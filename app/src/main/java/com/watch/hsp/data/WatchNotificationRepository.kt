package com.watch.hsp.data

import android.content.Context
import com.watch.hsp.BleProtocol
import org.json.JSONArray
import org.json.JSONObject

data class PhoneNotification(
    val id: Int,
    val app: Int,
    val title: String,
    val body: String,
    val postedAtMillis: Long,
    val sourceKey: String,
    val fingerprint: String
)

/**
 * Retains a small phone-side history so notifications received while BLE is
 * reconnecting can still be delivered when the watch returns.
 */
object WatchNotificationRepository {
    private const val PREFERENCES_NAME = "watch_notifications"
    private const val PREFERENCES_MESSAGES = "messages"
    private const val PREFERENCES_NEXT_ID = "next_id"
    private const val MAX_MESSAGES = 5

    private val lock = Any()
    private val messages = ArrayList<PhoneNotification>()
    private val listeners = LinkedHashSet<(PhoneNotification) -> Unit>()
    private var initialized = false
    private var appContext: Context? = null

    fun addListener(listener: (PhoneNotification) -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    fun removeListener(listener: (PhoneNotification) -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    fun snapshot(context: Context): List<PhoneNotification> = synchronized(lock) {
        ensureInitialized(context)
        messages.toList()
    }

    fun remove(context: Context, id: Int): Boolean = synchronized(lock) {
        ensureInitialized(context)
        val removed = messages.removeAll { it.id == id }
        if (removed) persist()
        removed
    }

    fun clear(context: Context) = synchronized(lock) {
        ensureInitialized(context)
        if (messages.isNotEmpty()) {
            messages.clear()
            persist()
        }
    }

    /** Returns null when this is an unchanged repost of an already cached notification. */
    fun record(
        context: Context,
        app: Int,
        title: String,
        body: String,
        postedAtMillis: Long,
        sourceKey: String
    ): PhoneNotification? {
        if (app !in BleProtocol.NOTIFICATION_APP_SMS..BleProtocol.NOTIFICATION_APP_QQ) return null

        val normalizedTitle = normalize(title).ifBlank { "New message" }
        val normalizedBody = normalize(body).ifBlank { "New notification" }
        val normalizedKey = sourceKey.ifBlank { "$app:$normalizedTitle:$normalizedBody" }
        val fingerprint = "$normalizedTitle\n$normalizedBody"
        val callbacks: List<(PhoneNotification) -> Unit>
        val stored: PhoneNotification

        synchronized(lock) {
            ensureInitialized(context)
            val existingIndex = messages.indexOfFirst { it.sourceKey == normalizedKey }
            if (existingIndex >= 0 && messages[existingIndex].fingerprint == fingerprint) {
                return null
            }

            val id = if (existingIndex >= 0) {
                messages.removeAt(existingIndex).id
            } else {
                nextMessageId()
            }
            stored = PhoneNotification(
                id = id,
                app = app,
                title = normalizedTitle,
                body = normalizedBody,
                postedAtMillis = postedAtMillis.coerceAtLeast(0L),
                sourceKey = normalizedKey,
                fingerprint = fingerprint
            )
            messages += stored
            while (messages.size > MAX_MESSAGES) messages.removeAt(0)
            persist()
            callbacks = listeners.toList()
        }

        callbacks.forEach { callback -> callback(stored) }
        return stored
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val preferences = preferences()
        val saved = preferences.getString(PREFERENCES_MESSAGES, null)
        if (!saved.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(saved)
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.optInt("id")
                    val app = item.optInt("app")
                    if (id in 1..0xffff &&
                        app in BleProtocol.NOTIFICATION_APP_SMS..BleProtocol.NOTIFICATION_APP_QQ) {
                        messages += PhoneNotification(
                            id = id,
                            app = app,
                            title = item.optString("title"),
                            body = item.optString("body"),
                            postedAtMillis = item.optLong("posted_at", 0L),
                            sourceKey = item.optString("source_key"),
                            fingerprint = item.optString("fingerprint")
                        )
                    }
                }
            }
        }
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        initialized = true
    }

    private fun nextMessageId(): Int {
        val preferences = preferences()
        val current = preferences.getInt(PREFERENCES_NEXT_ID, 1).coerceIn(1, 0xffff)
        val next = if (current == 0xffff) 1 else current + 1
        preferences.edit().putInt(PREFERENCES_NEXT_ID, next).apply()
        return current
    }

    private fun persist() {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(JSONObject().apply {
                put("id", message.id)
                put("app", message.app)
                put("title", message.title)
                put("body", message.body)
                put("posted_at", message.postedAtMillis)
                put("source_key", message.sourceKey)
                put("fingerprint", message.fingerprint)
            })
        }
        preferences().edit().putString(PREFERENCES_MESSAGES, array.toString()).apply()
    }

    private fun preferences() = requireNotNull(appContext).getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
}
