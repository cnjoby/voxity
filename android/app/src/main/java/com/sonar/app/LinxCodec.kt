package com.sonar.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Raw frames on the wire, never WAV/Opus headers disguised as speech. */
data class LinxAudioParams(
    val format: String = "pcm",
    val rate: Int = 16000,
    val frameMs: Int = 20,
) {
    fun hello() =
        json(
            "format" to format,
            "sample_rate" to rate,
            "channels" to 1,
            "bit_depth" to 16,
            "endianness" to "little",
            "frame_duration" to frameMs,
            "frame_size" to rate * frameMs / 1000,
            "sample_format" to "signed_int16",
            "play_buffer_duration" to 1000,
        )

    companion object {
        fun parse(j: org.json.JSONObject?): LinxAudioParams {
            if (j == null) return LinxAudioParams()
            val format = j.text("format")
            val rate = j.optInt("sample_rate")
            val duration = j.optInt("frame_duration", if (format == "pcm") 20 else 60)
            require(
                format in setOf("pcm", "opus") &&
                    rate in setOf(8000, 12000, 16000, 24000, 48000) &&
                    j.optInt("channels", 1) == 1 &&
                    duration in setOf(10, 20, 40, 60)
            ) {
                "灵矽协商的音频格式暂不支持"
            }
            if (format == "pcm")
                require(
                    j.optInt("bit_depth", 16) == 16 &&
                        j.text("endianness", "little") == "little" &&
                        j.text("sample_format", "signed_int16") == "signed_int16"
                ) {
                    "灵矽 PCM 参数不兼容"
                }
            return LinxAudioParams(format, rate, duration)
        }
    }
}

data class PcmAudio(val bytes: ByteArray, val rate: Int)

interface LinxCodec {
    suspend fun encode(pcm16k: ByteArray, params: LinxAudioParams): List<ByteArray>

    suspend fun decode(packets: List<ByteArray>, params: LinxAudioParams): PcmAudio
}

/** Platform Opus codecs; old phones without an encoder fail explicitly before listen/start. */
class AndroidLinxCodec : LinxCodec {
    override suspend fun encode(pcm16k: ByteArray, params: LinxAudioParams): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val pcm = resample(pcm16k, 16000, params.rate)
            if (params.format == "pcm")
                (pcm.indices step (params.rate * params.frameMs / 1000 * 2)).map { offset ->
                    pcm.copyOfRange(
                        offset,
                        minOf(pcm.size, offset + params.rate * params.frameMs / 1000 * 2),
                    )
                }
            else {
                val frameBytes = params.rate * 20 / 1000 * 2
                val chunks =
                    (pcm.indices step frameBytes).map { offset ->
                        pcm.copyOfRange(offset, minOf(pcm.size, offset + frameBytes))
                            .copyOf(frameBytes)
                    }
                val format =
                    MediaFormat.createAudioFormat("audio/opus", params.rate, 1).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(4096, frameBytes))
                    }
                val packets = transcode(chunks, format, true) { _, _ -> }.first
                OpusPackets.group(packets, params.frameMs)
            }
        }

    override suspend fun decode(packets: List<ByteArray>, params: LinxAudioParams): PcmAudio =
        withContext(Dispatchers.IO) {
            if (params.format == "pcm") {
                val bytes =
                    packets
                        .fold(ByteArrayOutputStream()) { out, frame -> out.apply { write(frame) } }
                        .toByteArray()
                require(bytes.isNotEmpty() && bytes.size % 2 == 0) { "灵矽返回的 PCM 音频不完整" }
                PcmAudio(bytes, params.rate)
            } else {
                val header =
                    ByteBuffer.allocate(19)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .apply {
                            put("OpusHead".toByteArray())
                            put(1)
                            put(1)
                            putShort(0)
                            putInt(params.rate)
                            putShort(0)
                            put(0)
                        }
                        .array()
                val format =
                    MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(header))
                        setByteBuffer(
                            "csd-1",
                            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply {
                                flip()
                            },
                        )
                        setByteBuffer(
                            "csd-2",
                            ByteBuffer.allocate(8)
                                .order(ByteOrder.nativeOrder())
                                .putLong(80000000)
                                .apply { flip() },
                        )
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                    }
                var rate = 48000
                val output =
                    transcode(packets, format, false) { sampleRate, channels ->
                            require(channels == 1) { "灵矽解码声道不兼容" }
                            rate = sampleRate
                        }
                        .first
                val bytes =
                    output
                        .fold(ByteArrayOutputStream()) { out, frame -> out.apply { write(frame) } }
                        .toByteArray()
                require(bytes.isNotEmpty() && bytes.size <= rate * 2 * 60) { "灵矽语音回复为空或过长" }
                PcmAudio(bytes, rate)
            }
        }

    private suspend fun transcode(
        chunks: List<ByteArray>,
        format: MediaFormat,
        encoding: Boolean,
        changed: (Int, Int) -> Unit,
    ): Pair<List<ByteArray>, Int> {
        val codec =
            try {
                if (encoding) MediaCodec.createEncoderByType("audio/opus")
                else MediaCodec.createDecoderByType("audio/opus")
            } catch (_: Exception) {
                error("手机缺少 Opus ${if(encoding) "编码器" else "解码器"}，请尝试 PCM")
            }
        var started = false
        try {
            codec.configure(
                format,
                null,
                null,
                if (encoding) MediaCodec.CONFIGURE_FLAG_ENCODE else 0,
            )
            codec.start()
            started = true
            val info = MediaCodec.BufferInfo()
            val output = mutableListOf<ByteArray>()
            var cursor = 0
            var inputDone = false
            var done = false
            var pts = 0L
            var size = 0
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!done) {
                currentCoroutineContext().ensureActive()
                check(System.nanoTime() < deadline) { "灵矽音频编解码超时" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val chunk = chunks.getOrNull(cursor)
                        if (chunk == null) {
                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                pts,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val buffer = codec.getInputBuffer(index)!!
                            require(chunk.size <= buffer.capacity()) { "灵矽音频帧超过手机解码缓冲" }
                            buffer.clear()
                            buffer.put(chunk)
                            codec.queueInputBuffer(index, 0, chunk.size, pts, 0)
                            pts +=
                                if (encoding)
                                    chunk.size / 2 * 1_000_000L /
                                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                else OpusPackets.durationUs(chunk)
                            cursor++
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        if (!encoding) {
                            val actual = codec.outputFormat
                            require(
                                (if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                else AudioFormat.ENCODING_PCM_16BIT) ==
                                    AudioFormat.ENCODING_PCM_16BIT
                            )
                            changed(
                                actual.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            )
                        }
                    else ->
                        if (index >= 0) {
                            try {
                                if (
                                    info.size > 0 &&
                                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    val buffer = codec.getOutputBuffer(index)!!
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val bytes = ByteArray(info.size)
                                    buffer.get(bytes)
                                    size += bytes.size
                                    require(size <= 6 * 1024 * 1024) { "灵矽音频过长" }
                                    output.add(bytes)
                                }
                                done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        }
                }
            }
            return output to size
        } finally {
            if (started) runCatching { codec.stop() }
            codec.release()
        }
    }

    companion object {
        fun resample(bytes: ByteArray, from: Int, to: Int): ByteArray {
            require(bytes.isNotEmpty() && bytes.size % 2 == 0)
            if (from == to) return bytes
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = (input.limit().toLong() * to / from).toInt()
            val out = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { index ->
                val position = index.toDouble() * from / to
                val left = position.toInt().coerceAtMost(input.limit() - 1)
                val right = (left + 1).coerceAtMost(input.limit() - 1)
                val fraction = position - left
                out.putShort(
                    (input[left] * (1 - fraction) + input[right] * fraction)
                        .roundToInt()
                        .coerceIn(-32768, 32767)
                        .toShort()
                )
            }
            return out.array()
        }
    }
}

