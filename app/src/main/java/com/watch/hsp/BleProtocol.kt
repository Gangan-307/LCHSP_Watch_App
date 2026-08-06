package com.watch.hsp

import java.util.UUID

/** UUIDs and packets shared by the watch GATT server and the phone client. */
object BleProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("2d6a5000-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val CONTROL_UUID: UUID = UUID.fromString("2d6a5001-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val STATE_UUID: UUID = UUID.fromString("2d6a5002-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val WATCH_COMMAND_UUID: UUID = UUID.fromString("2d6a5003-8d5c-4f6a-a9b2-1c0c9e7a1000")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** STATE notifications: ask the Android phone to start or stop ringing. */
    const val PHONE_COMMAND_FIND_START: Byte = 0x01
    const val PHONE_COMMAND_FIND_STOP: Byte = 0x02

    /** CONTROL writes: ask the watch to start or stop vibrating. */
    const val WATCH_COMMAND_FIND_START: Byte = 0x11
    const val WATCH_COMMAND_FIND_STOP: Byte = 0x12

    fun packet(command: Byte, sequence: Byte): ByteArray = byteArrayOf(command, sequence)
}
