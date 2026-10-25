package com.sonar.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class VoiceFeedbackTest {
    @Test fun quietEspAudioGetsAudiblePlaybackCopyWithoutChangingOriginalOrClipping() {
        val pcm = ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16000) { pcm.putShort(if (it % 2 == 0) 500 else -500) }
        val original = Wav.header(32000) + pcm.array()
        val saved = original.copyOf()
        val copy = originalPlaybackLevel(original)
        assertArrayEquals(saved, original)
        assertEquals(2800, ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).getShort(44).toInt())
        assertEquals(original.size, copy.size)
        assertArrayEquals(original.copyOfRange(0, 44), copy.copyOfRange(0, 44))
        assertArrayEquals(Wav.synthetic(), originalPlaybackLevel(Wav.synthetic()))
    }

    @Test fun receiptNamesParkScopeAndNeverClaimsAnUnacknowledgedPeerReceivedAudio() {
        val result = json("status" to "published", "action" to "question", "target_place" to json("label" to "七牛"),
            "match_scope" to json("kind" to "park", "label" to "张江人工智能岛"), "dispatch" to json("device_received" to 0))
        val receipt = submissionReceipt(result)
        assertTrue(receipt.contains("提交到七牛") && receipt.contains("园区范围"))
        assertTrue(receipt.contains("尚未有现场设备确认接收"))
        assertFalse(receipt.contains("已有现场设备确认收到"))
        result.getJSONObject("dispatch").put("device_received", 1)
        assertTrue(submissionReceipt(result).contains("确认收到邀请"))
    }
}
