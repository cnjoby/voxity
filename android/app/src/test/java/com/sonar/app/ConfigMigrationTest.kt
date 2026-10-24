package com.sonar.app

import org.junit.Assert.*
import org.junit.Test

class ConfigMigrationTest {
    private val original =
        DeviceConfig(
            token = "test-sonar-token",
            debugTranscript = true,
            linxUrl = "wss://voice.example.com/ws/",
            linxToken = "test-linx-token",
            linxDevice = "02:01:02:03:04:05",
            clientId = "test-client-id",
            simulatedLocation = true,
            linxOtaUrl = "https://voice.example.com/ota/",
            testPlaceId = "test-place",
        )

    @Test
    fun legacyLocalAddressesMoveWithoutChangingCredentialsOrOtherFields() {
        for (url in listOf("http://127.0.0.1:8765", "http://localhost:8765/", "http://10.0.2.2:8765")) {
            val migrated = original.copy(baseUrl = url).migrateLocalServerAddress()
            assertEquals(original, migrated)
            assertEquals(migrated, migrated.migrateLocalServerAddress())
        }
    }

    @Test
    fun customAddressesAndMockConfigurationsStayUnchanged() {
        for (url in listOf(
            SONAR_BASE_URL,
            "https://sonar.example.com",
            "http://192.168.1.5:8765",
            "http://localhost:18765",
            "https://localhost:8765",
            "http://localhost:8765/api",
            "http://localhost:8765/?environment=test",
            "http://localhost:8765/#test",
            "http://user:password@localhost:8765",
            "invalid",
        )) {
            val config = original.copy(baseUrl = url)
            assertEquals(config, config.migrateLocalServerAddress())
        }
        val mock = original.copy(baseUrl = "http://localhost:8765", mock = true)
        assertEquals(mock, mock.migrateLocalServerAddress())
    }
}
