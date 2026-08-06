package com.watch.hsp.service

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class PhoneAlertController(private val context: Context) {
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    var isRinging: Boolean = false
        private set

    fun start(): Result<Unit> = runCatching {
        if (isRinging) return@runCatching
        isRinging = true

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = uri?.let { RingtoneManager.getRingtone(context, it) }
        ringtone?.play()
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 650, 650), 0))
        wakeLock().takeUnless { it.isHeld }?.acquire(ALERT_WAKE_LOCK_TIMEOUT_MS)
    }.onFailure {
        stop()
    }

    fun stop() {
        isRinging = false
        try {
            ringtone?.stop()
            ringtone = null
            vibrator()?.cancel()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
            // Best-effort cleanup; callers decide how to surface failures.
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun wakeLock(): PowerManager.WakeLock {
        val currentWakeLock = wakeLock
        if (currentWakeLock != null) return currentWakeLock

        val powerManager = requireNotNull(context.getSystemService(PowerManager::class.java)) {
            "PowerManager unavailable"
        }
        return powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:FindPhone")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
    }

    private companion object {
        const val ALERT_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
