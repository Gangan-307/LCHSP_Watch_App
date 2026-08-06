package com.watch.hsp.data

import android.content.Context

class WatchPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val watchAddress: String?
        get() = preferences.getString(WATCH_ADDRESS_KEY, null)

    fun saveWatchAddress(address: String) {
        preferences.edit().putString(WATCH_ADDRESS_KEY, address).apply()
    }

    fun clearWatchAddress() {
        preferences.edit().remove(WATCH_ADDRESS_KEY).apply()
    }

    private companion object {
        const val PREFERENCES = "hsp_ble_companion"
        const val WATCH_ADDRESS_KEY = "watch_ble_address"
    }
}
