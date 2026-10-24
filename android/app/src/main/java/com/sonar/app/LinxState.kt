package com.sonar.app

data class LinxState(
    val phase: String = "unbound",
    val status: String = "尚未激活这台手机",
    val activationCode: String? = null,
    val consoleUrl: String? = null,
    val transcript: String = "",
    val reply: String = "",
    val soundEnabled: Boolean = false,
    val format: String = "",
) {
    val busy
        get() = phase in setOf("activating", "checking", "recording", "recognizing", "playing")
}
