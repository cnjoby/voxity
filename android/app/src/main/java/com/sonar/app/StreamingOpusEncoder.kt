package com.sonar.app

import android.media.MediaCodec
import android.media.MediaFormat

/** Incremental raw Opus encoder. No Ogg headers are sent to the Linx socket. */
internal class StreamingOpusEncoder(private val params: LinxAudioParams) : AutoCloseable {
    private val codec = MediaCodec.createEncoderByType("audio/opus")
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    private var done = false
    private val pending = mutableListOf<ByteArray>()

    init {
        try {
            require(params.frameMs >= 20)
            val format = MediaFormat.createAudioFormat("audio/opus", params.rate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OpusFiles.BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Exception) { codec.release(); throw error }
    }

    fun push(pcm16k: ByteArray): List<ByteArray> {
        val pcm = AndroidLinxCodec.resample(pcm16k, 16000, params.rate)
        val output = mutableListOf<ByteArray>()
        val deadline = System.nanoTime() + 2_000_000_000L
        var index = codec.dequeueInputBuffer(0)
        while (index < 0) {
            drain(output, 1000)
            check(System.nanoTime() < deadline) { "Opus流编码阻塞" }
            index = codec.dequeueInputBuffer(1000)
        }
        codec.getInputBuffer(index)!!.apply { clear(); put(pcm) }
        codec.queueInputBuffer(index, 0, pcm.size, pts, 0)
        pts += pcm.size * 1_000_000L / (params.rate * 2)
        require(pts <= 30_000_000L)
        drain(output, 0)
        return output
    }

    fun finish(): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        var sent = false
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!done) {
            check(System.nanoTime() < deadline) { "Opus编码未结束" }
            if (!sent) {
                val index = codec.dequeueInputBuffer(1000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sent = true
                }
            }
            drain(output, 1000)
        }
        output.addAll(OpusPackets.group(pending, params.frameMs))
        pending.clear()
        return output
    }

    private fun drain(output: MutableList<ByteArray>, waitUs: Long) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, waitUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size)
                            codec.getOutputBuffer(index)!!.apply { position(info.offset); get(bytes) }
                            require(bytes[0].toInt() and 3 == 0)
                            if (pending.isNotEmpty() && (pending.first()[0].toInt() and 252) != (bytes[0].toInt() and 252)) {
                                output.addAll(OpusPackets.group(pending, params.frameMs)); pending.clear()
                            }
                            pending.add(bytes)
                            if (pending.sumOf { OpusPackets.durationUs(it) } >= params.frameMs * 1000L) {
                                output.addAll(OpusPackets.group(pending, params.frameMs)); pending.clear()
                            }
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (done) return
                }
            }
        }
    }

    override fun close() { runCatching { codec.stop() }; codec.release() }
}
