package com.sonar.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A decoder belongs to exactly one reply; packet boundaries and decoder state are preserved. */
internal class StreamingOpusDecoder(params: LinxAudioParams) : LinxPcmDecoder {
    private val codec = MediaCodec.createDecoderByType("audio/opus")
    private val info = MediaCodec.BufferInfo()
    private var rate = 48000
    private var pts = 0L
    private var samples = 0L
    private var done = false

    init {
        try {
            val header = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray()); put(1); put(1); putShort(0)
                putInt(params.rate); putShort(0); put(0)
            }.array()
            val format = MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(header))
                setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
                setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000).apply { flip() })
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (error: Exception) {
            codec.release()
            throw error
        }
    }

    override fun push(packet: ByteArray): List<PcmAudio> {
        check(!done)
        val output = mutableListOf<PcmAudio>()
        val deadline = System.nanoTime() + 2_000_000_000L
        var index = codec.dequeueInputBuffer(0)
        while (index < 0) {
            drain(output, 1000)
            check(System.nanoTime() < deadline) { "Opus流解码阻塞" }
            index = codec.dequeueInputBuffer(1000)
        }
        val buffer = codec.getInputBuffer(index)!!
        buffer.clear()
        require(packet.size <= buffer.capacity())
        buffer.put(packet)
        codec.queueInputBuffer(index, 0, packet.size, pts, 0)
        pts += OpusPackets.durationUs(packet)
        require(pts <= 60_000_000L)
        drain(output, 0)
        return output
    }

    override fun finish(): List<PcmAudio> {
        val output = mutableListOf<PcmAudio>()
        val deadline = System.nanoTime() + 3_000_000_000L
        var sent = false
        while (!done) {
            check(System.nanoTime() < deadline) { "Opus流未正确结束" }
            if (!sent) {
                val index = codec.dequeueInputBuffer(1000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sent = true
                }
            }
            drain(output, 1000)
        }
        return output
    }

    private fun drain(output: MutableList<PcmAudio>, waitUs: Long) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, waitUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                    require(!format.containsKey(MediaFormat.KEY_PCM_ENCODING) || format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            val bytes = ByteArray(info.size)
                            codec.getOutputBuffer(index)!!.apply { position(info.offset); get(bytes) }
                            samples += bytes.size / 2
                            require(samples <= rate * 60L)
                            output.add(PcmAudio(bytes, rate))
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (done) return
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }
}
