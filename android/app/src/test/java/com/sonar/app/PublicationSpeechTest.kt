package com.sonar.app

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PublicationSpeechTest {
    private val published = Instant.parse("2026-10-26T15:04:40Z")
    private val payload get() = json("kind" to "question", "published_at" to published.toString(),
        "recorded_at" to "2026-10-26T14:50:00Z")

    @Test fun usesPublicationTimeAndRoundsDownAtMinuteAndHourBoundaries() {
        for ((seconds, expected) in listOf(0L to "刚刚发布。", 59L to "刚刚发布。",
            60L to "1分钟前发布。", 3599L to "59分钟前发布。", 3600L to "1小时前发布。",
            90000L to "25小时前发布。")) {
            assertEquals(expected, publicationIntro(payload, published.plusSeconds(seconds)))
        }
    }

    @Test fun recomputesWhenPlaybackIsDelayedAndIgnoresTheCachedIntro() {
        val queued = payload.put("playback_intro", "1分钟前发布。")
        assertEquals("1分钟前发布。", publicationIntro(queued, published.plusSeconds(60)))
        assertEquals("2小时前发布。", publicationIntro(queued, published.plusSeconds(7200)))
    }

    @Test fun unknownOrFuturePublicationIsNotInventedAndPrivateChatStaysUnchanged() {
        assertEquals("", publicationIntro(json("published_at" to "invalid"), published))
        assertEquals("", publicationIntro(json("collected_at" to published.toString()), published))
        assertEquals("", publicationIntro(payload, published.minusSeconds(11)))
        assertEquals("刚刚发布。", publicationIntro(payload, published.minusSeconds(5)))
        assertEquals("", publicationIntro(payload.put("kind", "chat"), published))
    }
}