/** RFC 6716 raw-packet framing. Preserve TOC changes; never concatenate encoded packets. */
object OpusPackets {
    private fun frameUs(toc: Int): Long {
        val config = toc ushr 3
        return if (config < 12) longArrayOf(10000, 20000, 40000, 60000)[config % 4]
        else if (config < 16) longArrayOf(10000, 20000)[config % 2]
        else longArrayOf(2500, 5000, 10000, 20000)[config % 4]
    }

    fun durationUs(packet: ByteArray): Long {
        require(packet.isNotEmpty())
        val code = packet[0].toInt() and 3
        val count =
            when (code) {
                0 -> 1
                1,
                2 -> 2
                else -> {
                    require(packet.size > 1)
                    packet[1].toInt() and 63
                }
            }
        val duration = frameUs(packet[0].toInt() and 255) * count
        require(count in 1..48 && duration <= 120000) { "Opus 帧长度无效" }
        return duration
    }

    fun group(packets: List<ByteArray>, maxMs: Int): List<ByteArray> {
        // Android encoder emits one 20 ms frame per packet. Flush on format/mode changes.
        val result = mutableListOf<ByteArray>()
        val group = mutableListOf<ByteArray>()
        var duration = 0L
        fun flush() {
            if (group.isEmpty()) return
            if (group.size == 1) result.add(group[0])
            else {
                val out = ByteArrayOutputStream()
                out.write((group[0][0].toInt() and 252) or 3)
                out.write(128 or group.size)
                group.dropLast(1).forEach { frame ->
                    val length = frame.size - 1
                    require(length <= 1275)
                    if (length < 252) out.write(length)
                    else {
                        val first = 252 + (length and 3)
                        out.write(first)
                        out.write((length - first) / 4)
                    }
                }
                group.forEach { out.write(it, 1, it.size - 1) }
                result.add(out.toByteArray())
            }
            group.clear()
            duration = 0
        }
        packets.forEach { packet ->
            val us = durationUs(packet)
            require(packet[0].toInt() and 3 == 0) { "手机 Opus 编码器帧形式暂不兼容" }
            require(us <= maxMs * 1000L) { "手机 Opus 编码器帧时长暂不兼容" }
            if (
                group.isNotEmpty() &&
                    ((packet[0].toInt() and 252) != (group[0][0].toInt() and 252) ||
                        duration + us > maxMs * 1000L)
            )
                flush()
            group.add(packet)
            duration += us
        }
        flush()
        return result
    }
}
