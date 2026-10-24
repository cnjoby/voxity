package com.sonar.app

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LinxDeviceTest {
    @get:Rule(order = 0)
    val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val vm
        get() = ViewModelProvider(compose.activity)[SonarViewModel::class.java]

    private fun enabled(key: String) =
        InstrumentationRegistry.getArguments().getString(key) == "true"

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(200)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "test-linx-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun tone(): ByteArray =
        ByteBuffer.allocate(19200)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(9600) { i ->
                    putShort((sin(i * 2 * Math.PI * 440 / 16000) * 8000).toInt().toShort())
                }
            }
            .array()

    @Test
    fun pcmActivationPrivateSpeechAndMute() {
        assumeTrue(enabled("linx_protocol"))
        roundTrip(LinxAudioParams())
    }

    @Test
    fun opusNegotiationUsesRealPlatformCodecsAndPlaysTheReply() {
        assumeTrue(enabled("linx_protocol"))
        roundTrip(LinxAudioParams("opus", 24000, 60))
    }

    private fun roundTrip(params: LinxAudioParams) = runBlocking {
        if (params.format == "pcm") {
            // Migrate an existing manual identity without changing it on the next launch.
            val suffix = java.util.UUID.randomUUID().toString()
            val context =
                object : android.content.ContextWrapper(compose.activity) {
                    override fun getSharedPreferences(
                        name: String,
                        mode: Int,
                    ): android.content.SharedPreferences =
                        super.getSharedPreferences("linx-migration-$suffix-$name", mode)
                }
            try {
                val legacy =
                    DeviceConfig(
                        linxDevice = "02:11:22:33:44:55",
                        clientId = java.util.UUID.randomUUID().toString(),
                    )
                val normalized = ConfigStore(context).normalize(legacy)
                val restored = ConfigStore(context).load()
                assertEquals(legacy.linxDevice, normalized.linxDevice)
                assertEquals(legacy.clientId, restored.clientId)
                assertEquals(legacy.linxDevice, restored.linxDevice)
            } finally {
                context.getSharedPreferences("connection", 0).edit().clear().commit()
                context.getSharedPreferences("linx-identity", 0).edit().clear().commit()
            }
        }
        val original = ConfigStore(compose.activity).load()
        val backend = MockWebServer()
        val bound = AtomicBoolean(false)
        val uploaded = AtomicInteger(0)
        val publicRequests = AtomicInteger(0)
        val codec = AndroidLinxCodec()
        val audio = codec.encode(tone(), params)
        val decoded = codec.decode(audio, params)
        val samples = ByteBuffer.wrap(decoded.bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var power = 0L
        while (samples.hasRemaining()) {
            val sample = samples.get().toLong()
            power += sample * sample
        }
        assertTrue("Codec round trip lost the signal", power > 1000000)
        assertTrue(decoded.rate in setOf(16000, 24000, 48000))
        if (params.format == "opus") assertTrue(audio.any { OpusPackets.durationUs(it) == 60000L })
        backend.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/v1/ota/" -> {
                            val data = JSONObject(request.body.readUtf8())
                            assertEquals(request.getHeader("Device-Id"), data.text("mac_address"))
                            assertNull(request.getHeader("Authorization"))
                            if (!bound.get())
                                MockResponse()
                                    .setBody(
                                        json(
                                                "activation" to
                                                    json(
                                                        "code" to "654321",
                                                        "message" to
                                                            "https://linx.qiniu.com/\n654321",
                                                    )
                                            )
                                            .toString()
                                    )
                            else
                                MockResponse()
                                    .setBody(
                                        json(
                                                "websocket" to
                                                    json(
                                                        "url" to
                                                            backend
                                                                .url("/v1/ws/")
                                                                .toString()
                                                                .replace("http://", "ws://"),
                                                        "token" to "synthetic-device-token",
                                                    )
                                            )
                                            .toString()
                                    )
                        }
                        "/v1/ota/activate" ->
                            if (bound.get()) MockResponse().setBody("success")
                            else MockResponse().setResponseCode(202)
                        "/v1/ws/" ->
                            MockResponse()
                                .withWebSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onMessage(ws: WebSocket, text: String) {
                                            val j = JSONObject(text)
                                            when {
                                                j.text("type") == "hello" ->
                                                    ws.send(
                                                        json(
                                                                "type" to "hello",
                                                                "transport" to "websocket",
                                                                "session_id" to "local-speech",
                                                                "audio_params" to params.hello(),
                                                            )
                                                            .toString()
                                                    )
                                                j.text("type") == "listen" &&
                                                    j.text("state") == "stop" -> {
                                                    ws.send(
                                                        json(
                                                                "type" to "stt",
                                                                "session_id" to "local-speech",
                                                                "text" to "[协议模拟识别] 你好灵矽",
                                                            )
                                                            .toString()
                                                    )
                                                    ws.send(
                                                        json("type" to "tts", "state" to "start")
                                                            .toString()
                                                    )
                                                    ws.send(
                                                        json(
                                                                "type" to "tts",
                                                                "state" to "sentence_start",
                                                                "text" to "[协议模拟回复] 手机语音测试完成",
                                                            )
                                                            .toString()
                                                    )
                                                    audio.forEach { ws.send(ByteString.of(*it)) }
                                                    ws.send(
                                                        json(
                                                                "type" to "tts",
                                                                "state" to "stop",
                                                                "is_aborted" to false,
                                                            )
                                                            .toString()
                                                    )
                                                }
                                            }
                                        }

                                        override fun onMessage(ws: WebSocket, bytes: ByteString) {
                                            uploaded.addAndGet(bytes.size)
                                        }
                                    }
                                )
                        else -> {
                            publicRequests.incrementAndGet()
                            MockResponse().setResponseCode(404)
                        }
                    }
            }
        backend.start()
        val otaEndpoint = backend.url("/v1/ota/").toString()
        try {
            compose.runOnIdle {
                vm.configure(
                    original.copy(
                        baseUrl = "http://127.0.0.1:1",
                        token = "",
                        mock = false,
                        linxToken = "",
                        linxOtaUrl = otaEndpoint,
                    )
                )
            }
            compose.onNodeWithTag("settings").performScrollTo().performClick()
            compose.onNodeWithTag("linx-activate").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.state.value.linx.phase in setOf("pending", "failed") }
            assertEquals(vm.state.value.linx.status, "pending", vm.state.value.linx.phase)
            compose.onNodeWithTag("linx-code").assertTextEquals("654321")
            screenshot("${params.format}-activation")
            val identity = vm.state.value.config.linxDevice
            bound.set(true)
            compose.onNodeWithTag("linx-check").performScrollTo().performClick()
            compose.waitUntil(15000) { vm.state.value.linx.phase in setOf("bound", "failed") }
            assertEquals(vm.state.value.linx.status, "bound", vm.state.value.linx.phase)
            assertEquals(identity, ConfigStore(compose.activity).load().linxDevice)
            compose.onNodeWithTag("linx-sound").performScrollTo().performClick()
            compose.onNodeWithTag("linx-record").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.state.value.linx.phase == "recording" }
            Thread.sleep(700)
            compose.onNodeWithTag("linx-record").performScrollTo().performClick()
            compose.waitUntil(20000) { vm.state.value.linx.phase in setOf("complete", "failed") }
            assertEquals(vm.state.value.linx.status, "complete", vm.state.value.linx.phase)
            assertTrue(vm.state.value.linx.status.contains("播放均已完成"))
            assertTrue(vm.state.value.linx.format.contains(params.format.uppercase()))
            assertTrue(uploaded.get() > 0)
            screenshot("${params.format}-completed")
            assertNull(vm.state.value.draft)
            assertEquals(0, publicRequests.get())
            compose.onNodeWithTag("linx-sound").performScrollTo().performClick()
            compose.onNodeWithTag("linx-record").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.state.value.linx.phase == "recording" }
            Thread.sleep(500)
            compose.onNodeWithTag("linx-record").performClick()
            compose.waitUntil(20000) { vm.state.value.linx.phase in setOf("complete", "failed") }
            assertTrue(vm.state.value.linx.status, vm.state.value.linx.status.contains("当前不出声"))
            // Stopping a private recording cannot leave the microphone or queue occupied.
            compose.onNodeWithTag("linx-record").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.state.value.linx.phase == "recording" }
            compose.runOnIdle { vm.stopLinxVoice() }
            compose.waitUntil(5000) { !vm.state.value.linx.busy }
            assertNull(vm.state.value.draft)
            if (params.format == "pcm") {
                // Global Sonar quiet also blocks AI playback, even when the local switch is on.
                val publicConfig =
                    vm.state.value.config.copy(
                        mock = true,
                        debugTranscript = false,
                        simulatedLocation = true,
                        baseUrl = otaEndpoint.removeSuffix("/v1/ota/"),
                    )
                compose.runOnIdle { vm.configure(publicConfig) }
                compose.waitUntil(10000) { vm.state.value.connected }
                compose.runOnIdle {
                    vm.linxSound(true)
                    vm.toggleQuiet()
                }
                compose.waitUntil(10000) { vm.state.value.profile?.mode == "quiet" }
                compose.onNodeWithTag("linx-record").performScrollTo().performClick()
                compose.waitUntil(5000) { vm.state.value.linx.phase == "recording" }
                Thread.sleep(500)
                compose.onNodeWithTag("linx-record").performClick()
                compose.waitUntil(20000) {
                    vm.state.value.linx.phase in setOf("complete", "failed")
                }
                assertTrue(vm.state.value.linx.status, vm.state.value.linx.status.contains("当前不出声"))
                // Public dictation uses STT but does not publish or play the assistant response.
                compose.onNodeWithTag("settings").performScrollTo().performClick()
                compose.onNodeWithTag("record").performScrollTo().performClick()
                compose.waitUntil(5000) { vm.state.value.recording }
                Thread.sleep(500)
                compose.onNodeWithTag("record").performClick()
                compose.waitUntil(15000) {
                    vm.state.value.draft?.transcript?.isNotBlank() == true &&
                        !vm.state.value.transcribing
                }
                assertEquals("[协议模拟识别] 你好灵矽", vm.state.value.draft!!.transcript)
                assertNull(vm.state.value.draft!!.postId)
                assertNull(vm.state.value.question)
                screenshot("public-candidate")
                compose.runOnIdle { vm.discardDraft() }
            }
        } finally {
            compose.runOnIdle { vm.configure(original) }
            backend.shutdown()
        }
    }

    @Test
    fun requestRealActivationWithoutPublishingOrSendingAudio() {
        assumeTrue(enabled("linx_live"))
        val config = ConfigStore(compose.activity).load()
        assertEquals(
            "Refuse a test gateway in a live activation check",
            LINX_OTA,
            config.linxOtaUrl,
        )
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.onNodeWithTag("linx-activate").performScrollTo().performClick()
        compose.waitUntil(30000) {
            vm.state.value.linx.phase in setOf("pending", "bound", "failed")
        }
        screenshot("live-activation")
        val state = vm.state.value.linx
        assertTrue(state.status, state.phase in setOf("pending", "bound"))
        // No WebSocket token or encrypted config is exported to the report.
        File(compose.activity.filesDir, "linx-activation.json")
            .writeText(
                json(
                        "phase" to state.phase,
                        "device_id" to config.linxDevice,
                        "client_id" to config.clientId,
                        "activation_code" to state.activationCode,
                        "console_url" to state.consoleUrl,
                        "status" to state.status,
                        "has_pending_public_draft" to (vm.state.value.draft != null),
                        "public_audio_busy" to
                            (vm.state.value.recording ||
                                vm.state.value.transcribing ||
                                vm.state.value.playing != null),
                    )
                    .toString()
            )
    }

    @Test
    fun prerecordedAudioThroughRealLinxWithoutMicrophoneOrPublication(): Unit = runBlocking {
        assumeTrue(enabled("linx_live_speech"))
        val config = ConfigStore(compose.activity).load()
        assertTrue("Bind this device before the live speech check", config.linxToken.isNotBlank())
        assertEquals(LINX_OTA, config.linxOtaUrl)
        val before = vm.state.value.draft?.requestId
        assertFalse(
            vm.state.value.recording ||
                vm.state.value.transcribing ||
                vm.state.value.playing != null
        )
        val source =
            compose.activity.resources.openRawResource(R.raw.onsite_question).use { it.readBytes() }
        val bytes = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, bytes.getInt(24))
        assertEquals(1, bytes.getShort(22).toInt())
        assertEquals(16, bytes.getShort(34).toInt())
        var offset = 12
        var pcm: ByteArray? = null
        while (offset + 8 <= source.size) {
            val length = bytes.getInt(offset + 4)
            require(length >= 0 && offset + 8L + length <= source.size)
            if (String(source, offset, 4) == "data") {
                pcm = source.copyOfRange(offset + 8, offset + 8 + length)
                break
            }
            offset += 8 + length + (length and 1)
        }
        val data = requireNotNull(pcm)
        val file =
            File(compose.activity.cacheDir, "linx-live-prerecorded.wav").apply {
                writeBytes(Wav.header(data.size) + data)
            }
        val report =
            json(
                "input" to "bundled_onsite_question",
                "microphone_used" to false,
                "published" to false,
                "pending_public_draft_before" to (before != null),
            )
        try {
            val reply =
                LinxSession()
                    .exchange(
                        file,
                        config,
                        status = { report.put("last_stage", it) },
                        recognized = { report.put("transcript", it) },
                    )
            assertTrue(reply.transcript.isNotBlank())
            assertNotNull(reply.audio)
            assertTrue(reply.audio!!.bytes.isNotEmpty())
            assertEquals(before, vm.state.value.draft?.requestId)
            report
                .put("status", "passed")
                .put("transcript", reply.transcript)
                .put("reply_text_characters", reply.assistantText.length)
                .put("wire_format", reply.params.format)
                .put("wire_rate", reply.params.rate)
                .put("decoded_rate", reply.audio.rate)
                .put("decoded_bytes", reply.audio.bytes.size)
        } catch (e: Exception) {
            report
                .put("status", "failed")
                .put("error_type", e.javaClass.simpleName)
                .put("error", e.message?.take(240))
            throw e
        } finally {
            file.delete()
            File(compose.activity.filesDir, "linx-live-speech.json").writeText(report.toString())
        }
    }

    @Test
    fun boundPhoneVoiceEntryPreservesPendingPublicDraft() {
        assumeTrue(enabled("linx_prepare"))
        assertTrue(ConfigStore(compose.activity).load().linxToken.isNotBlank())
        val before = vm.state.value.draft
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.onNodeWithTag("linx-record").performScrollTo().assertIsEnabled()
        screenshot("phone-ready")
        val manager = compose.activity.getSystemService(android.media.AudioManager::class.java)
        val preserved = before == vm.state.value.draft
        File(compose.activity.filesDir, "linx-phone-ready.json")
            .writeText(
                json(
                        "voice_button_enabled" to true,
                        "has_pending_public_draft" to (before != null),
                        "pending_public_draft_preserved" to preserved,
                        "profile_mode" to vm.state.value.profile?.mode,
                        "media_volume" to
                            manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC),
                        "media_max_volume" to
                            manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC),
                    )
                    .toString()
            )
        assertTrue("Public draft was modified by private voice setup", preserved)
    }

    @Test
    fun humanMicrophoneThroughRealLinxPreservesPendingPublicDraft() {
        assumeTrue(enabled("linx_human"))
        assertTrue(ConfigStore(compose.activity).load().linxToken.isNotBlank())
        val before = vm.state.value.draft
        val report =
            json(
                "input" to "phone_microphone",
                "published" to false,
                "had_pending_public_draft" to (before != null),
            )
        try {
            compose.onNodeWithTag("settings").performScrollTo().performClick()
            if (!vm.state.value.linx.soundEnabled)
                compose.onNodeWithTag("linx-sound").performScrollTo().performClick()
            compose.onNodeWithTag("linx-record").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(6000) { vm.state.value.linx.phase == "recording" }
            compose.waitUntil(90000) { vm.state.value.linx.phase in setOf("complete", "failed") }
            val state = vm.state.value.linx
            val preserved = before == vm.state.value.draft
            report
                .put("phase", state.phase)
                .put("status", state.status)
                .put("transcript", state.transcript)
                .put("reply_text_characters", state.reply.length)
                .put("wire_format", state.format)
                .put("playback_completed", state.status.contains("播放均已完成"))
                .put("pending_public_draft_preserved", preserved)
            screenshot("phone-human")
            assertEquals(state.status, "complete", state.phase)
            assertTrue("No microphone transcription returned", state.transcript.isNotBlank())
            assertTrue("Pending public draft was modified by private voice", preserved)
        } finally {
            File(compose.activity.filesDir, "linx-phone-human.json").writeText(report.toString())
        }
    }
}
