package com.sonar.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Standard Ogg Opus for HTTP/files; Linx continues to use raw negotiated Opus packets. */
object OpusFiles {
    const val BIT_RATE = 24000
    private val encodingLock = Mutex()

    suspend fun forUpload(file: File): ByteArray = withContext(Dispatchers.IO) {
        val wav = file.readBytes()
        encodingLock.withLock {
            // Freeze bytes across retries, including process restarts. Never switch format after
            // an attempted upload: server idempotency fingerprints include the exact bytes.
            val cache = UploadAudioCache(file)
            val hash = MessageDigest.getInstance("SHA-256").digest(wav)
            cache.read(hash)?.let { return@withLock it }
            val temporary = File(file.path + ".encoding.opus")
            try {
                encode(wav, temporary)
                val encoded = temporary.readBytes()
                require(encoded.size in 1..(1000 * 1024)) { "Opus录音体积异常" }
                cache.commit(hash, encoded)
                android.util.Log.i("SonarVoice", "opus_file pcm_bytes=${wav.size} opus_bytes=${encoded.size} bitrate=$BIT_RATE")
                encoded
            } finally { temporary.delete() }
        }
    }

    fun forgetUpload(file: File) {
        UploadAudioCache(file).clear()
        File(file.path + ".upload.opus").delete()
        File(file.path + ".upload.sha256").delete()
    }

