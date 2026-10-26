package com.sonar.app

import java.time.Instant
import java.time.Duration
import org.json.JSONObject

/** Announce publication time separately; never alter or replace the source recording. */
fun publicationIntro(payload: JSONObject, now: Instant): String {
    if (payload.text("kind") == "chat") return ""
    val published = runCatching {
        Instant.parse(payload.text("published_at"))
    }.getOrNull() ?: return ""
    val seconds = Duration.between(published, now).seconds
    if (seconds < -10) return ""
    return when {
        seconds < 60 -> "刚刚发布。"
        seconds < 3600 -> "${seconds / 60}分钟前发布。"
        else -> "${seconds / 3600}小时前发布。"
    }
}
