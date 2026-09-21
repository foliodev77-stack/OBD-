package net.khanclouds.obdscanner

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class Elm327Client {
    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null

    fun connect(device: BluetoothDevice) {
        socket = device.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        ).also { it.connect() }
        reader = BufferedReader(InputStreamReader(socket!!.inputStream))
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command(it) }
    }

    fun command(cmd: String): String {
        val s = socket ?: error("Not connected")
        s.outputStream.write((cmd + "\r").toByteArray())
        s.outputStream.flush()
        val out = StringBuilder()
        while (true) {
            val c = reader!!.read()
            if (c < 0 || c.toChar() == '>') break
            out.append(c.toChar())
        }
        return out.toString().trim()
    }

    fun close() { socket?.close() }
}
