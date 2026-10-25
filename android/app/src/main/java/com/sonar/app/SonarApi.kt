package com.sonar.app

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiFailure(
    val status: Int,
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
) : IOException(message)

interface SonarApi {
    suspend fun me(): JSONObject

    suspend fun context(data: JSONObject): JSONObject

    suspend fun location(revision: Int, fix: JSONObject?): JSONObject = error("此模式不支持定位")

    suspend fun remotePlace(revision: Int, placeId: String): JSONObject = error("此服务尚不支持远程地点")

    suspend fun places(query: String): JSONObject = error("此模式不支持地点搜索")

    suspend fun listening(data: JSONObject): JSONObject = error("此模式不支持收听设置")

    suspend fun voices(placeId: String): JSONObject = json("items" to org.json.JSONArray())

    suspend fun queueVoice(postId: String): JSONObject = error("此模式不支持地点声音")

    suspend fun withdrawVoice(postId: String): JSONObject = error("此模式不支持撤回")

    suspend fun conversation(qid: String): JSONObject = json("items" to org.json.JSONArray())

    suspend fun startExchange(data: JSONObject): JSONObject = error("此服务不支持自然交流")
    suspend fun agentTurn(id: String, metadata: JSONObject): JSONObject = error("此服务不支持统一语音入口")
    suspend fun message(postId: String): JSONObject = error("此服务不支持来源核对")
    suspend fun personalTasks(): JSONObject = json("items" to org.json.JSONArray())
    suspend fun personalTask(taskId: String): JSONObject = error("此服务不支持任务管理")
    suspend fun requestResult(requestId: String): JSONObject = error("此服务不支持结果核对")
    suspend fun taskAction(taskId: String, revision: Int, requestId: String, action: String): JSONObject = error("此服务不支持任务管理")
    suspend fun validateInformation(messageIds: List<String>, purpose: String = "all"): Boolean = false
    suspend fun exchangeSession(id: String): JSONObject = error("此服务不支持自然交流")
    suspend fun changeExchange(id: String, revision: Int, state: String): JSONObject = error("此服务不支持自然交流")
    suspend fun exchangeContext(id: String, revision: Int, postId: String): JSONObject = error("此服务不支持自然交流")
    suspend fun exchangeInbox(id: String): JSONObject = error("此服务不支持自然交流")
    suspend fun exchangeInbox(id: String, excluded: List<String>): JSONObject = exchangeInbox(id)
    suspend fun exchangeUpload(id: String, file: File, metadata: JSONObject): JSONObject = error("此服务不支持自然交流")

    suspend fun upload(draft: LocalDraft): JSONObject

    suspend fun confirm(postId: String): JSONObject

    suspend fun question(id: String): JSONObject

    suspend fun cancel(id: String): JSONObject

    suspend fun inbox(): JSONObject

    suspend fun delivery(id: String): JSONObject

    suspend fun ack(id: String, requestId: String, event: String): JSONObject

    suspend fun download(path: String): ByteArray
}

