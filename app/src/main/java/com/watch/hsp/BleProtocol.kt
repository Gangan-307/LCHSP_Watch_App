package com.watch.hsp

import java.text.Normalizer
import java.util.UUID
import java.util.TimeZone
import kotlin.math.roundToInt

/** UUIDs and packets shared by the watch GATT server and the phone client. */
object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("2d6a5000-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val CONTROL_UUID: UUID = UUID.fromString("2d6a5001-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val STATE_UUID: UUID = UUID.fromString("2d6a5002-8d5c-4f6a-a9b2-1c0c9e7a1000")
    /** Writable App-to-watch packets: time, location, city, and weather snapshots. */
    val SYNC_UUID: UUID = UUID.fromString("2d6a5003-8d5c-4f6a-a9b2-1c0c9e7a1000")
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

    const val SYNC_COMMAND_TIME: Byte = 0x21
    const val SYNC_COMMAND_LOCATION: Byte = 0x22
    const val SYNC_COMMAND_WEATHER: Byte = 0x23
    const val SYNC_COMMAND_CITY: Byte = 0x24

    private const val MAX_SYNC_PACKET_BYTES = 20

    fun packet(command: Byte, sequence: Byte): ByteArray = byteArrayOf(command, sequence)

    /** UTC seconds u32 LE followed by the phone's UTC offset in minutes i16 LE. */
    fun buildTimeSyncPacket(nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val packet = ByteArray(7)
        val utcSeconds = nowMillis / 1_000L
        val offsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60_000

        require(utcSeconds in 0..0xffff_ffffL)
        require(offsetMinutes in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        packet[0] = SYNC_COMMAND_TIME
        writeUInt32Le(packet, 1, utcSeconds)
        writeUInt16Le(packet, 5, offsetMinutes)
        return packet
    }

    /** Latitude/longitude in E7 and horizontal accuracy in metres. */
    fun buildLocationSyncPacket(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float
    ): ByteArray {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)

        val packet = ByteArray(11)
        packet[0] = SYNC_COMMAND_LOCATION
        writeUInt32Le(packet, 1, (latitude * 10_000_000.0).roundToInt().toLong() and 0xffff_ffffL)
        writeUInt32Le(packet, 5, (longitude * 10_000_000.0).roundToInt().toLong() and 0xffff_ffffL)
        writeUInt16Le(packet, 9, accuracyMeters.roundToInt().coerceIn(0, 0xffff))
        return packet
    }

    /** WMO weather code, temperatures in 0.1 C, humidity, and update UTC seconds. */
    fun buildWeatherSyncPacket(
        wmoCode: Int,
        currentCelsius: Double,
        highCelsius: Double,
        lowCelsius: Double,
        humidityPercent: Int,
        updatedAtMillis: Long = System.currentTimeMillis()
    ): ByteArray {
        require(wmoCode in 0..0xff)
        require(humidityPercent in 0..100)
        val updatedSeconds = updatedAtMillis / 1_000L
        require(updatedSeconds in 0..0xffff_ffffL)

        val packet = ByteArray(13)
        packet[0] = SYNC_COMMAND_WEATHER
        packet[1] = wmoCode.toByte()
        writeUInt16Le(packet, 2, celsiusToDeci(currentCelsius))
        writeUInt16Le(packet, 4, celsiusToDeci(highCelsius))
        writeUInt16Le(packet, 6, celsiusToDeci(lowCelsius))
        packet[8] = humidityPercent.toByte()
        writeUInt32Le(packet, 9, updatedSeconds)
        return packet
    }

    /** Printable ASCII city name, limited to one 20-byte GATT write including the command. */
    fun buildCitySyncPacket(city: String): ByteArray {
        val displayCity = Normalizer.normalize(city.trim(), Normalizer.Form.NFKD)
            .map { character -> if (character.isWhitespace()) ' ' else character }
            .filter { character -> character.code in 0x20..0x7e }
            .joinToString("")
            .trim()
        require(displayCity.any { character -> character.isLetterOrDigit() })

        val maxPayloadBytes = MAX_SYNC_PACKET_BYTES - 1
        val payload = displayCity.take(maxPayloadBytes).toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(payload.size + 1)
        packet[0] = SYNC_COMMAND_CITY
        payload.copyInto(packet, destinationOffset = 1)
        return packet
    }

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

    private fun celsiusToDeci(celsius: Double): Int =
        (celsius * 10.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    private fun writeUInt16Le(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value and 0xff).toByte()
        packet[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun writeUInt32Le(packet: ByteArray, offset: Int, value: Long) {
        packet[offset] = (value and 0xff).toByte()
        packet[offset + 1] = ((value ushr 8) and 0xff).toByte()
        packet[offset + 2] = ((value ushr 16) and 0xff).toByte()
        packet[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
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
