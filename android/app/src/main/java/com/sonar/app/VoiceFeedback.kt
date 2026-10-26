package com.sonar.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject

/** Acceptance by the server is distinct from a peer's receipt. */
internal fun submissionReceipt(result: JSONObject): String {
    require(result.text("status") == "published")
    if (result.text("action") != "question") return "原声已提交到服务器。"
    val target = result.optJSONObject("target_place")?.text("label").orEmpty().ifBlank { "目标地点" }
    val scope = result.optJSONObject("match_scope")
    val area = if (scope?.text("kind") == "park") "，按${scope.text("label")}园区范围询问" else ""
    val received = result.optJSONObject("dispatch")?.optInt("device_received", 0) ?: 0
    return "正在将问题转发给${target}在场的人${area}。" +
        if (received > 0) "已有现场设备确认收到邀请，等待回应。" else "尚未有现场设备确认接收，正在等待回应。"
}

/** A playback copy only: uploaded/stored original bytes and provenance stay unchanged. */
internal fun originalPlaybackLevel(wav: ByteArray): ByteArray {
    if (wav.size < 46 || String(wav, 0, 4) != "RIFF" || String(wav, 36, 4) != "data") return wav
    val input = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (input.getShort(20).toInt() != 1 || input.getShort(22).toInt() != 1 || input.getShort(34).toInt() != 16 || input.getInt(40) != wav.size - 44) return wav
    var power = 0.0
    var peak = 0
    for (i in 44 until wav.size - 1 step 2) {
        val sample = input.getShort(i).toInt()
        power += sample.toDouble() * sample
        peak = maxOf(peak, abs(sample))
    }
    val rms = sqrt(power / ((wav.size - 44) / 2))
    if (rms < 40 || rms >= 2800 || peak == 0) return wav
    val gain = minOf(6.0, 2800 / rms, 28000.0 / peak)
    if (gain <= 1.05) return wav
    val result = wav.copyOf()
    val output = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 44 until wav.size - 1 step 2) output.putShort(i, (input.getShort(i) * gain).toInt().coerceIn(-28000, 28000).toShort())
    return result
}
