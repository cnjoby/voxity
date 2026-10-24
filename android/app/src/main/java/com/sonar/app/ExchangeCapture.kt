package com.sonar.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single microphone owner. Echo support is reported, never assumed from the model name. */
class ExchangeCapture {
    @Volatile private var running = true
    @Volatile private var recorder: AudioRecord? = null
    @Volatile var echoProtected = false
        private set
    @Volatile var speaking = false
        private set

    fun stop() {
        running = false
        recorder?.runCatching { stop() }
    }

    @SuppressLint("MissingPermission")
    suspend fun run(onStart: () -> Unit, onSegment: (ByteArray, Boolean) -> Unit, allowed: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "本机不支持自然交流录音" }
        val mic = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, 4096)).build()
        recorder = mic
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        try {
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "麦克风不可用" }
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(mic.audioSessionId)?.apply { enabled = true }
            if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(mic.audioSessionId)?.apply { enabled = true }
            echoProtected = echo?.enabled == true
            val vad = SpeechSegments(onStart = { speaking = true; onStart() }, onSegment = { bytes, cut ->
                speaking = false
                onSegment(bytes, cut)
            })
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统未允许麦克风采集" }
            val frame = ByteArray(640)
            var count = 0
            while (running) {
                val n = mic.read(frame, count, frame.size - count)
                if (!running) break
                check(n >= 0) { "麦克风被系统中断" }
                if (n == 0) continue
                count += n
                if (count == frame.size) {
                    if (allowed()) vad.accept(frame) else vad.reset()
                    speaking = vad.active; count = 0
                }
            }
        } finally {
            speaking = false
            mic.runCatching { stop() }
            echo?.release(); noise?.release(); mic.release()
            recorder = null
        }
    }
}
