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
    private var pendingReply: Delivery? = null
    private var pendingLinx = false
    private var pendingExchange: Pair<Boolean, Boolean>? = null
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val exchange = pendingExchange
                if (exchange != null) model.startExchange(exchange.first, exchange.second)
                else if (pendingLinx) model.recordLinx() else model.record(pendingReply)
            } else {
                model.clearComposition()
                model.notice("需要麦克风权限才能录音；可在系统设置中开启")
            }
            pendingReply = null
            pendingLinx = false
            pendingExchange = null
        }

    private val exchangePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val pending = pendingExchange
            pendingExchange = null
            val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val notifications = android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (pending != null && mic && notifications) model.startExchange(pending.first, pending.second)
            else model.notice("请停车后允许麦克风和通知；通知提供后台安静与结束入口")
        }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.values.any { granted -> granted }) model.useCurrentLocation()
            else model.notice("定位未开启，仍可点地点名称手选询问目标。")
        }

    private fun locate() {
        if (PhoneLocation(this).permitted()) model.useCurrentLocation()
        else
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SonarViewModel::class.java]
        setContent {
            SonarScreen(
                model,
                onLocate = ::locate,
                onExchangeStart = { publish, play ->
                    val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (android.os.Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
                        model.startExchange(publish, play)
                    else {
                        pendingExchange = publish to play
                        exchangePermissions.launch(needed.toTypedArray())
                    }
                },
                onLinxRecord = {
                    pendingLinx = true
                    if (
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    ) {
                        model.recordLinx()
                        pendingLinx = false
                    } else microphone.launch(Manifest.permission.RECORD_AUDIO)
                },
            ) { reply ->
                if (reply == null && model.state.value.selectedPlace == null) {
                    locate()
                    return@SonarScreen
                }
                pendingLinx = false
                pendingReply = reply
                if (
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    model.record(reply)
                    pendingReply = null
                } else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.activate()
    }

    override fun onStop() {
        model.pause()
        super.onStop()
    }
}
