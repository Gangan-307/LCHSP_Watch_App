package com.watch.hsp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.watch.hsp.data.WatchNotificationRepository

/** Receives selected phone notifications after the user enables notification access. */
class WatchNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach(::forwardNotification)
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        forwardNotification(statusBarNotification)
    }

    private fun forwardNotification(statusBarNotification: StatusBarNotification) {
        val notification = statusBarNotification.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val app = sourceApp(statusBarNotification.packageName) ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString(" ")
            ?: ""
        if (title.isBlank() && body.isBlank()) return

        val stored = WatchNotificationRepository.record(
            this,
            app,
            title.toString(),
            body.toString(),
            statusBarNotification.postTime,
            statusBarNotification.key
        )
        if (stored != null) {
            Log.i(TAG, "Queued ${sourceName(app)} notification id=${stored.id}")
        }
    }

    private fun sourceApp(packageName: String): Int? = when (packageName) {
        "com.tencent.mm" -> BleProtocol.NOTIFICATION_APP_WECHAT
        "com.tencent.mobileqq", "com.tencent.tim" -> BleProtocol.NOTIFICATION_APP_QQ
        "com.android.mms", "com.android.messaging", "com.google.android.apps.messaging",
        "com.miui.sms", "com.samsung.android.messaging", "com.huawei.message" ->
            BleProtocol.NOTIFICATION_APP_SMS
        else -> null
    }

    private fun sourceName(app: Int): String = when (app) {
        BleProtocol.NOTIFICATION_APP_SMS -> "SMS"
        BleProtocol.NOTIFICATION_APP_WECHAT -> "WeChat"
        BleProtocol.NOTIFICATION_APP_QQ -> "QQ"
        else -> "phone"
    }

    private companion object {
        const val TAG = "HspNotifications"
    }
}
