package com.sonar.app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LowLatencyReviewRulesTest {
    @Test fun surplusNativeEncoderPaddingCannotMoveEosBehindPreviousPage() {
        // Synthesized tone encoded on vivo V2405A/Android 16, never microphone audio.
        val source = javaClass.getResource("/vivo-opus-extra-padding.opus")!!.readBytes()
        val fixed = OpusFiles.trimGranule(source, 80000)
        assertTrue(fixed.size < source.size)
        var offset = 0
        var previous = 0L
        var finalGranule = 0L
        while (offset < fixed.size) {
            val count = fixed[offset + 26].toInt() and 255
            val size = 27 + count + (0 until count).sumOf { fixed[offset + 27 + it].toInt() and 255 }
            val granule = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN).getLong(offset + 6)
            assertTrue("Granules must not move backwards", granule >= previous)
            if (fixed[offset + 5].toInt() and 4 != 0) {
                assertEquals(fixed.size, offset + size)
                assertTrue(granule - previous in 1..960)
                finalGranule = granule
            }
            previous = granule
            offset += size
        }
        assertEquals(240312L, finalGranule)
        assertArrayEquals(fixed, OpusFiles.trimGranule(fixed, 80000))
    }

    @Test fun cancelledCaptureDropsLateFramesWithoutEndingTheMicLoop() {
        val queues = CaptureFrameQueues(true, 1)
        queues.cancel()
        repeat(1000) { queues.accept(ByteArray(640)) }
        val finished = CaptureFrameQueues(true, 1)
        finished.finish()
        finished.accept(ByteArray(640))
    }

    @Test fun realQueueOverflowStillFailsExplicitly() {
        val queues = CaptureFrameQueues(true, 1)
        queues.accept(ByteArray(640))
        assertTrue(runCatching { queues.accept(ByteArray(640)) }.isFailure)
    }

    @Test fun unfinishedCacheWriteCanBeReplacedAndCommittedBytesStayFrozen() {
        val source = File.createTempFile("sonar-cache", ".wav")
        val cache = UploadAudioCache(source)
        try {
            File(source.path + ".upload.cache.pending").writeBytes(byteArrayOf(1, 2))
            val hash = ByteArray(32) { 7 }
            assertNull(cache.read(hash))
            val audio = "OggS-test-data".toByteArray()
            cache.commit(hash, audio)
            assertArrayEquals(audio, cache.read(hash))
            assertFalse(File(source.path + ".upload.cache.pending").exists())
            assertTrue(runCatching { cache.read(ByteArray(32) { 8 }) }.isFailure)
        } finally { cache.clear(); source.delete() }
    }

    @Test fun livePcmHonorsAllNegotiatedFrameDurations() {
        val source = ByteArray(640 * 7) { (it % 127).toByte() }
        for (ms in listOf(10, 20, 40, 60)) {
            val packetizer = PcmPacketizer(16000, ms)
            val packets = mutableListOf<ByteArray>()
            for (offset in source.indices step 640) packets.addAll(packetizer.push(source.copyOfRange(offset, offset + 640)))
            packets.addAll(packetizer.finish())
            assertTrue(packets.all { it.size == 16000 * ms / 1000 * 2 })
            assertArrayEquals(source, packets.fold(byteArrayOf()) { a, b -> a + b }.copyOf(source.size))
        }
    }

    @Test fun streamEchoFollowsPlaybackHeadThroughLongNetworkStarvation() {
        var now = 0L
        var played = 0
        val guard = PlaybackEchoGuard { now }
        val random = java.util.Random(79)
        val pcm = ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(16000) { putShort((random.nextInt(12000) - 6000).toShort()) }
        }.array()
        guard.beginStream { played }
        guard.appendStream(PcmAudio(pcm, 16000))
        now = 5000 // Five wall-clock seconds, but only 300ms has actually played.
        played = 4800
        assertTrue(guard.isEcho(pcm.copyOfRange(3200, 3840)))
    }
}
