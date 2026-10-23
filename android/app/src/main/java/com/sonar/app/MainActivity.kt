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
    private val microphone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) model.record(pendingReply) else model.notice("需要麦克风权限才能录音；可在系统设置中开启")
            pendingReply = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SonarViewModel::class.java]
        setContent {
            SonarScreen(model) { reply ->
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