class HttpSonarApi(
    url: String,
    private val token: String,
    private val clock: ServerClock,
    allowLocalHttp: Boolean = false,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build(),
) : SonarApi {
    private val base =
        url.trimEnd('/').plus('/').toHttpUrl().also {
            require(
                it.username.isEmpty() &&
                    it.password.isEmpty() &&
                    it.query == null &&
                    it.fragment == null &&
                    it.encodedPath == "/"
            ) {
                "服务地址只填写协议、主机和端口"
            }
            require(
                it.scheme == "https" ||
                    (allowLocalHttp && it.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
            ) {
                "请使用 HTTPS，调试仅允许本机地址"
            }
        }

    private fun request(path: String): Request.Builder {
        val resolved = base.resolve(path) ?: error("无效的音频路径")
        require(
            resolved.scheme == base.scheme &&
                resolved.host == base.host &&
                resolved.port == base.port &&
                resolved.username.isEmpty() &&
                resolved.password.isEmpty()
        ) {
            "拒绝跨站发送设备凭证"
        }
        return Request.Builder().url(resolved).header("Authorization", "Bearer $token")
    }

    private suspend fun call(
        path: String,
        data: JSONObject? = null,
        method: String = if (data == null) "GET" else "POST",
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = request(path)
            if (data != null)
                builder.method(
                    method,
                    data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
            executeJson(builder.build())
        }

    private val exchangeClient = client.newBuilder().callTimeout(65, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    private suspend fun executeJson(request: Request): JSONObject =
        withResponse(request, if ((request.url.encodedPath.startsWith("/v1/exchange/") &&
            (request.url.encodedPath.endsWith("/utterances") || request.url.encodedPath.endsWith("/inbox"))) ||
            (request.url.encodedPath.startsWith("/v1/agent/") && request.url.encodedPath.endsWith("/turns")))
            exchangeClient else client) { response ->
            val body = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) {
                val e = parsed?.optJSONObject("error")
                throw ApiFailure(
                    response.code,
                    e?.text("code") ?: "HTTP_ERROR",
                    e?.text("message")?.ifBlank { null } ?: "服务暂不可用（${response.code}）",
                    e?.optBoolean("retryable") ?: false,
                )
            }
            val result = parsed ?: throw IOException("服务返回了无法读取的数据")
            result.text("server_time").takeIf { it.isNotBlank() }?.let(clock::sync)
            result
        }

    private suspend fun <T> withResponse(request: Request, http: OkHttpClient = client, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val value = read(it)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }

    override suspend fun me() = call("/v1/me")

    override suspend fun context(data: JSONObject) = call("/v1/me/context", data, "PUT")

    override suspend fun location(revision: Int, fix: JSONObject?) =
        call("/v1/me/location", json("expected_revision" to revision, "location" to fix), "PUT")

    override suspend fun remotePlace(revision: Int, placeId: String) =
        call("/v1/me/remote-place", json("expected_revision" to revision, "place_id" to placeId), "PUT")

    override suspend fun places(query: String) =
        call("/v1/places?query=" + java.net.URLEncoder.encode(query, "UTF-8"))

    override suspend fun listening(data: JSONObject) = call("/v1/me/listening", data, "PUT")

    override suspend fun voices(placeId: String) =
        call("/v1/voices?place_id=" + java.net.URLEncoder.encode(placeId, "UTF-8"))

    override suspend fun queueVoice(postId: String) =
        call(
            "/v1/voices/$postId/queue",
            json("client_request_id" to java.util.UUID.randomUUID().toString()),
        )

    override suspend fun withdrawVoice(postId: String) =
        call("/v1/voices/$postId/withdraw", JSONObject())

    override suspend fun conversation(qid: String) = call("/v1/conversations/$qid")

    override suspend fun startExchange(data: JSONObject) = call("/v1/exchange/sessions", data)
    override suspend fun agentTurn(id: String, metadata: JSONObject) = call("/v1/agent/sessions/$id/turns", metadata)
    override suspend fun message(postId: String) = call("/v1/messages/${postId}")
    override suspend fun personalTasks() = call("/v1/tasks")
    override suspend fun personalTask(taskId: String) = call("/v1/tasks/$taskId")
    override suspend fun requestResult(requestId: String) = call("/v1/requests/$requestId")
    override suspend fun taskAction(taskId: String, revision: Int, requestId: String, action: String) =
        call("/v1/tasks/$taskId/actions", json("expected_revision" to revision, "client_request_id" to requestId, "action" to action))
    override suspend fun validateInformation(messageIds: List<String>, purpose: String) =
        call("/v1/information/validate", json("message_ids" to org.json.JSONArray(messageIds), "purpose" to purpose)).getBoolean("valid")
    override suspend fun exchangeSession(id: String) = call("/v1/exchange/sessions/$id")
    override suspend fun changeExchange(id: String, revision: Int, state: String) =
        call("/v1/exchange/sessions/$id", json("expected_session_revision" to revision, "state" to state), "PUT")
    override suspend fun exchangeContext(id: String, revision: Int, postId: String) =
        call("/v1/exchange/sessions/$id/context", json("expected_session_revision" to revision, "post_id" to postId), "PUT")
    override suspend fun exchangeInbox(id: String) = exchangeInbox(id, emptyList())
    override suspend fun exchangeInbox(id: String, excluded: List<String>) =
        call("/v1/exchange/sessions/$id/inbox" + excluded.take(256).joinToString("&", prefix = if (excluded.isEmpty()) "" else "?") {
            "exclude=" + java.net.URLEncoder.encode(it, "UTF-8")
        })
    override suspend fun exchangeUpload(id: String, file: File, metadata: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            require(file.length() in 46..(1000 * 1024)) { "录音大小无效" }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("metadata", metadata.toString())
                .addFormDataPart("audio", "voice.wav", file.readBytes().toRequestBody("audio/wav".toMediaType()))
                .build()
            executeJson(request("/v1/exchange/sessions/$id/utterances").post(body).build())
        }

    override suspend fun upload(draft: LocalDraft): JSONObject =
        withContext(Dispatchers.IO) {
            val audio = File(draft.file).readBytes()
            require(audio.size <= 1000 * 1024) { "录音过长，请重新录制" }
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("metadata", draft.metadata().toString())
                    .addFormDataPart(
                        "audio",
                        "voice.wav",
                        audio.toRequestBody("audio/wav".toMediaType()),
                    )
                    .build()
            executeJson(request("/v1/posts/drafts").post(body).build())
        }

    override suspend fun confirm(postId: String) = call("/v1/posts/$postId/confirm", JSONObject())

    override suspend fun question(id: String) = call("/v1/questions/$id")

    override suspend fun cancel(id: String) = call("/v1/questions/$id/cancel", JSONObject())

    override suspend fun inbox() = call("/v1/inbox")

    override suspend fun delivery(id: String) = call("/v1/inbox/$id")

    override suspend fun ack(id: String, requestId: String, event: String) =
        call("/v1/inbox/$id/ack", json("request_id" to requestId, "event" to event))

    override suspend fun download(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            withResponse(request(path).build()) { response ->
                if (!response.isSuccessful)
                    throw ApiFailure(response.code, "AUDIO_ERROR", "原声下载失败（${response.code}）")
                val body = response.body ?: throw IOException("原声为空")
                val bytes =
                    java.io.ByteArrayOutputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(4096)
                            while (output.size() <= 1000 * 1024) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                        output.toByteArray()
                    }
                require(bytes.size <= 1000 * 1024) { "音频超过大小限制" }
                require(
                    bytes.size > 44 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())
                ) {
                    "原声格式无效"
                }
                bytes
            }
        }
}
