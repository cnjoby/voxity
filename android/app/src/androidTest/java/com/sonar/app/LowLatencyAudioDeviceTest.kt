package com.sonar.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Isolated native codecs and loopback protocol. No microphone, production service or Linx account. */
class LowLatencyAudioDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pcm(seconds: Int) = ByteBuffer.allocate(32000 * seconds).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(16000 * seconds) { i -> putShort((sin(i * 2 * Math.PI * 320 / 16000) * 7000).toInt().toShort()) }
    }.array()

    @Test fun oggOpusIsSmallStableDecodableAndKeepsOriginalDuration() = runBlocking {
        val original = pcm(5)
        val file = File(context.cacheDir, "codec-proof.wav").apply { writeBytes(Wav.header(original.size) + original) }
        OpusFiles.forgetUpload(file)
        try {
            val encoded = OpusFiles.forUpload(file)
            assertEquals("OggS", String(encoded, 0, 4))
            assertTrue("encoded=${encoded.size}", encoded.size < original.size / 4)
            assertArrayEquals(encoded, OpusFiles.forUpload(file))
            val decoded = OpusFiles.toWav(encoded, context.cacheDir)
            assertEquals("RIFF", String(decoded, 0, 4))
            assertEquals(original.size + 44, decoded.size)
            assertTrue(SpeechSegments.rms(decoded.copyOfRange(44, 44 + 640)) > 500)
            // Synthetic fixture for independent server decoder validation, never user audio.
            File(context.filesDir, "test-opus-proof.opus").writeBytes(encoded)
            File(context.filesDir, "test-opus-proof.json").writeText(json("wav_bytes" to original.size + 44, "opus_bytes" to encoded.size,
                "decoded_wav_bytes" to decoded.size, "bitrate" to OpusFiles.BIT_RATE).toString())
        } finally { OpusFiles.forgetUpload(file); file.delete() }
    }

    @Test fun oggOpusKeepsPartialSecondDurationOnDevice() = runBlocking {
        val original = pcm(3).copyOf(79360) // 2.48s, matching a real VAD segment.
        val file = File(context.cacheDir, "partial-second-proof.wav").apply { writeBytes(Wav.header(original.size) + original) }
        OpusFiles.forgetUpload(file)
        try {
            val encoded = OpusFiles.forUpload(file)
            File(context.filesDir, "test-opus-partial-proof.opus").writeBytes(encoded)
            val decoded = OpusFiles.toWav(encoded, context.cacheDir)
            assertEquals(original.size + 44, decoded.size)
            assertArrayEquals(encoded, OpusFiles.forUpload(file))
        } finally { OpusFiles.forgetUpload(file); file.delete() }
    }

    @Test fun rawOpusStreamingProducesPcmBeforeEndOfReply() = runBlocking {
        val params = LinxAudioParams("opus", 24000, 60)
        val input = pcm(2)
        val encoder = StreamingOpusEncoder(params)
        val decoder = StreamingOpusDecoder(params)
        var packets = 0
        var decodedBytes = 0
        try {
            for (offset in input.indices step 640) {
                for (packet in encoder.push(input.copyOfRange(offset, offset + 640))) {
                    packets++
                    for (frame in decoder.push(packet)) decodedBytes += frame.bytes.size
                }
            }
            assertTrue(packets > 0)
            assertTrue("PCM must arrive before encoder/decoder EOS", decodedBytes > 0)
            for (packet in encoder.finish()) for (frame in decoder.push(packet)) decodedBytes += frame.bytes.size
            for (frame in decoder.finish()) decodedBytes += frame.bytes.size
            assertTrue(decodedBytes > 16000)
        } finally { encoder.close(); decoder.close() }
    }

    @Test fun singleSocketRoutesOnceAndStartsAudioBeforeTtsStop() = runBlocking {
        MockWebServer().use { server ->
            val audible = CountDownLatch(1)
            val stopSent = AtomicBoolean(false)
            val startedBeforeStop = AtomicBoolean(false)
            val routes = AtomicInteger()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    val value = JSONObject(text)
                    if (value.text("type") == "hello") ws.send(json("type" to "hello", "transport" to "websocket", "session_id" to "turn-1", "audio_params" to LinxAudioParams().hello()).toString())
                    if (value.text("type") == "listen" && value.text("state") == "stop") {
                        ws.send(json("type" to "stt", "text" to "聊聊今天的见闻").toString())
                        ws.send(json("type" to "tts", "state" to "start").toString())
                        ws.send(json("type" to "tts", "state" to "sentence_start", "text" to "我们可以聊聊今天的见闻。").toString())
                        ws.send(pcm(1).toByteString())
                        assertTrue("client waited for whole TTS", audible.await(5, TimeUnit.SECONDS))
                        stopSent.set(true)
                        ws.send(json("type" to "tts", "state" to "stop").toString())
                    }
                }
            }))
            val file = File(context.cacheDir, "stream-proof.wav").apply { writeBytes(Wav.synthetic()) }
            val audio = AudioCoordinator(context)
            try {
                val reply = audio.stream(mayStart = { true }, onStarted = {
                    startedBeforeStop.set(!stopSent.get()); audible.countDown()
                }, onReference = {}) { sink ->
                    LinxSession(allowLocal = true).exchange(file, DeviceConfig(linxUrl = server.url("/ws").toString().replace("http://", "ws://"),
                        linxToken = "test-only", linxDevice = "02:00:00:00:00:01", clientId = "test-only"),
                        route = { routes.incrementAndGet(); true }, onPcm = sink)
                }
                assertEquals(1, server.requestCount)
                assertEquals(1, routes.get())
                assertTrue(startedBeforeStop.get())
                assertEquals("我们可以聊聊今天的见闻。", reply.assistantText)
                assertNull(reply.audio) // PCM was streamed, never accumulated into another WAV.
            } finally { audio.close(); file.delete() }
        }
    }

    @Test fun localStopCancelsStreamEvenWhileUpstreamIsStalled() = runBlocking {
        val audio = AudioCoordinator(context)
        val began = CompletableDeferred<Unit>()
        val job = launch {
            audio.stream(mayStart = { true }, onStarted = { began.complete(Unit) }, onReference = {}) { sink ->
                sink(PcmAudio(pcm(1), 16000))
                delay(60000)
            }
        }
        try {
            withTimeout(5000) { began.await() }
            audio.stopPlayback()
            withTimeout(1000) { job.join() }
            assertTrue(job.isCancelled)
            assertEquals(AudioMode.IDLE, audio.gate.mode)
        } finally { job.cancel(); audio.close() }
    }

    @Test fun liveOpusSocketUploadsDuringCaptureAndDecodesItsReply() = runBlocking {
        MockWebServer().use { server ->
            val packets = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            val first = CountDownLatch(1)
            val params = LinxAudioParams("opus", 16000, 20)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    val value = JSONObject(text)
                    if (value.text("type") == "hello") {
                        assertEquals("opus", value.getJSONObject("audio_params").text("format"))
                        ws.send(json("type" to "hello", "transport" to "websocket", "audio_params" to params.hello()).toString())
                    }
                    if (value.text("type") == "listen" && value.text("state") == "stop") {
                        ws.send(json("type" to "stt", "text" to "本轮私人聊天").toString())
                        ws.send(json("type" to "tts", "state" to "start").toString())
                        for (packet in packets) ws.send(packet.toByteString())
                        ws.send(json("type" to "tts", "state" to "stop").toString())
                    }
                }
                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) { packets.add(bytes.toByteArray()); first.countDown() }
            }))
            val frames = Channel<ByteArray>(100)
            var decoded = 0
            val reply = async {
                LinxSession(allowLocal = true).exchange(null, DeviceConfig(linxUrl = server.url("/ws").toString().replace("http://", "ws://"),
                    linxToken = "test-only", linxDevice = "02:00:00:00:00:02", clientId = "test-only"),
                    pcmFrames = frames, route = { true }, onPcm = { decoded += it.bytes.size })
            }
            val input = pcm(2)
            for (offset in 0 until 6400 step 640) frames.send(input.copyOfRange(offset, offset + 640))
            assertTrue(withContext(Dispatchers.IO) { first.await(5, TimeUnit.SECONDS) })
            assertFalse(reply.isCompleted)
            for (offset in 6400 until input.size step 640) frames.send(input.copyOfRange(offset, offset + 640))
            frames.close()
            withTimeout(10000) { reply.await() }
            assertEquals(1, server.requestCount)
            assertTrue(packets.sumOf { it.size } < input.size / 3)
            assertTrue(decoded > 48000)
        }
    }
}
