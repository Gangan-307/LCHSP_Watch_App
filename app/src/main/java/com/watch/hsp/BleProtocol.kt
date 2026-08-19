package com.watch.hsp

import java.text.Normalizer
import java.util.Calendar
import java.util.UUID
import java.util.TimeZone
import java.util.zip.CRC32
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
    const val PHONE_COMMAND_NOTIFICATION_CLEAR: Byte = 0x03
    const val PHONE_COMMAND_NOTIFICATION_DELETE: Byte = 0x04
    const val PHONE_COMMAND_PHOTO_REQUEST: Byte = 0x05

    /** CONTROL writes: ask the watch to start or stop vibrating. */
    const val WATCH_COMMAND_FIND_START: Byte = 0x11
    const val WATCH_COMMAND_FIND_STOP: Byte = 0x12

    const val SYNC_COMMAND_TIME: Byte = 0x21
    const val SYNC_COMMAND_LOCATION: Byte = 0x22
    const val SYNC_COMMAND_WEATHER: Byte = 0x23
    const val SYNC_COMMAND_CITY: Byte = 0x24
    const val SYNC_COMMAND_NOTIFICATION_BEGIN: Byte = 0x31
    const val SYNC_COMMAND_NOTIFICATION_DATA: Byte = 0x32
    const val SYNC_COMMAND_LYRIC_BEGIN: Byte = 0x41
    const val SYNC_COMMAND_LYRIC_DATA: Byte = 0x42
    const val SYNC_COMMAND_COVER_BEGIN: Byte = 0x43
    const val SYNC_COMMAND_COVER_DATA: Byte = 0x44
    const val SYNC_COMMAND_PHOTO_BEGIN: Byte = 0x45
    const val SYNC_COMMAND_PHOTO_DATA: Byte = 0x46
    const val SYNC_COMMAND_PHOTO_STATUS: Byte = 0x47

    const val PHOTO_STATUS_PERMISSION_REQUIRED: Byte = 0x01
    const val PHOTO_STATUS_NOT_FOUND: Byte = 0x02
    const val PHOTO_STATUS_ERROR: Byte = 0x03

    const val NOTIFICATION_APP_SMS = 1
    const val NOTIFICATION_APP_WECHAT = 2
    const val NOTIFICATION_APP_QQ = 3

    /** Safe ATT value bounds for the watch SYNC characteristic. */
    const val DEFAULT_SYNC_PACKET_BYTES = 20
    const val MAX_SYNC_PACKET_BYTES = 244
    private const val NOTIFICATION_BEGIN_LENGTH = 10
    private const val NOTIFICATION_DATA_HEADER_LENGTH = 5
    private const val NOTIFICATION_TITLE_MAX_BYTES = 96
    private const val NOTIFICATION_BODY_MAX_BYTES = 512
    private const val NOTIFICATION_DATA_MAX_BYTES =
        DEFAULT_SYNC_PACKET_BYTES - NOTIFICATION_DATA_HEADER_LENGTH
    private const val LYRIC_BEGIN_LENGTH = 5
    private const val LYRIC_DATA_HEADER_LENGTH = 5
    private const val LYRIC_MAX_BYTES = 192
    private const val LYRIC_DATA_MAX_BYTES = DEFAULT_SYNC_PACKET_BYTES - LYRIC_DATA_HEADER_LENGTH
    private const val COVER_BEGIN_LENGTH = 11
    private const val COVER_DATA_HEADER_LENGTH = 7
    const val COVER_MAX_BYTES = 8 * 1024
    const val PHOTO_MAX_BYTES = 16 * 1024

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

        val maxPayloadBytes = DEFAULT_SYNC_PACKET_BYTES - 1
        val payload = displayCity.take(maxPayloadBytes).toByteArray(Charsets.US_ASCII)
        val packet = ByteArray(payload.size + 1)
        packet[0] = SYNC_COMMAND_CITY
        payload.copyInto(packet, destinationOffset = 1)
        return packet
    }

    /**
     * Build a compact, ordered notification transfer. The watch supports only
     * 20-byte writes before MTU negotiation, so UTF-8 content is segmented.
     */
    fun buildNotificationSyncPackets(
        id: Int,
        app: Int,
        title: String,
        body: String,
        postedAtMillis: Long
    ): List<ByteArray> {
        require(id in 1..0xffff)
        require(app in NOTIFICATION_APP_SMS..NOTIFICATION_APP_QQ)

        val titleBytes = truncateUtf8(title, NOTIFICATION_TITLE_MAX_BYTES)
        val bodyBytes = truncateUtf8(body, NOTIFICATION_BODY_MAX_BYTES)
        val payload = titleBytes + bodyBytes
        val packets = ArrayList<ByteArray>()
        val begin = ByteArray(NOTIFICATION_BEGIN_LENGTH)
        begin[0] = SYNC_COMMAND_NOTIFICATION_BEGIN
        begin[1] = app.toByte()
        writeUInt16Le(begin, 2, id)
        writeUInt16Le(begin, 4, titleBytes.size)
        writeUInt16Le(begin, 6, bodyBytes.size)
        val postedAt = Calendar.getInstance().apply {
            timeInMillis = postedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        begin[8] = postedAt.get(Calendar.HOUR_OF_DAY).toByte()
        begin[9] = postedAt.get(Calendar.MINUTE).toByte()
        packets += begin

        var offset = 0
        while (offset < payload.size) {
            val length = minOf(NOTIFICATION_DATA_MAX_BYTES, payload.size - offset)
            val data = ByteArray(NOTIFICATION_DATA_HEADER_LENGTH + length)
            data[0] = SYNC_COMMAND_NOTIFICATION_DATA
            writeUInt16Le(data, 1, id)
            writeUInt16Le(data, 3, offset)
            payload.copyInto(data, NOTIFICATION_DATA_HEADER_LENGTH, offset, offset + length)
            packets += data
            offset += length
        }
        return packets
    }

    /** Send only the current lyric line; the watch does not retain the whole song. */
    fun buildLyricSyncPackets(generation: Int, lyric: String): List<ByteArray> {
        require(generation in 1..0xffff)
        val payload = truncateUtf8(lyric, LYRIC_MAX_BYTES)
        val packets = ArrayList<ByteArray>()
        val begin = ByteArray(LYRIC_BEGIN_LENGTH)
        begin[0] = SYNC_COMMAND_LYRIC_BEGIN
        writeUInt16Le(begin, 1, generation)
        writeUInt16Le(begin, 3, payload.size)
        packets += begin

        var offset = 0
        while (offset < payload.size) {
            val length = minOf(LYRIC_DATA_MAX_BYTES, payload.size - offset)
            val data = ByteArray(LYRIC_DATA_HEADER_LENGTH + length)
            data[0] = SYNC_COMMAND_LYRIC_DATA
            writeUInt16Le(data, 1, generation)
            writeUInt16Le(data, 3, offset)
            payload.copyInto(data, LYRIC_DATA_HEADER_LENGTH, offset, offset + length)
            packets += data
            offset += length
        }
        return packets
    }

    /**
     * Transfer a small JPEG cover with an end-to-end CRC32 integrity check.
     *
     * Only cover data uses the negotiated MTU. The other SYNC commands retain
     * their 20-byte layout so older firmware remains compatible.
     */
    fun buildCoverSyncPackets(
        generation: Int,
        jpeg: ByteArray,
        packetLimit: Int = DEFAULT_SYNC_PACKET_BYTES
    ): List<ByteArray> {
        require(generation in 1..0xffff)
        require(jpeg.isNotEmpty() && jpeg.size <= COVER_MAX_BYTES)
        require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val safePacketLimit = packetLimit.coerceIn(
            DEFAULT_SYNC_PACKET_BYTES,
            MAX_SYNC_PACKET_BYTES
        )

        val packets = ArrayList<ByteArray>()
        val begin = ByteArray(COVER_BEGIN_LENGTH)
        begin[0] = SYNC_COMMAND_COVER_BEGIN
        writeUInt16Le(begin, 1, generation)
        writeUInt32Le(begin, 3, jpeg.size.toLong())
        writeUInt32Le(begin, 7, CRC32().apply { update(jpeg) }.value)
        packets += begin

        var offset = 0
        while (offset < jpeg.size) {
            val length = minOf(
                safePacketLimit - COVER_DATA_HEADER_LENGTH,
                jpeg.size - offset
            )
            val data = ByteArray(COVER_DATA_HEADER_LENGTH + length)
            data[0] = SYNC_COMMAND_COVER_DATA
            writeUInt16Le(data, 1, generation)
            writeUInt32Le(data, 3, offset.toLong())
            jpeg.copyInto(data, COVER_DATA_HEADER_LENGTH, offset, offset + length)
            packets += data
            offset += length
        }
        return packets
    }

    fun buildPhotoSyncPackets(
        generation: Int,
        jpeg: ByteArray,
        packetLimit: Int = DEFAULT_SYNC_PACKET_BYTES
    ): List<ByteArray> {
        require(generation in 1..0xffff)
        require(jpeg.size in 4..PHOTO_MAX_BYTES)
        require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val safePacketLimit = packetLimit.coerceIn(
            DEFAULT_SYNC_PACKET_BYTES,
            MAX_SYNC_PACKET_BYTES
        )
        val packets = ArrayList<ByteArray>()
        val begin = ByteArray(COVER_BEGIN_LENGTH)
        begin[0] = SYNC_COMMAND_PHOTO_BEGIN
        writeUInt16Le(begin, 1, generation)
        writeUInt32Le(begin, 3, jpeg.size.toLong())
        writeUInt32Le(begin, 7, CRC32().apply { update(jpeg) }.value)
        packets += begin

        var offset = 0
        while (offset < jpeg.size) {
            val length = minOf(
                safePacketLimit - COVER_DATA_HEADER_LENGTH,
                jpeg.size - offset
            )
            val data = ByteArray(COVER_DATA_HEADER_LENGTH + length)
            data[0] = SYNC_COMMAND_PHOTO_DATA
            writeUInt16Le(data, 1, generation)
            writeUInt32Le(data, 3, offset.toLong())
            jpeg.copyInto(data, COVER_DATA_HEADER_LENGTH, offset, offset + length)
            packets += data
            offset += length
        }
        return packets
    }

    fun buildPhotoStatusPacket(status: Byte): ByteArray {
        require(status == PHOTO_STATUS_PERMISSION_REQUIRED ||
            status == PHOTO_STATUS_NOT_FOUND || status == PHOTO_STATUS_ERROR)
        return byteArrayOf(SYNC_COMMAND_PHOTO_STATUS, status)
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

    /** Do not split a UTF-8 sequence when reducing a notification for BLE. */
    private fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
        val bytes = value.replace(Regex("\\s+"), " ").trim().toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes

        var length = maxBytes
        while (length > 0 && (bytes[length].toInt() and 0xc0) == 0x80) length--
        return bytes.copyOf(length)
    }

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
