package com.sonar.app

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class LinxAuthFailure : IOException("灵矽设备凭证已失效，请重新检查绑定")

data class LinxReply(
    val transcript: String,
    val assistantText: String,
    val audio: PcmAudio?,
    val params: LinxAudioParams,
)

/**
 * One socket per recorded clip. Public transcription and private assistant replies stay separate.
 */
class LinxSession(
    private val client: OkHttpClient = sharedClient,
    private val codec: LinxCodec = AndroidLinxCodec(),
    private val allowLocal: Boolean = false,
    private val handshakeMs: Long = 10000,
    private val responseMs: Long = 45000,
) {
    companion object {
        private val sharedClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    }
    private sealed interface Event {
        class Text(val value: String) : Event

        class Audio(val value: ByteArray) : Event
    }

    suspend fun transcribe(file: File, config: DeviceConfig): String =
        exchange(file, config, false).transcript

    suspend fun exchange(
        file: File?,
        config: DeviceConfig,
        conversation: Boolean = true,
        status: (String) -> Unit = {},
        recognized: (String) -> Unit = {},
        route: (suspend (String) -> Boolean)? = null,
        onPcm: (suspend (PcmAudio) -> Unit)? = null,
        traceId: String = "",
        spokenText: (String) -> Unit = {},
        pcmFrames: ReceiveChannel<ByteArray>? = null,
    ): LinxReply {
        require(
            config.linxToken.isNotBlank() &&
                config.linxDevice.isNotBlank() &&
                config.clientId.isNotBlank()
        ) {
            "请先激活并绑定这台手机"
        }
        val url = config.linxUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
        val parsed = url.toHttpUrl()
        require(
            parsed.username.isEmpty() &&
                parsed.password.isEmpty() &&
                parsed.query == null &&
                parsed.fragment == null
        )
        require(
            config.linxUrl.startsWith("wss://") ||
                (allowLocal &&
                    config.linxUrl.startsWith("ws://") &&
                    parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
        ) {
            "灵矽语音必须使用安全连接"
        }
        val bytes = file?.readBytes() ?: byteArrayOf()
        if (pcmFrames == null) {
            require(
                bytes.size in 46..(1000 * 1024) &&
                    String(bytes, 0, 4) == "RIFF" &&
                    String(bytes, 8, 4) == "WAVE" &&
                    String(bytes, 36, 4) == "data"
            ) {
                "请重新录制语音"
            }
            val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            require(
                header.getShort(20).toInt() == 1 &&
                    header.getShort(22).toInt() == 1 &&
                    header.getInt(24) == 16000 &&
                    header.getShort(34).toInt() == 16 &&
                    header.getInt(40) == bytes.size - 44 &&
                    (bytes.size - 44) % 2 == 0
            ) {
                "录音格式应为16kHz单声道PCM"
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        fun stage(name: String) {
            android.util.Log.i("SonarVoice", "linx_stage=$name turn=$traceId at_ms=${SystemClock.elapsedRealtime()} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
        val events = Channel<Event>(4096)
        val queuedBytes = AtomicInteger()
        fun size(event: Event) = when (event) {
            is Event.Audio -> event.value.size
            is Event.Text -> event.value.length * 2
        }
        suspend fun receive(): Event {
            val event = events.receive()
            queuedBytes.addAndGet(-size(event))
            return event
        }
        val listener =
            object : WebSocketListener() {
                private fun emit(ws: WebSocket, event: Event) {
                    if (queuedBytes.addAndGet(size(event)) > 6 * 1024 * 1024 || events.trySend(event).isFailure) {
                        events.close(IOException("灵矽音频接收缓冲已满，请重试"))
                        ws.cancel()
                    }
                }

                override fun onOpen(ws: WebSocket, response: Response) {
                    // No device MCP advertised until initialize/tools/list/tools/call is
                    // implemented.
                    ws.send(
                        json(
                                "type" to "hello",
                                "version" to 1,
                                "features" to json("mcp" to false),
                                "transport" to "websocket",
                                "audio_params" to LinxAudioParams("opus", 16000, 20).hello(),
                            )
                            .toString()
                    )
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.length > 16384) {
                        events.close(IOException("灵矽控制消息过长"))
                        ws.cancel()
                    } else emit(ws, Event.Text(text))
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (bytes.size > 65536) {
                        events.close(IOException("灵矽音频帧异常"))
                        ws.cancel()
                    } else emit(ws, Event.Audio(bytes.toByteArray()))
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    events.close(
                        if (response?.code in setOf(401, 403)) LinxAuthFailure()
                        else IOException("灵矽语音连接失败，请检查网络后重试")
                    )
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    events.close(IOException("灵矽语音会话已结束"))
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(code, null)
                    events.close(IOException("灵矽语音会话已结束"))
                }
            }
        status("正在连接灵矽…")
        stage("connect")
        val socket =
            client.newWebSocket(
                Request.Builder()
                    .url(config.linxUrl)
                    .header("Authorization", "Bearer ${config.linxToken}")
                    .header("Device-Id", config.linxDevice)
                    .header("Client-Id", config.clientId)
                    .header("Protocol-Version", "1")
                    .build(),
                listener,
            )
        var session = ""
        var decoder: LinxPcmDecoder? = null
        fun send(j: JSONObject) {
            if (session.isNotBlank()) j.put("session_id", session)
            check(socket.send(j.toString())) { "灵矽语音连接已关闭" }
        }
        try {
            val hello =
                withTimeout(handshakeMs) {
                    val event = receive()
                    require(event is Event.Text) { "灵矽未返回语音握手" }
                    val value = JSONObject(event.value)
                    check(value.text("type") == "hello" && value.text("transport") == "websocket") {
                        "灵矽语音握手失败"
                    }
                    value
                }
            session = hello.text("session_id")
            stage("hello")
            val params = LinxAudioParams.parse(hello.optJSONObject("audio_params"))
            status("正在发送本次录音…")
            send(json("type" to "listen", "state" to "start", "mode" to "manual"))
            var sentUs = 0L
            val sendingAt = System.nanoTime()
            suspend fun sendFrame(frame: ByteArray) {
                check(socket.send(frame.toByteString())) { "灵矽录音上传中断" }
                sentUs += if (params.format == "opus") OpusPackets.durationUs(frame)
                    else frame.size * 1_000_000L / (params.rate * 2)
                // Pace by a single timeline: waiting for live capture already consumes time.
                val remainingMs = (sendingAt + sentUs * 1000 - System.nanoTime()) / 1_000_000
                if (remainingMs > 0) delay(remainingMs)
            }
            if (pcmFrames == null) {
                val packets = codec.encode(bytes.copyOfRange(44, bytes.size), params)
                require(packets.isNotEmpty()) { "录音中没有可发送的声音" }
                for (frame in packets) sendFrame(frame)
            } else {
                var pcmBytes = 0
                val encoder = if (params.format == "opus") withContext(Dispatchers.IO) { StreamingOpusEncoder(params) } else null
                val pcmPackets = if (params.format == "pcm") PcmPacketizer(params.rate, params.frameMs) else null
                try {
                    for (pcm in pcmFrames) {
                        require(pcm.size == 640)
                        pcmBytes += pcm.size
                        require(pcmBytes <= 960000)
                        val packets = withContext(Dispatchers.IO) {
                            encoder?.push(pcm) ?: pcmPackets!!.push(AndroidLinxCodec.resample(pcm, 16000, params.rate))
                        }
                        for (frame in packets) sendFrame(frame)
                    }
                    require(pcmBytes > 0)
                    if (encoder != null) for (frame in withContext(Dispatchers.IO) { encoder.finish() }) sendFrame(frame)
                    if (pcmPackets != null) for (frame in pcmPackets.finish()) sendFrame(frame)
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { encoder?.close() }
                }
            }
            send(json("type" to "listen", "state" to "stop", "mode" to "manual"))
            stage("upload_complete")
            status(if (conversation) "正在等待灵矽回复…" else "正在识别本次录音…")
            val candidates = linkedSetOf<String>()
            val spoken = mutableListOf<String>()
            val output = mutableListOf<ByteArray>()
            var outputSize = 0
            var started = false
            var routedAway = false
            var routed = false
            var firstAudio = true
            val complete =
                withTimeout(if (conversation) responseMs else minOf(responseMs, 12000)) {
                    while (true) {
                        val next: Event? =
                            if (candidates.isNotEmpty() && (!conversation || route != null && !routed))
                                withTimeoutOrNull(1200) { receive() }
                            else receive()
                        if (next == null) {
                            if (!conversation) return@withTimeout true
                            routed = true
                            stage("route_start")
                            if (!route!!.invoke(candidates.single())) {
                                routedAway = true
                                return@withTimeout true
                            }
                            stage("route_complete")
                            continue
                        }
                        when (val event = checkNotNull(next)) {
                            is Event.Audio -> {
                                if (!conversation) continue
                                check(started) { "灵矽返回了未标记来源的音频" }
                                outputSize += event.value.size
                                check(outputSize <= minOf(6 * 1024 * 1024, params.rate * 2 * 60)) {
                                    "灵矽语音回复过长"
                                }
                                if (firstAudio) { stage("first_audio"); firstAudio = false }
                                if (onPcm == null) output.add(event.value)
                                else {
                                    val active = decoder ?: withContext(Dispatchers.IO) { codec.openDecoder(params) }.also { decoder = it }
                                    val frames = withContext(Dispatchers.IO) { active.push(event.value) }
                                    for (frame in frames) onPcm(frame)
                                }
                            }
                            is Event.Text -> {
                                val j = JSONObject(event.value)
                                if (session.isNotBlank() && j.text("session_id").isNotBlank())
                                    check(j.text("session_id") == session) { "收到其他语音会话的消息，本次不使用" }
                                when (j.text("type")) {
                                    "stt" -> {
                                        val text = j.text("text").trim()
                                        if (text.isNotEmpty()) {
                                            require(text.length <= 1000) { "语音识别结果过长" }
                                            candidates.add(text)
                                            check(candidates.size == 1) { "收到多个识别片段，请重录后核对；本次不会发布" }
                                            recognized(text)
                                            stage("stt")
                                        }
                                    }
                                    "tts" ->
                                        when (j.text("state")) {
                                            "start" -> {
                                                stage("tts_start")
                                                started = true
                                                if (!conversation) {
                                                    send(
                                                        json(
                                                            "type" to "abort",
                                                            "reason" to "wake_word_detected",
                                                        )
                                                    )
                                                    // STT remains a candidate, not an invented
                                                    // final marker.
                                                    check(candidates.size == 1) { "尚未取得可核对的语音识别结果" }
                                                    return@withTimeout true
                                                }
                                                check(candidates.size == 1) { "尚未取得可核对的本轮识别结果" }
                                                if (!routed && route != null) {
                                                    routed = true
                                                    stage("route_start")
                                                    if (!route(candidates.single())) {
                                                        routedAway = true
                                                        return@withTimeout true
                                                    }
                                                    stage("route_complete")
                                                }
                                            }
                                            "sentence_start" -> {
                                                val text = j.text("text").take(4000)
                                                if (text.isNotBlank()) {
                                                    spoken.add(text)
                                                    require(spoken.sumOf { it.length } <= 8000)
                                                    spokenText(spoken.joinToString(""))
                                                }
                                            }
                                            "stop" -> {
                                                stage("tts_stop")
                                                check(!j.optBoolean("is_aborted")) {
                                                    "灵矽回复已被打断，本次不播放"
                                                }
                                                check(started && candidates.size == 1) {
                                                    "灵矽没有返回完整的本轮语音"
                                                }
                                                decoder?.let { active ->
                                                    for (frame in withContext(Dispatchers.IO) { active.finish() }) onPcm?.invoke(frame)
                                                }
                                                return@withTimeout true
                                            }
                                        }
                                    "error" -> error("灵矽语音服务返回错误，请稍后重试")
                                    "notify" ->
                                        if (j.text("event") == "config_updated")
                                            error("灵矽配置已更新，请重新开始本次语音")
                                }
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
            check(complete && candidates.size == 1)
            if (params.format == "opus")
                require(output.sumOf { OpusPackets.durationUs(it) } <= 60000000) { "灵矽语音回复过长" }
            val audio =
                if (conversation && output.isNotEmpty()) codec.decode(output, params) else null
            check(!conversation || routedAway || audio != null || onPcm != null && !firstAudio) { "灵矽未返回可播放的语音" }
            return LinxReply(candidates.single(), spoken.joinToString(""), audio, params)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { decoder?.close() }
            runCatching { send(json("type" to "abort", "reason" to "wake_word_detected")) }
            events.cancel()
            socket.close(1000, "clip complete")
            socket.cancel()
        }
    }
}
