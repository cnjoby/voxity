package com.sonar.app

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun places(query: String): JSONObject = error("此模式不支持地点搜索")

    suspend fun listening(data: JSONObject): JSONObject = error("此模式不支持收听设置")

    suspend fun voices(placeId: String): JSONObject = json("items" to org.json.JSONArray())

    suspend fun queueVoice(postId: String): JSONObject = error("此模式不支持地点声音")

    suspend fun withdrawVoice(postId: String): JSONObject = error("此模式不支持撤回")

    suspend fun conversation(qid: String): JSONObject = json("items" to org.json.JSONArray())

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

    private fun executeJson(request: Request): JSONObject =
        client.newCall(request).execute().use { response ->
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

    override suspend fun me() = call("/v1/me")

    override suspend fun context(data: JSONObject) = call("/v1/me/context", data, "PUT")

    override suspend fun location(revision: Int, fix: JSONObject?) =
        call("/v1/me/location", json("expected_revision" to revision, "location" to fix), "PUT")

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

    override suspend fun upload(draft: LocalDraft): JSONObject =
        withContext(Dispatchers.IO) {
            val audio = File(draft.file).readBytes()
            require(audio.size <= 300 * 1024) { "录音过长，请重新录制" }
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
            client.newCall(request(path).build()).execute().use { response ->
                if (!response.isSuccessful)
                    throw ApiFailure(response.code, "AUDIO_ERROR", "原声下载失败（${response.code}）")
                val body = response.body ?: throw IOException("原声为空")
                val bytes =
                    java.io.ByteArrayOutputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(4096)
                            while (output.size() <= 300 * 1024) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                        output.toByteArray()
                    }
                require(bytes.size <= 300 * 1024) { "音频超过大小限制" }
                require(
                    bytes.size > 44 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())
                ) {
                    "原声格式无效"
                }
                bytes
            }
        }
}
