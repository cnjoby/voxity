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
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingLinx) model.recordLinx() else model.record(pendingReply)
            } else {
                model.clearComposition()
                model.notice("需要麦克风权限才能录音；可在系统设置中开启")
            }
            pendingReply = null
            pendingLinx = false
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
