package com.sonar.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var model: SonarViewModel
    private var pendingStart = false
    private val voicePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val start = pendingStart
        pendingStart = false
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (start && mic && notifications) model.startVoiceSession()
        else model.notice("请在开始前允许麦克风和通知；通知提供后台安静与结束入口")
    }
    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.any { granted -> granted }) model.useCurrentLocation()
        else model.notice("未开启手机定位，仍可使用语音助手或选择远程地点。")
    }
    private fun locate() {
        if (PhoneLocation(this).permitted()) model.useCurrentLocation()
        else locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    private fun startVoice() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) model.startVoiceSession()
        else { pendingStart = true; voicePermissions.launch(needed.toTypedArray()) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SonarViewModel::class.java]
        setContent { SonarScreen(model, onLocate = ::locate, onVoiceStart = ::startVoice) }
    }
    override fun onStart() { super.onStart(); model.activate() }
    override fun onStop() { model.pause(); super.onStop() }
}
