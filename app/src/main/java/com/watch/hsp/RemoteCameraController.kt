package com.watch.hsp

import android.os.Handler
import android.os.Looper

/** Bridges BLE shutter commands to the foreground remote-camera activity. */
object RemoteCameraController {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var captureHandler: (() -> Unit)? = null

    fun attach(handler: () -> Unit) {
        captureHandler = handler
    }

    fun detach(handler: () -> Unit) {
        if (captureHandler === handler) captureHandler = null
    }

    fun requestCapture(): Boolean {
        val handler = captureHandler ?: return false
        mainHandler.post(handler)
        return true
    }
}
