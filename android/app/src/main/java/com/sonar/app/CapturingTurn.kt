package com.sonar.app

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicBoolean

internal data class LinxTurnHooks(
    val route: suspend (String) -> Boolean,
    val pcm: suspend (PcmAudio) -> Unit,
    val spoken: (String) -> Unit,
)

/** One capture, one socket, one local recognizer. All queues are bounded to a 30s turn. */
internal class CapturingTurn(scope: CoroutineScope, commands: LocalSpeechCommands, config: DeviceConfig, cloud: Boolean, val contextPostId: String?,
    recognizeLocal: suspend (ReceiveChannel<ByteArray>) -> String? = commands::recognizeFrames) {
    val id = UUID.randomUUID().toString()
    private val frames = CaptureFrameQueues(cloud)
    private val localFrames get() = frames.local
    private val cloudFrames get() = frames.cloud
    private val hooks = CompletableDeferred<LinxTurnHooks>()
    @Volatile private var bound: LinxTurnHooks? = null
    val local = scope.async(Dispatchers.Default) { recognizeLocal(localFrames) }
    val reply: Deferred<LinxReply>? = if (cloud) scope.async(Dispatchers.IO) {
        LinxSession(allowLocal = BuildConfig.DEBUG).exchange(null, config, pcmFrames = cloudFrames,
            traceId = id, route = { text ->
                val active = hooks.await()
                withContext(Dispatchers.Main.immediate) { active.route(text) }
            }, onPcm = { hooks.await().pcm(it) },
            spokenText = { bound?.spoken?.invoke(it) })
    } else null

    fun accept(frame: ByteArray) {
        frames.accept(frame)
    }

    fun finish() { frames.finish() }
    fun bind(value: LinxTurnHooks) { bound = value; check(hooks.complete(value)) }
    fun cancel() { frames.cancel(); local.cancel(); reply?.cancel(); hooks.cancel() }
}

internal class CaptureFrameQueues(private val sendCloud: Boolean, capacity: Int = 1500) {
    val local = Channel<ByteArray>(capacity)
    val cloud = Channel<ByteArray>(capacity)
    private val closed = AtomicBoolean()

    fun accept(frame: ByteArray) {
        if (closed.get()) return
        val localResult = local.trySend(frame)
        if (closed.get() || localResult.isClosed) return
        if (localResult.isFailure) { cancel(); error("本地控制音频缓冲已满") }
        if (sendCloud) {
            val cloudResult = cloud.trySend(frame)
            if (closed.get() || cloudResult.isClosed) return
            if (cloudResult.isFailure) { cancel(); error("语音上行缓冲已满，本次不发送业务") }
        }
    }

    fun finish() { closed.set(true); local.close(); cloud.close() }
    fun cancel() { closed.set(true); local.cancel(); cloud.cancel() }
}
