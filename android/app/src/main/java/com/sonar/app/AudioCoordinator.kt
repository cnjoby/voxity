package com.sonar.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object Wav {
    fun header(bytes: Int): ByteArray =
        ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(bytes + 36)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(16000)
                putInt(32000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(bytes)
            }
            .array()

    fun synthetic(): ByteArray = header(3200) + ByteArray(3200)
}

class AudioCoordinator(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    val gate = AudioGate()
    @Volatile private var recording = false
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    fun stopRecording() {
        recording = false
    }

    fun stopPlayback() {
        player?.runCatching { stop() }
    }

    fun close() {
        stopRecording()
        stopPlayback()
    }

    @SuppressLint(
        "MissingPermission"
    ) // Activity obtains RECORD_AUDIO; failures remain private drafts.
    suspend fun record(file: File): File =
        withContext(Dispatchers.IO) {
            check(gate.enter(AudioMode.RECORDING)) { "请先结束当前音频" }
            recording = true
            var recorder: AudioRecord? = null
            try {
                val min =
                    AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                check(min > 0) { "设备不支持16kHz录音" }
                recorder =
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(16000)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(min * 2, 4096))
                        .build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                recorder.startRecording()
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    out.write(ByteArray(44))
                    val buf = ByteArray(640)
                    val start = SystemClock.elapsedRealtime()
                    var total = 0
                    while (
                        recording && total < 256000 && SystemClock.elapsedRealtime() - start < 8000
                    ) {
                        val read = recorder.read(buf, 0, minOf(buf.size, 256000 - total))
                        check(read >= 0) { "录音中断，请重录" }
                        if (read > 0) {
                            out.write(buf, 0, read)
                            total += read
                        }
                    }
                    check(total > 0 && total % 2 == 0) { "没有录到声音，请重试" }
                    out.seek(0)
                    out.write(Wav.header(total))
                }
                file
            } catch (e: Exception) {
                file.delete()
                throw e
            } finally {
                recording = false
                recorder?.runCatching { stop() }
                recorder?.release()
                gate.leave()
            }
        }

    suspend fun play(file: File, mode: AudioMode = AudioMode.ORIGINAL) =
        withContext(Dispatchers.Main.immediate) {
            check(gate.enter(mode)) { "请先结束当前音频" }
            var current: MediaPlayer? = null
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val attributes =
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    val request =
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener { change ->
                                if (change < 0 && continuation.isActive) continuation.cancel()
                            }
                            .build()
                    focus = request
                    if (
                        manager.requestAudioFocus(request) !=
                            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    ) {
                        continuation.resumeWithException(IllegalStateException("当前无法播放，请稍后再听"))
                        return@suspendCancellableCoroutine
                    }
                    val media = MediaPlayer()
                    current = media
                    player = media
                    media.setAudioAttributes(attributes)
                    media.setOnCompletionListener {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    media.setOnErrorListener { _, _, _ ->
                        if (continuation.isActive)
                            continuation.resumeWithException(IllegalStateException("原声播放失败"))
                        true
                    }
                    continuation.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            media.runCatching { stop() }
                        }
                    }
                    try {
                        media.setDataSource(file.absolutePath)
                        media.prepare()
                        media.start()
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            } finally {
                current?.release()
                player = null
                focus?.let { manager.abandonAudioFocusRequest(it) }
                focus = null
                gate.leave()
            }
        }
}