    private suspend fun encode(wav: ByteArray, output: File) {
        require(wav.size in 46..960044 && String(wav, 0, 4) == "RIFF" && String(wav, 36, 4) == "data")
        val inputHeader = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(inputHeader.getShort(20).toInt() == 1 && inputHeader.getShort(22).toInt() == 1 &&
            inputHeader.getInt(24) == 16000 && inputHeader.getShort(34).toInt() == 16 &&
            inputHeader.getInt(40) == wav.size - 44 && (wav.size - 44) % 2 == 0)
        val original = wav.copyOfRange(44, wav.size)
        // Ogg granules/pre-skip use 48kHz. Encode at that rate so Android's muxer
        // writes codec delay in the correct unit; flush one padded frame for the tail.
        val pcm = AndroidLinxCodec.resample(original, 16000, 48000) + ByteArray(1920)
        val encoder = MediaCodec.createEncoderByType("audio/opus")
        var muxer: MediaMuxer? = null
        var started = false
        var muxed = false
        try {
            val format = MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start(); started = true
            val target = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            muxer = target
            val info = MediaCodec.BufferInfo()
            var cursor = 0
            var inputDone = false
            var done = false
            var track = -1
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!done) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() < deadline) { "Opus编码超时" }
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!
                        val count = minOf(1920, pcm.size - cursor)
                        buffer.clear()
                        if (count > 0) buffer.put(pcm, cursor, count)
                        encoder.queueInputBuffer(index, 0, count, cursor * 1_000_000L / 96000,
                            if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        cursor += count
                        inputDone = count == 0
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = target.addTrack(encoder.outputFormat)
                        target.start(); muxed = true
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(track >= 0)
                                target.writeSampleData(track, encoder.getOutputBuffer(index)!!, info)
                            }
                            done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            target.stop(); muxed = false
            // Account for codec delay and final-frame padding in the standard Ogg granule.
            output.writeBytes(trimGranule(output.readBytes(), original.size / 2))
        } finally {
            if (muxed) runCatching { muxer?.stop() }
            muxer?.release()
            if (started) runCatching { encoder.stop() }
            encoder.release()
        }
    }

    internal fun trimGranule(bytes: ByteArray, samples16k: Int): ByteArray {
        require(samples16k in 1..480000)
        val output = ByteArrayOutputStream()
        var offset = 0
        var skip = -1
        var packetIndex = 0
        var audioSamples48k = 0L
        val packet = ByteArrayOutputStream()
        while (offset < bytes.size) {
            require(offset + 27 <= bytes.size && String(bytes, offset, 4) == "OggS")
            val count = bytes[offset + 26].toInt() and 255
            require(offset + 27 + count <= bytes.size)
            val size = 27 + count + (0 until count).sumOf { bytes[offset + 27 + it].toInt() and 255 }
            require(offset + size <= bytes.size)
            var payload = offset + 27 + count
            var completedAudio = false
            var keptSegments = count
            var ended = false
            for (index in 0 until count) {
                val length = bytes[offset + 27 + index].toInt() and 255
                packet.write(bytes, payload, length)
                payload += length
                if (length < 255) {
                    val complete = packet.toByteArray()
                    when (packetIndex) {
                        0 -> {
                            require(complete.size == 19 && String(complete, 0, 8) == "OpusHead")
                            skip = ByteBuffer.wrap(complete).order(ByteOrder.LITTLE_ENDIAN).getShort(10).toInt() and 65535
                        }
                        1 -> require(complete.size >= 8 && String(complete, 0, 8) == "OpusTags")
                        else -> {
                            audioSamples48k += OpusPackets.durationUs(complete) * 48 / 1000
                            completedAudio = true
                        }
                    }
                    packetIndex++
                    packet.reset()
                    if (completedAudio && audioSamples48k >= samples16k * 3L + skip) {
                        keptSegments = index + 1
                        ended = true
                        break
                    }
                }
            }
            // Ogg stores the END of the last completed packet, whereas some Android
            // muxers write its starting timestamp. Rebuild from actual Opus durations.
            var granule = if (completedAudio) audioSamples48k else if (packetIndex <= 2) 0L else -1L
            // Device encoders can emit extra flush packets. End on the FIRST packet
            // covering the recording; otherwise EOS can move backwards past a prior
            // page and FFmpeg cannot apply the intended end trim (RFC 7845 §4.4).
            if (ended) granule = samples16k * 3L + skip
            val page = bytes.copyOfRange(offset, offset + 27 + keptSegments) +
                bytes.copyOfRange(offset + 27 + count, payload)
            page[26] = keptSegments.toByte()
            page[5] = ((page[5].toInt() and 4.inv()) or if (ended) 4 else 0).toByte()
            val buffer = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putLong(6, granule)
            buffer.putInt(22, 0)
            var crc = 0
            for (byte in page) {
                crc = crc xor ((byte.toInt() and 255) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04c11db7 else 0 }
            }
            buffer.putInt(22, crc)
            output.write(page)
            if (ended) return output.toByteArray()
            offset += size
        }
        error("Opus编码未保留完整录音尾部")
    }

    suspend fun toWav(data: ByteArray, directory: File): ByteArray = withContext(Dispatchers.IO) {
        if (!data.take(4).toByteArray().contentEquals("OggS".toByteArray())) return@withContext data
        val file = File.createTempFile("opus-play-", ".opus", directory)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            file.writeBytes(data)
            extractor.setDataSource(file.path)
            require(extractor.trackCount == 1)
            val format = extractor.getTrackFormat(0)
            require(format.getString(MediaFormat.KEY_MIME) == "audio/opus" && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType("audio/opus")
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArrayOutputStream()
            var rate = 48000
            var inputDone = false
            var done = false
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!done) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() < deadline) { "Opus解码超时" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index)!!
                        input.clear()
                        val count = extractor.readSampleData(input, 0)
                        decoder.queueInputBuffer(index, 0, maxOf(0, count), maxOf(0, extractor.sampleTime),
                            if (count < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (count < 0) inputDone = true else extractor.advance()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = decoder.outputFormat
                        rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        require(actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                        require(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) || actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                val bytes = ByteArray(info.size)
                                decoder.getOutputBuffer(index)!!.apply { position(info.offset); get(bytes) }
                                pcm.write(bytes)
                                require(pcm.size() <= rate * 2 * 31)
                            }
                            done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            val decoded = AndroidLinxCodec.resample(pcm.toByteArray(), rate, 16000)
            val expected = decodedSamples16k(data) * 2
            require(decoded.size >= expected - 2) { "Opus录音未解码完整" }
            // Android extractors do not consistently honor EOS end trimming.
            val normalized = decoded.copyOf(minOf(decoded.size, expected))
            Wav.header(normalized.size) + normalized
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release(); extractor.release(); file.delete()
        }
    }

    private fun decodedSamples16k(data: ByteArray): Int {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        var skip = -1
        var samples = -1L
        while (offset < data.size) {
            require(offset + 27 <= data.size && String(data, offset, 4) == "OggS")
            val count = data[offset + 26].toInt() and 255
            require(offset + 27 + count <= data.size)
            val size = 27 + count + (0 until count).sumOf { data[offset + 27 + it].toInt() and 255 }
            require(offset + size <= data.size)
            if (offset == 0) {
                val begin = offset + 27 + count
                require(size >= 27 + count + 19 && String(data, begin, 8) == "OpusHead")
                skip = buffer.getShort(begin + 10).toInt() and 65535
            }
            if (data[offset + 5].toInt() and 4 != 0) samples = buffer.getLong(offset + 6) - skip
            offset += size
        }
        require(samples in 1..1440000)
        return (samples / 3).toInt()
    }
}
