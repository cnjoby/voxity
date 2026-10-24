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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

const val SONAR_BASE_URL = "https://122.195.131.106"
private const val SERVER_ADDRESS_MIGRATED = "server_address_migrated_v1"

data class DeviceConfig(
    val baseUrl: String = SONAR_BASE_URL,
    val token: String = "",
    val mock: Boolean = false,
    val debugTranscript: Boolean = false,
    val linxUrl: String = "wss://xrobo-io.qiniuapi.com/v1/ws/",
    val linxToken: String = "",
    val linxDevice: String = "",
    val clientId: String = "",
    val simulatedLocation: Boolean = false,
    val linxOtaUrl: String = LINX_OTA,
    val testPlaceId: String = "",
) {
    /** Only the old local deployment address is eligible for the one-time upgrade. */
    internal fun migrateLocalServerAddress(): DeviceConfig {
        if (mock) return this
        val url = baseUrl.toHttpUrlOrNull() ?: return this
        return if (
            url.scheme == "http" &&
                url.host in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
                url.port == 8765 &&
                url.encodedPath == "/" &&
                url.query == null &&
                url.fragment == null &&
                url.username.isEmpty() &&
                url.password.isEmpty()
        ) copy(baseUrl = SONAR_BASE_URL) else this
    }

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
            "simulated_location" to simulatedLocation,
            "linx_ota_url" to linxOtaUrl,
            "test_place_id" to testPlaceId,
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
                j.text("base_url", SONAR_BASE_URL),
                j.text("token"),
                j.optBoolean("mock"),
                j.optBoolean("debug_transcript"),
                j.text("linx_url", "wss://xrobo-io.qiniuapi.com/v1/ws/"),
                j.text("linx_token"),
                j.text("linx_device"),
                j.text("client_id"),
                j.optBoolean("simulated_location"),
                j.text("linx_ota_url", LINX_OTA),
                j.text("test_place_id"),
            )
    }
}

/** Credentials never enter BuildConfig/resources/APK; private storage encrypted with Keystore. */
class ConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val identity = context.getSharedPreferences("linx-identity", Context.MODE_PRIVATE)

    @Synchronized
    fun normalize(config: DeviceConfig): DeviceConfig {
        val client =
            identity.getString("client", null)
                ?: config.clientId.ifBlank { UUID.randomUUID().toString() }
        val device =
            identity.getString("device", null)
                ?: config.linxDevice.takeIf { it.isNotBlank() }
                ?: run {
                    val androidId =
                        android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID,
                            )
                            .orEmpty()
                    val hash =
                        MessageDigest.getInstance("SHA-256")
                            .digest("sonar-android|$androidId|$client".toByteArray())
                    hash[0] = ((hash[0].toInt() and 0xfc) or 0x02).toByte()
                    hash.take(6).joinToString(":") { "%02X".format(it.toInt() and 255) }
                }
        check(identity.edit().putString("client", client).putString("device", device).commit())
        return config.copy(
            linxDevice = config.linxDevice.ifBlank { device },
            clientId = config.clientId.ifBlank { client },
            linxOtaUrl = if (BuildConfig.DEBUG) config.linxOtaUrl else LINX_OTA,
        )
    }

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
        val data = cipher.doFinal(normalize(config).toJson().toString().toByteArray())
        check(
            prefs
                .edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("sealed", Base64.encodeToString(data, Base64.NO_WRAP))
                .putBoolean(SERVER_ADDRESS_MIGRATED, true)
                .commit()
        )
    }

    fun load(): DeviceConfig {
        if (BuildConfig.DEBUG) {
            val bootstrap = File(context.filesDir, "bootstrap.json")
            if (bootstrap.exists()) {
                val config = normalize(DeviceConfig.parse(JSONObject(bootstrap.readText())))
                save(config)
                check(bootstrap.delete())
                return config
            }
        }
        val sealed = prefs.getString("sealed", null) ?: return normalize(DeviceConfig())
        val config = runCatching {
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
                normalize(
                    DeviceConfig.parse(
                        JSONObject(String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP))))
                    )
                )
            }
            .getOrElse { throw IllegalStateException("无法解锁本机配置，请清除应用数据后重新配置") }
        if (!prefs.getBoolean(SERVER_ADDRESS_MIGRATED, false)) {
            val migrated = config.migrateLocalServerAddress()
            // Persist the encrypted config and migration marker together. Explicit later edits,
            // including local debug bootstrap configurations, keep their selected address.
            save(migrated)
            return migrated
        }
        return config
    }
}

class TaskStore(context: Context, scope: String) {
    private val prefs = context.getSharedPreferences("tasks-$scope", Context.MODE_PRIVATE)
    var automaticAudioSuspended: Boolean
        get() = prefs.getBoolean("natural-exchange-selected", false)
        set(value) { check(prefs.edit().putBoolean("natural-exchange-selected", value).commit()) }

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
            (!key.contains(":visit:") &&
                prefs.contains("attempt-delivery:${key.substringBefore(":")}"))

    fun markAttempt(key: String) {
        check(
            prefs
                .edit()
                .putBoolean("attempt:$key", true)
                .apply {
                    if (!key.contains(":visit:"))
                        putBoolean("attempt-delivery:${key.substringBefore(":")}", true)
                }
                .commit()
        )
    }

    fun excludedDeliveryIds(): List<String> = prefs.all.entries
        .filter { (key, value) -> value == true && (key.startsWith("defer:") || key.startsWith("attempt:")) }
        .sortedBy { if (it.key.startsWith("defer:")) 0 else 1 }
        .map { it.key.substringAfter(":").substringBefore(":") }.distinct().take(256)

    fun deferred(key: String) = prefs.getBoolean("defer:$key", false)

    fun defer(key: String, value: Boolean) {
        check(prefs.edit().putBoolean("defer:$key", value).commit())
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
                            "visit_id" to d.payload.text("visit_id"),
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
