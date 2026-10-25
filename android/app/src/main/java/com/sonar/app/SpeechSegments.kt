package com.sonar.app

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.sqrt

/** Local PCM16 VAD. No cloud calls, no files until one bounded speech segment closes. */
class SpeechSegments(
    private val onStart: () -> Unit,
    private val onSegment: (ByteArray, Boolean) -> Unit,
    private val threshold: Double = 700.0,
    private val tailFrames: Int = 60,
) {
    private val preRoll = ArrayDeque<ByteArray>()
    private var samples = ByteArrayOutputStream()
    private var leading = 0
    private var quiet = 0
    private var voiced = 0
    private var truncated = false
    private var waitForSilence = false
    private var cooldown = 0
    var speaking = false
        private set

    val active: Boolean get() = speaking || waitForSilence

    fun accept(frame: ByteArray) {
        require(frame.size == 640)
        val speech = rms(frame) > threshold
        if (waitForSilence) {
            cooldown = if (speech) 0 else cooldown + 1
            if (cooldown >= tailFrames) { waitForSilence = false; cooldown = 0 }
            return
        }
        if (!speaking) {
            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 12) preRoll.removeFirst()
            leading = if (speech) leading + 1 else 0
            if (leading >= 4) {
                speaking = true
                onStart() // Stop playback locally, before any ASR/network result.
                samples = ByteArrayOutputStream(960000)
                preRoll.forEach { samples.write(it) }
                preRoll.clear()
                voiced = leading
                quiet = 0
                truncated = false
            }
            return
        }
        if (samples.size() < 960000) samples.write(frame)
        else truncated = true
        if (speech) { voiced++; quiet = 0 } else quiet++
        if (quiet >= tailFrames || samples.size() >= 960000) {
            val pcm = samples.toByteArray()
            val cut = truncated || samples.size() >= 960000
            val enoughSpeech = voiced >= 8
            reset()
            waitForSilence = cut
            if (enoughSpeech && pcm.size >= 6400) onSegment(pcm, cut)
        }
    }

    fun reset() {
        samples = ByteArrayOutputStream()
        preRoll.clear()
        leading = 0
        quiet = 0
        voiced = 0
        truncated = false
        speaking = false
        waitForSilence = false
        cooldown = 0
    }

    companion object {
        fun rms(frame: ByteArray): Double {
            var sum = 0.0
            for (i in 0 until frame.size - 1 step 2) {
                val sample = ((frame[i].toInt() and 255) or (frame[i+1].toInt() shl 8)).toShort().toInt()
                sum += sample.toDouble() * sample
            }
            return sqrt(sum / (frame.size / 2).coerceAtLeast(1))
        }
    }
}

internal fun localExchangeCommand(text: String): String? {
    val value = text.replace(Regex("[\\s，。！？,.!?]"), "")
    return when (value) {
        "安静", "安静一下", "别说了", "先别说了", "暂停交流" -> "quiet"
        "继续", "恢复", "恢复交流", "恢复收听", "继续交流" -> "resume"
        "停", "停一下", "停止播放", "暂停播放", "稍后听" -> "stop"
        "刚才那句别发", "先别发", "不要发出去", "撤回刚才那句", "撤回刚才的分享" -> "undo"
        "不用等了", "别等了", "取消刚才的问题" -> "cancel"
        "结束", "退出", "先到这里", "关闭麦克风", "结束交流", "结束免提" -> "end"
        else -> null
    }
}


/** Suppress recognizable speaker leakage, including a captured fragment of the playing original. */
internal fun looksLikePlaybackEcho(transcript: String, original: String?, minLength: Int = 4): Boolean {
    fun clean(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    val spoken = clean(transcript)
    val played = clean(original ?: return false)
    if (spoken.length < minLength || played.length < minLength) return false
    if (spoken.contains(played) || played.contains(spoken)) return true
    var row = IntArray(played.length + 1) { it }
    for (i in spoken.indices) {
        val next = IntArray(played.length + 1); next[0] = i + 1
        for (j in played.indices) next[j+1] = minOf(next[j] + 1, row[j+1] + 1,
            row[j] + if (spoken[i] == played[j]) 0 else 1)
        row = next
    }
    return row.last().toDouble() / maxOf(spoken.length, played.length) <= 0.2
}
