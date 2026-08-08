package com.watch.hsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.zip.CRC32

class BleProtocolTest {
    @Test
    fun cityPacketIncludesOpcodeAndAsciiName() {
        val packet = BleProtocol.buildCitySyncPacket("Shenzhen")

        assertEquals(BleProtocol.SYNC_COMMAND_CITY, packet[0])
        assertArrayEquals("Shenzhen".toByteArray(Charsets.US_ASCII), packet.copyOfRange(1, packet.size))
    }

    @Test
    fun cityPacketTruncatesPayloadToNineteenBytes() {
        val packet = BleProtocol.buildCitySyncPacket("12345678901234567890-extra")

        assertEquals(20, packet.size)
        assertEquals("1234567890123456789", packet.copyOfRange(1, packet.size).toString(Charsets.US_ASCII))
    }

    @Test
    fun cityPacketCleansCharactersUnsupportedByWatchFont() {
        val packet = BleProtocol.buildCitySyncPacket("\u0160h\u0113nzh\u00e8n \u6df1\u5733")

        assertEquals("Shenzhen", packet.copyOfRange(1, packet.size).toString(Charsets.US_ASCII))
        assertThrows(IllegalArgumentException::class.java) {
            BleProtocol.buildCitySyncPacket("\u6df1\u5733")
        }
    }

    @Test
    fun notificationPacketsPreserveLongUtf8TextTimeAndOffsets() {
        val postedAtMillis = 1_786_120_500_000L
        val packets = BleProtocol.buildNotificationSyncPackets(
            id = 0x3456,
            app = BleProtocol.NOTIFICATION_APP_WECHAT,
            title = "Chinese title 作曲消息".repeat(4),
            body = "Long Chinese notification 通知正文。".repeat(40),
            postedAtMillis = postedAtMillis
        )

        val begin = packets.first()
        val titleLength = readUInt16Le(begin, 4)
        val bodyLength = readUInt16Le(begin, 6)
        val expectedTime = Calendar.getInstance().apply { timeInMillis = postedAtMillis }
        assertEquals(10, begin.size)
        assertEquals(expectedTime.get(Calendar.HOUR_OF_DAY), begin[8].toInt() and 0xff)
        assertEquals(expectedTime.get(Calendar.MINUTE), begin[9].toInt() and 0xff)
        assertTrue(bodyLength > 255)

        val payload = ByteArray(titleLength + bodyLength)
        var expectedOffset = 0
        packets.drop(1).forEach { packet ->
            assertTrue(packet.size <= 20)
            assertEquals(expectedOffset, readUInt16Le(packet, 3))
            packet.copyInto(payload, expectedOffset, 5, packet.size)
            expectedOffset += packet.size - 5
        }
        assertEquals(payload.size, expectedOffset)
        assertFalse(String(payload, 0, titleLength, Charsets.UTF_8).contains('\uFFFD'))
        assertFalse(String(payload, titleLength, bodyLength, Charsets.UTF_8).contains('\uFFFD'))
    }

    @Test
    fun lyricPacketsPreserveChineseUtf8AndOrderedOffsets() {
        val packets = BleProtocol.buildLyricSyncPackets(
            generation = 0x2345,
            lyric = "风吹过山海，作曲的人仍在歌唱。".repeat(12)
        )
        val expectedLength = readUInt16Le(packets.first(), 3)
        val payload = ByteArray(expectedLength)
        var expectedOffset = 0

        packets.drop(1).forEach { packet ->
            assertTrue(packet.size <= 20)
            assertEquals(0x2345, readUInt16Le(packet, 1))
            assertEquals(expectedOffset, readUInt16Le(packet, 3))
            packet.copyInto(payload, expectedOffset, 5, packet.size)
            expectedOffset += packet.size - 5
        }

        assertEquals(expectedLength, expectedOffset)
        assertFalse(payload.toString(Charsets.UTF_8).contains('\uFFFD'))
    }

    @Test
    fun coverPacketsFitDefaultMtuAndReconstructWithCrc() {
        val jpeg = ByteArray(4_321) { index -> (index * 31).toByte() }.apply {
            this[0] = 0xff.toByte()
            this[1] = 0xd8.toByte()
            this[lastIndex - 1] = 0xff.toByte()
            this[lastIndex] = 0xd9.toByte()
        }
        val packets = BleProtocol.buildCoverSyncPackets(77, jpeg)
        val begin = packets.first()
        val payload = ByteArray(readUInt32Le(begin, 3).toInt())
        var expectedOffset = 0

        packets.drop(1).forEach { packet ->
            assertTrue(packet.size <= 20)
            assertEquals(expectedOffset.toLong(), readUInt32Le(packet, 3))
            packet.copyInto(payload, expectedOffset, 7, packet.size)
            expectedOffset += packet.size - 7
        }

        assertArrayEquals(jpeg, payload)
        assertEquals(CRC32().apply { update(jpeg) }.value, readUInt32Le(begin, 7))
    }

    private fun readUInt16Le(packet: ByteArray, offset: Int): Int =
        (packet[offset].toInt() and 0xff) or
            ((packet[offset + 1].toInt() and 0xff) shl 8)

    private fun readUInt32Le(packet: ByteArray, offset: Int): Long =
        (packet[offset].toLong() and 0xff) or
            ((packet[offset + 1].toLong() and 0xff) shl 8) or
            ((packet[offset + 2].toLong() and 0xff) shl 16) or
            ((packet[offset + 3].toLong() and 0xff) shl 24)
}
