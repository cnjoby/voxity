package com.sonar.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class DeviceConfig(
    val baseUrl: String = "http://127.0.0.1:8765",
    val token: String = "",
    val mock: Boolean = false,
    val debugTranscript: Boolean = false,
    val linxUrl: String = "wss://xrobo-io.qiniuapi.com/v1/ws/",
    val linxToken: String = "",
    val linxDevice: String = "",
    val clientId: String = UUID.randomUUID().toString(),
) {
    fun toJson() =
        json(
            "base_url" to baseUrl,
            "token" to token,
            "mock" to mock,
            "debug_transcript" to debugTranscript,
            "linx_url" to linxUrl,
            "linx_token" to linxToken,
            "linx_device" to linxDevice,
            "client_id" to clientId,
        )

    val scope
        get() =
            MessageDigest.getInstance("SHA-256")
                .digest("$mock|$baseUrl|$token".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(24)

    companion object {
        fun parse(j: JSONObject) =
            DeviceConfig(
                j.text("base_url", "http://127.0.0.1:8765"),
                j.text("token"),
                j.optBoolean("mock"),
                j.optBoolean("debug_transcript"),
                j.text("linx_url", "wss://xrobo-io.qiniuapi.com/v1/ws/"),
                j.text("linx_token"),
                j.text("linx_device"),
                j.text("client_id").ifBlank { UUID.randomUUID().toString() },
            )
    }
}

/** Credentials never enter BuildConfig/resources/APK; private storage encrypted with Keystore. */
class ConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("sonar-config-v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "sonar-config-v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun save(config: DeviceConfig) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(config.toJson().toString().toByteArray())
        check(
            prefs
                .edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("sealed", Base64.encodeToString(data, Base64.NO_WRAP))
                .commit()
        )
    }

    fun load(): DeviceConfig {
        if (BuildConfig.DEBUG) {
            val bootstrap = File(context.filesDir, "bootstrap.json")
            if (bootstrap.exists()) {
                val config = DeviceConfig.parse(JSONObject(bootstrap.readText()))
                save(config)
                check(bootstrap.delete())
                return config
            }
        }
        val sealed = prefs.getString("sealed", null) ?: return DeviceConfig()
        return runCatching {
                val cipher =
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            key(),
                            GCMParameterSpec(
                                128,
                                Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP),
                            ),
                        )
                    }
                DeviceConfig.parse(
                    JSONObject(String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP))))
                )
            }
            .getOrElse { throw IllegalStateException("无法解锁本机配置，请清除应用数据后重新配置") }
    }
}

class TaskStore(context: Context, scope: String) {
    private val prefs = context.getSharedPreferences("tasks-$scope", Context.MODE_PRIVATE)
    var draft: LocalDraft?
        get() =
            prefs.getString("draft", null)?.let {
                runCatching { LocalDraft.parse(JSONObject(it)) }.getOrNull()
            }
        set(v) {
            check(prefs.edit().putString("draft", v?.toJson()?.toString()).commit())
        }

    var pendingMode: String?
        get() = prefs.getString("pending_mode", null)
        set(v) {
            check(prefs.edit().putString("pending_mode", v).commit())
        }

    var questionId: String?
        get() = prefs.getString("question", null)
        set(v) {
            check(prefs.edit().putString("question", v).commit())
        }

    fun outcome(qid: String) = prefs.getString("outcome:$qid", null)

    fun outcome(qid: String, value: String) {
        check(prefs.edit().putString("outcome:$qid", value).commit())
    }

    fun attempted(key: String) =
        prefs.contains("attempt:$key") ||
            prefs.contains("attempt-delivery:${key.substringBefore(":")}")

    fun markAttempt(key: String) {
        check(
            prefs
                .edit()
                .putBoolean("attempt:$key", true)
                .putBoolean("attempt-delivery:${key.substringBefore(":")}", true)
                .commit()
        )
    }

    fun queueAck(d: Delivery, event: String) {
        check(
            prefs
                .edit()
                .putString(
                    "ack:${d.key}",
                    json(
                            "id" to d.id,
                            "request_id" to d.requestId,
                            "event" to event,
                            "post_id" to d.payload.text("post_id"),
                        )
                        .toString(),
                )
                .commit()
        )
    }

    fun acks() =
        prefs.all
            .filterKeys { it.startsWith("ack:") }
            .map { it.key to JSONObject(it.value as String) }

    fun clearAck(key: String) {
        check(prefs.edit().remove(key).commit())
    }
}
