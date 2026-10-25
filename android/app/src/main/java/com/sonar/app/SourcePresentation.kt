package com.sonar.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal fun sourceTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value.ifBlank { "时间未提供" })

/** Observation, recording and publication times must remain distinct even in compact UI. */
internal fun sourceSummary(source: JSONObject): String {
    if (source.optBoolean("synthetic") || source.text("content_origin") == "agent_synthesized") {
        return "合成测试音频 · ${source.text("place_name")}\n非实时公告、路况或真人现场发言"
    }
    val operator = source.text("source_type") == "poi_operator" || source.text("channel_id").isNotBlank() || source.text("kind") == "announcement"
    val label = source.text("channel_label").ifBlank { source.text("author_label") }.ifBlank {
        if (source.text("provider") == "amap") "高德信息" else "来源未提供"
    }
    if (source.text("kind") == "chat") return "真人原声 · $label\n发送 ${sourceTime(source.text("published_at"))} · 仅参与者对话"
    val type = if (source.text("provider") == "amap") "工具信息" else if (operator) "运营来源 · 真人原声" else "真人原声"
    val (timeLabel, at) = when {
        source.text("observed_at").isNotBlank() -> "观察" to source.text("observed_at")
        source.text("recorded_at").isNotBlank() -> "录制（观察时间未提供）" to source.text("recorded_at")
        source.text("published_at").isNotBlank() -> "发布（观察时间未提供）" to source.text("published_at")
        source.text("reporttime").isNotBlank() -> "报告" to source.text("reporttime")
        source.text("server_time").isNotBlank() -> "查询" to source.text("server_time")
        else -> "" to "时间未提供"
    }
    val scope = source.text("scope_label").ifBlank {
        when {
            source.has("adcode") || source.has("reporttime") -> "区县天气，不能确认地点此刻实况"
            source.has("distance_m") -> "路线规划，未知路段不代表畅通"
            source.text("scope") == "poi" -> "地点范围"
            source.text("scope") in setOf("floor", "entrance", "area") -> "指定范围，名称未提供"
            else -> "适用范围未提供"
        }
    }
    return "$type · $label\n$timeLabel ${sourceTime(at)} · $scope"
}

internal fun visibilityLabel(value: String) = when (value) {
    "public" -> "公开问答与分享"
    "participants" -> "仅交流参与者可听"
    "private" -> "仅本人可见"
    else -> ""
}
