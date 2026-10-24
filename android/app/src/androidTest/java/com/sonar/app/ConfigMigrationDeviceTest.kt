package com.sonar.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real Android Keystore and preferences, isolated from the installed user's configuration. */
class ConfigMigrationDeviceTest {
    private fun isolated(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "config-migration-${UUID.randomUUID()}"
        val files = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$prefix-$name", mode)

            override fun deleteSharedPreferences(name: String) =
                base.deleteSharedPreferences("$prefix-$name")

            override fun getFilesDir() = files
        }
        try {
            test(context)
        } finally {
            for (name in listOf("connection", "linx-identity")) {
                base.deleteSharedPreferences("$prefix-$name")
            }
            files.deleteRecursively()
        }
    }

    @Test
    fun upgradePersistsEncryptedConfigAndPreservesCredentialsAndOldTaskCache() = isolated { context ->
        val original = DeviceConfig(
            baseUrl = "http://127.0.0.1:8765",
            token = "test-sonar-token",
            linxUrl = "wss://voice.example.com/ws/",
            linxToken = "test-linx-token",
            linxDevice = "02:01:02:03:04:05",
            clientId = "test-client-id",
            debugTranscript = true,
            simulatedLocation = true,
            testPlaceId = "test-place",
        )
        ConfigStore(context).save(original)
        val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
        // The pre-upgrade app persisted this encrypted envelope without a migration marker.
        assertTrue(prefs.edit().remove("server_address_migrated_v1").commit())
        val oldTasks = TaskStore(context, original.scope)
        oldTasks.questionId = "test-question"
        oldTasks.markAttempt("test-delivery:test-request")
        try {
            val expected = original.copy(baseUrl = SONAR_BASE_URL)
            assertEquals(expected, ConfigStore(context).load())
            assertEquals(expected, ConfigStore(context).load())
            assertEquals("test-question", oldTasks.questionId)
            assertTrue(oldTasks.attempted("test-delivery:test-request"))
            assertNull(TaskStore(context, expected.scope).questionId)
            assertFalse(prefs.getString("sealed", "")!!.contains(original.token))
            assertFalse(prefs.getString("sealed", "")!!.contains(original.linxToken))
            // An explicit local address saved after upgrade remains available for development.
            ConfigStore(context).save(original)
            assertEquals(original, ConfigStore(context).load())
        } finally {
            context.deleteSharedPreferences("tasks-${original.scope}")
            context.deleteSharedPreferences("tasks-${original.copy(baseUrl = SONAR_BASE_URL).scope}")
        }
    }

    @Test
    fun explicitDebugBootstrapKeepsItsLocalEndpoint() = isolated { context ->
        val config = DeviceConfig(baseUrl = "http://localhost:8765", token = "test-token")
        val store = ConfigStore(context)
        val expected = store.normalize(config)
        val bootstrap = File(context.filesDir, "bootstrap.json")
        bootstrap.writeText(expected.toJson().toString())
        assertEquals(expected, store.load())
        assertFalse(bootstrap.exists())
        assertEquals(expected, ConfigStore(context).load())
    }
}
