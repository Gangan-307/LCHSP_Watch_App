package com.watch.hsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
