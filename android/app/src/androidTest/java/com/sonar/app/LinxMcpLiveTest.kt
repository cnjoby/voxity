package com.sonar.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real speech/MCP check, no activity, microphone, speaker, or publication. */
class LinxMcpLiveTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun enabled(key: String) =
        InstrumentationRegistry.getArguments().getString(key) == "true"

    private fun snapshot(): Map<String, String> =
        (File(context.applicationInfo.dataDir, "shared_prefs")
            .listFiles()?.filter { it.name.startsWith("tasks-") } ?: emptyList())
            .associate {
                it.name to MessageDigest.getInstance("SHA-256")
                    .digest(it.readBytes())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }

    @Test
    fun prerecordedVoiceReadsRealSonarMcp(): Unit = runBlocking {
        assumeTrue(enabled("linx_mcp_live"))
        val config = ConfigStore(context).load()
        val before = snapshot()
        assertTrue(config.linxToken.isNotBlank())
        val target = InstrumentationRegistry.getArguments().getString("linx_mcp_base_url")
            ?: error("Provide the actual Sonar HTTPS endpoint")
        val api = HttpSonarApi(
            target,
            config.token,
            ServerClock(android.os.SystemClock::elapsedRealtime),
        )
        val me = api.me()
        assertEquals(config.linxDevice.uppercase(), me.getString("device_id").uppercase())
        val profile = me.getJSONObject("profile")
        val file = File(context.filesDir, "linx-mcp-read-state.wav")
        val report = json(
            "input" to "synthetic_read_state_fixture",
            "client" to "actual_Android_LinxSession",
            "microphone_used" to false,
            "speaker_used" to false,
            "published" to false,
            "sonar_base_url" to target,
            "app_base_url_matches" to (config.baseUrl.trimEnd('/') == target.trimEnd('/')),
            "mapped_user" to profile.getString("user_id"),
            "profile_mode" to profile.getString("mode"),
        )
        try {
            val reply = LinxSession().exchange(file, config)
            assertTrue(reply.transcript.contains("收听"))
            assertTrue(reply.assistantText.contains("收听"))
            assertNotNull(reply.audio)
            val after = api.me().getJSONObject("profile")
            assertEquals(profile.getInt("revision"), after.getInt("revision"))
            assertEquals(before, snapshot())
            assertEquals(config, ConfigStore(context).load())
            report.put("status", "passed")
                .put("transcript", reply.transcript)
                .put("assistant_text", reply.assistantText)
                .put("decoded_bytes", reply.audio!!.bytes.size)
                .put("pending_public_draft_preserved", true)
                .put("phone_config_preserved", true)
        } catch (e: Exception) {
            report.put("status", "failed").put("error_type", e.javaClass.simpleName)
            throw e
        } finally {
            file.delete()
            File(context.filesDir, "linx-mcp-live.json").writeText(report.toString())
        }
    }
}
