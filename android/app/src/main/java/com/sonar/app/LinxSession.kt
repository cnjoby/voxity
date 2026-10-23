package com.sonar.app

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** One new socket per clip: session_id is not treated as a business recording ID. */
class LinxSession(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun transcribe(file: File, config: DeviceConfig): String {
        require(config.linxToken.isNotBlank() && config.linxDevice.isNotBlank()) {
            "尚未配置 Linx 设备，调试转写仅供接口联调"
        }
        require(config.linxUrl.startsWith("wss://")) { "Linx 必须使用 WSS" }
        val ready = CompletableDeferred<String>()
        val text = CompletableDeferred<String>()
        val candidates = mutableSetOf<String>()
        val audio = file.readBytes().drop(44).toByteArray()
        val listener =
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(
                        json(
                                "type" to "hello",
                                "version" to 1,
                                "features" to json("mcp" to true),
                                "transport" to "websocket",
                                "audio_params" to
                                    json(
                                        "format" to "pcm",
                                        "sample_rate" to 16000,
                                        "channels" to 1,
                                        "bit_depth" to 16,
                                        "endianness" to "little",
                                        "frame_duration" to 20,
                                        "frame_size" to 320,
                                        "sample_format" to "signed_int16",
                                    ),
                            )
                            .toString()
                    )
                }

                override fun onMessage(ws: WebSocket, message: String) {
                    runCatching {
                            val j = JSONObject(message)
                            when (j.text("type")) {
                                "hello" -> {
                                    val p = j.optJSONObject("audio_params")
                                    if (
                                        p?.text("format") != "pcm" ||
                                            p.optInt("sample_rate") != 16000 ||
                                            p.optInt("channels") != 1
                                    )
                                        throw IllegalStateException(
                                            "Linx 协商格式尚不支持，不能将 Opus 当作 PCM 发送"
                                        )
                                    ready.complete(j.text("session_id"))
                                }
                                "stt" ->
                                    j.text("text")
                                        .takeIf { it.isNotBlank() }
                                        ?.let {
                                            synchronized(candidates) { candidates.add(it) }
                                            text.complete(it)
                                        }
                                "tts" ->
                                    if (j.text("state") == "start")
                                        ws.send(
                                            json(
                                                    "type" to "abort",
                                                    "session_id" to j.text("session_id"),
                                                    "reason" to "wake_word_detected",
                                                )
                                                .toString()
                                        )
                            }
                        }
                        .onFailure {
                            ready.completeExceptionally(it)
                            text.completeExceptionally(it)
                        }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val error = IllegalStateException("Linx 连接失败，请检查设备绑定与网络")
                    ready.completeExceptionally(error)
                    text.completeExceptionally(error)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    val error = IllegalStateException("Linx 会话已关闭，未确认的声音不会公开")
                    ready.completeExceptionally(error)
                    text.completeExceptionally(error)
                }
            }
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
        try {
            val session = withTimeout(10000) { ready.await() }
            check(
                socket.send(
                    json(
                            "type" to "listen",
                            "state" to "start",
                            "mode" to "manual",
                            "session_id" to session,
                        )
                        .toString()
                )
            )
            for (offset in audio.indices step 640) {
                check(
                    socket.send(
                        audio.copyOfRange(offset, minOf(offset + 640, audio.size)).toByteString()
                    )
                )
                delay(20)
            }
            check(
                socket.send(
                    json(
                            "type" to "listen",
                            "state" to "stop",
                            "mode" to "manual",
                            "session_id" to session,
                        )
                        .toString()
                )
            )
            val result = withTimeout(8000) { text.await() }
            delay(
                500
            ) // Local settling window, NOT a claim that the protocol provides a final marker.
            check(synchronized(candidates) { candidates.size == 1 }) { "收到多个识别片段，请核对后重录；本次不发布" }
            return result
        } finally {
            socket.close(1000, "clip complete")
            socket.cancel()
        }
    }
}
