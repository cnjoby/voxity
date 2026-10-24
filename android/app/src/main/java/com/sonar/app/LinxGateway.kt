package com.sonar.app

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

const val LINX_OTA = "https://xrobo.qiniuapi.com/v1/ota/"

/** Device OTA only: no Sonar token, no account management key, no firmware installation. */
class LinxGateway(
    endpoint: String = LINX_OTA,
    private val allowLocal: Boolean = false,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build(),
) {
    private val base =
        endpoint.toHttpUrl().also {
            require(
                it.username.isEmpty() &&
                    it.password.isEmpty() &&
                    it.query == null &&
                    it.fragment == null
            )
            require(
                (it.scheme == "https" &&
                    it.host == "xrobo.qiniuapi.com" &&
                    it.encodedPath == "/v1/ota/") ||
                    (allowLocal &&
                        it.scheme == "http" &&
                        it.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
            ) {
                "请使用官方灵矽激活接口"
            }
        }

    suspend fun report(config: DeviceConfig, apkHash: String): LinxRegistration =
        withContext(Dispatchers.IO) {
            require(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(config.linxDevice)) {
                "设备标识应为 MAC 格式"
            }
            UUID.fromString(config.clientId)
            val body =
                json(
                    "version" to 0,
                    "uuid" to config.clientId,
                    "mac_address" to config.linxDevice,
                    "application" to
                        json(
                            "name" to "sonar-android",
                            "version" to BuildConfig.VERSION_NAME,
                            "elf_sha256" to apkHash,
                        ),
                    "board" to json("type" to "sonar-android", "ssid" to "", "rssi" to 0),
                    "chip_model_name" to "android",
                )
            val request =
                Request.Builder()
                    .url(base)
                    .header("Device-Id", config.linxDevice)
                    .header("Client-Id", config.clientId)
                    .header("Activation-Version", "1")
                    .header("User-Agent", "sonar-android/${BuildConfig.VERSION_NAME}")
                    .header("Accept-Language", "zh-CN")
                    .post(
                        body
                            .toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("灵矽激活请求失败（${response.code}），请稍后重试")
                val bytes =
                    response.body?.byteStream()?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (out.size() <= 65536) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("灵矽激活响应为空")
                check(bytes.size <= 65536) { "灵矽激活响应异常" }
                val value =
                    runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
                        .getOrElse { throw IOException("无法读取灵矽激活结果") }
                check(
                    value.isNull("error") &&
                        (!value.has("code") || value.optInt("code") in setOf(0, 200))
                ) {
                    "灵矽未接受设备注册，请检查设备配置"
                }
                val ws = value.optJSONObject("websocket")
                val token = ws?.text("token").orEmpty()
                if (token.isNotBlank()) {
                    LinxRegistration.Bound(secureSocket(ws!!.getString("url")), token)
                } else {
                    val activation = value.optJSONObject("activation") ?: error("设备尚未绑定，平台未返回激活码")
                    val code = activation.text("code")
                    check(Regex("[0-9]{6}").matches(code)) { "灵矽返回的激活码无效" }
                    val console =
                        activation.text("message").lineSequence().map(String::trim).firstOrNull {
                            it.startsWith("https://") || it.startsWith("http://")
                        }
                    val url =
                        console
                            ?.let { runCatching { it.toHttpUrl() }.getOrNull() }
                            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
                    LinxRegistration.Pending(code, url?.toString())
                }
            }
        }

    /** A 200 response can still be a JSON error; only exact success means bound. */
    suspend fun activated(config: DeviceConfig): Boolean =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(base.resolve("activate")!!)
                    .header("Device-Id", config.linxDevice)
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 202 -> false
                    response.code == 200 && response.body?.string()?.trim() == "success" -> true
                    else -> throw IOException("尚未确认灵矽绑定状态，请重试")
                }
            }
        }

    private fun secureSocket(url: String): String {
        // Official OTA examples return ws://. Upgrade the known endpoint before sending
        // credentials.
        val secure =
            if (url.startsWith("ws://xrobo-io.qiniuapi.com/")) "wss://" + url.removePrefix("ws://")
            else url
        val http =
            secure.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrl()
        require(
            http.username.isEmpty() &&
                http.password.isEmpty() &&
                http.query == null &&
                http.fragment == null
        )
        require(
            (secure.startsWith("wss://") &&
                http.host == "xrobo-io.qiniuapi.com" &&
                http.port == 443) ||
                (allowLocal &&
                    secure.startsWith("ws://") &&
                    http.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
        ) {
            "平台返回的语音地址暂不支持安全连接"
        }
        return secure
    }
}

sealed interface LinxRegistration {
    data class Pending(val code: String, val consoleUrl: String?) : LinxRegistration

    // Credentials deliberately have no data-class toString representation.
    class Bound(val url: String, val token: String) : LinxRegistration
}
