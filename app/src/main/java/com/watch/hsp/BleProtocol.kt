package com.watch.hsp

import java.util.UUID

/** UUIDs and packets shared by the watch GATT server and the phone client. */
object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("2d6a5000-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val CONTROL_UUID: UUID = UUID.fromString("2d6a5001-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val STATE_UUID: UUID = UUID.fromString("2d6a5002-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val WATCH_COMMAND_UUID: UUID = UUID.fromString("2d6a5003-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val DEVICE_STATUS_UUID: UUID = UUID.fromString("2d6a5004-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val STATUS_SCHEMA_VERSION = 1
    private const val STATUS_FLAG_BLE_ENABLED = 1 shl 0
    private const val STATUS_FLAG_COMPANION_CONNECTED = 1 shl 1
    private const val STATUS_FLAG_BATTERY_VALID = 1 shl 2
    private const val STATUS_FLAG_CHARGING = 1 shl 3
    private const val STATUS_FLAG_ACTIVITY_VALID = 1 shl 4
    private const val STATUS_HEADER_LENGTH = 4
    private const val STATUS_ACTIVITY_LENGTH = 10

    /** STATE notifications: ask the Android phone to start or stop ringing. */
    const val PHONE_COMMAND_FIND_START: Byte = 0x01
    const val PHONE_COMMAND_FIND_STOP: Byte = 0x02

    /** CONTROL writes: ask the watch to start or stop vibrating. */
    const val WATCH_COMMAND_FIND_START: Byte = 0x11
    const val WATCH_COMMAND_FIND_STOP: Byte = 0x12

    fun packet(command: Byte, sequence: Byte): ByteArray = byteArrayOf(command, sequence)

    /**
     * Device status packet: schema, flags, battery, firmware version length, version bytes,
     * followed by optional activity data: steps u32 LE, kcal u16 LE, distance meters u32 LE.
     */
    fun decodeDeviceStatus(packet: ByteArray): WatchStatusPacket? {
        if (packet.size < STATUS_HEADER_LENGTH ||
            (packet[0].toInt() and 0xff) != STATUS_SCHEMA_VERSION) {
            return null
        }

        val flags = packet[1].toInt() and 0xff
        val versionLength = packet[3].toInt() and 0xff
        if (versionLength > packet.size - STATUS_HEADER_LENGTH) return null

        val batteryValid = (flags and STATUS_FLAG_BATTERY_VALID) != 0
        val rawBattery = packet[2].toInt() and 0xff
        val batteryPercent = rawBattery.takeIf { batteryValid && it <= 100 }
        val firmwareVersion = String(
            packet,
            STATUS_HEADER_LENGTH,
            versionLength,
            Charsets.US_ASCII
        ).let { version -> if (version.isBlank()) null else version }
        val activityOffset = STATUS_HEADER_LENGTH + versionLength
        val activityValid = (flags and STATUS_FLAG_ACTIVITY_VALID) != 0 &&
            packet.size >= activityOffset + STATUS_ACTIVITY_LENGTH
        val steps = if (activityValid) readUInt32Le(packet, activityOffset) else null
        val caloriesKcal = if (activityValid) {
            (packet[activityOffset + 4].toInt() and 0xff) or
                ((packet[activityOffset + 5].toInt() and 0xff) shl 8)
        } else {
            null
        }
        val distanceMeters = if (activityValid) readUInt32Le(packet, activityOffset + 6) else null

        return WatchStatusPacket(
            bleEnabled = (flags and STATUS_FLAG_BLE_ENABLED) != 0,
            companionConnected = (flags and STATUS_FLAG_COMPANION_CONNECTED) != 0,
            batteryValid = batteryValid && batteryPercent != null,
            batteryPercent = batteryPercent,
            charging = (flags and STATUS_FLAG_CHARGING) != 0,
            firmwareVersion = firmwareVersion,
            activityValid = activityValid,
            steps = steps,
            caloriesKcal = caloriesKcal,
            distanceMeters = distanceMeters
        )
    }

    private fun readUInt32Le(packet: ByteArray, offset: Int): Long =
        (packet[offset].toLong() and 0xff) or
            ((packet[offset + 1].toLong() and 0xff) shl 8) or
            ((packet[offset + 2].toLong() and 0xff) shl 16) or
            ((packet[offset + 3].toLong() and 0xff) shl 24)
}

data class WatchStatusPacket(
    val bleEnabled: Boolean,
    val companionConnected: Boolean,
    val batteryValid: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    val firmwareVersion: String?,
    val activityValid: Boolean,
    val steps: Long?,
    val caloriesKcal: Int?,
    val distanceMeters: Long?
)
