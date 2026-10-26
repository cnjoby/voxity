package com.sonar.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Wav {
    fun header(bytes: Int, sampleRate: Int = 16000): ByteArray =
        ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(bytes + 36)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(bytes)
            }
            .array()

    fun synthetic(): ByteArray = header(3200) + ByteArray(3200)
}

class AudioCoordinator(context: Context, private val onFocusLost: () -> Unit = {}) {
    private val manager = context.getSystemService(AudioManager::class.java)
    val gate = AudioGate()
    @Volatile private var recording = false
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    @Volatile private var streamOwner: Job? = null

    fun stopRecording() {
        recording = false
    }

    fun stopPlayback() {
        streamOwner?.cancel()
        player?.runCatching { stop() }
    }

    fun close() {
        stopRecording()
        stopPlayback()
    }

    /** Producer runs once. The audio gate/focus is acquired only for permitted PCM. */
    suspend fun <T> stream(
        mayStart: () -> Boolean,
        onStarted: () -> Unit,
        onReference: (PcmAudio) -> Unit,
        onPlaybackClock: ((() -> Int) -> Unit) = {},
        produce: suspend (suspend (PcmAudio) -> Unit) -> T,
    ): T = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        var request: AudioFocusRequest? = null
        var entered = false
        var started = false
        var written = 0L
        var rate = 0
        val retired = java.util.concurrent.atomic.AtomicBoolean()
        val played = java.util.concurrent.atomic.AtomicInteger()
        var position: (() -> Int)? = null
        val owner = currentCoroutineContext()[Job]!!
        val pcmLock = Mutex()
        streamOwner = owner
        try {
            fun start() {
                if (!started) {
                    check(mayStart()) { "会话已改变，本轮不播放" }
                    track!!.play()
                    started = true
                    onStarted()
                }
            }
            val result = produce { frame ->
                pcmLock.withLock {
                    owner.ensureActive()
                    currentCoroutineContext().ensureActive()
                    check(mayStart()) { "本轮播放已暂停" }
                    if (track == null) {
                        check(gate.enter(AudioMode.PROMPT)) { "其他音频正在播放" }
                        entered = true
                        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                                if (change < 0) { owner.cancel(); onFocusLost() }
                            }.build()
                        request = focusRequest
                        check(manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "当前无法播放" }
                        rate = frame.rate
                        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0)
                        track = AudioTrack.Builder().setAudioAttributes(attributes)
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, rate * 2 / 4)).build()
                        check(track!!.state == AudioTrack.STATE_INITIALIZED)
                        val activeTrack = track!!
                        val activeRate = rate
                        val readPosition = {
                            if (!retired.get()) runCatching {
                                played.set(((activeTrack.playbackHeadPosition.toLong() and 0xffffffffL) * 16000 / activeRate).toInt())
                            }
                            played.get()
                        }
                        position = readPosition
                        onPlaybackClock(readPosition)
                    }
                    require(frame.rate == rate && frame.bytes.size % 2 == 0)
                    require(written + frame.bytes.size / 2 <= rate * 60L)
                    onReference(frame)
                    var offset = 0
                    while (offset < frame.bytes.size) {
                        owner.ensureActive()
                        currentCoroutineContext().ensureActive()
                        check(mayStart()) { "本轮播放已暂停" }
                        val count = track!!.write(frame.bytes, offset, frame.bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0) { "流式音频播放失败" }
                        offset += count
                        written += count / 2
                        if (written >= rate / 5 || count == 0) start()
                        if (count == 0) delay(5)
                    }
                }
            }
            if (written > 0) {
                start()
                val deadline = SystemClock.elapsedRealtime() + 65000
                while ((track!!.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                    currentCoroutineContext().ensureActive()
                    check(mayStart() && SystemClock.elapsedRealtime() < deadline) { "流式播放未完成" }
                    delay(10)
                }
            }
            result
        } finally {
            // stop() alone drains AudioTrack. Pause/flush discards queued samples immediately.
            withContext(NonCancellable) {
                pcmLock.withLock {
                    track?.let { current ->
                        position?.invoke()
                        retired.set(true)
                        runCatching { current.pause() }
                        runCatching { current.flush() }
                        runCatching { current.release() }
                    }
                    request?.let { manager.abandonAudioFocusRequest(it) }
                    if (streamOwner === owner) streamOwner = null
                    if (entered) gate.leave()
                }
            }
        }
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

    suspend fun play(file: File, mode: AudioMode = AudioMode.ORIGINAL, ownSpeaker: Boolean = false,
        mayStart: () -> Boolean = { true }, onStarted: () -> Unit = {}) =
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
                                if (change < 0 && continuation.isActive) {
                                    onFocusLost()
                                    continuation.cancel()
                                }
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
                        if (ownSpeaker) {
                            if (android.os.Build.VERSION.SDK_INT < 28) {
                                continuation.resumeWithException(IllegalStateException("自然交流扬声器路由需要 Android 9 或以上"))
                                return@suspendCancellableCoroutine
                            }
                            val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            if (speaker == null || !media.setPreferredDevice(speaker)) {
                                continuation.resumeWithException(IllegalStateException("本机扬声器不可用"))
                                return@suspendCancellableCoroutine
                            }
                        }
                        if (!mayStart()) {
                            continuation.cancel()
                            return@suspendCancellableCoroutine
                        }
                        media.start()
                        onStarted()
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
