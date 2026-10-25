package com.sonar.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Reject a strongly correlated speaker-only frame before it can trigger a false interruption. */
internal class PlaybackEchoGuard(private val now: () -> Long) {
    private data class Reference(val samples: ShortArray, val started: Long)
    @Volatile private var reference: Reference? = null
    @Volatile private var stoppedAt = Long.MAX_VALUE

    fun begin(wav: ByteArray) {
        reference = pcm16Mono(wav)?.let { Reference(it, now()) }
        stoppedAt = Long.MAX_VALUE
    }

    fun stop() { stoppedAt = now() }

    fun isEcho(frame: ByteArray): Boolean {
        val ref = reference ?: return false
        val time = now()
        if (time - stoppedAt > 600) return false
        if (frame.size != 640 || SpeechSegments.rms(frame) < 500) return false
        val position = ((time - ref.started) * 16).toInt()
        val end = minOf(position + 320, ref.samples.size - 320)
        val start = maxOf(0, position - 9600)
        if (end < start) return false
        val input = ShortArray(80) { i -> ((frame[i * 8].toInt() and 255) or (frame[i * 8 + 1].toInt() shl 8)).toShort() }
        val mean = input.sumOf { it.toDouble() } / input.size
        val power = input.sumOf { (it - mean) * (it - mean) }
        if (power < 1) return false
        for (offset in start..end step 32) {
            var sum = 0.0
            for (i in input.indices) sum += ref.samples[offset + i * 4]
            val otherMean = sum / input.size
            var cross = 0.0
            var otherPower = 0.0
            for (i in input.indices) {
                val value = ref.samples[offset + i * 4] - otherMean
                cross += (input[i] - mean) * value
                otherPower += value * value
            }
            if (otherPower > 1 && abs(cross) / sqrt(power * otherPower) > 0.87) return true
        }
        return false
    }

    companion object {
        /** Read RIFF chunks rather than assuming TTS always writes a 44-byte header. */
        private fun pcm16Mono(wav: ByteArray): ShortArray? {
            if (wav.size < 44 || String(wav, 0, 4) != "RIFF" || String(wav, 8, 4) != "WAVE") return null
            val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            var rate = 0
            var supported = false
            var offset = 12
            while (offset + 8 <= wav.size) {
                val name = String(wav, offset, 4)
                val size = buffer.getInt(offset + 4)
                if (size < 0 || size > wav.size - offset - 8) return null
                val begin = offset + 8
                if (name == "fmt " && size >= 16) {
                    rate = buffer.getInt(begin + 4)
                    supported = buffer.getShort(begin).toInt() == 1 && buffer.getShort(begin + 2).toInt() == 1 && buffer.getShort(begin + 14).toInt() == 16
                }
                if (name == "data" && supported && rate in 8000..48000) {
                    val count = size / 2
                    if (count == 0) return null
                    return ShortArray((count.toLong() * 16000 / rate).toInt()) { index ->
                        buffer.getShort(begin + minOf(count - 1, (index.toLong() * rate / 16000).toInt()) * 2)
                    }
                }
                offset = begin + size + (size and 1)
            }
            return null
        }
    }
}
