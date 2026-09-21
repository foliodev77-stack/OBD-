package net.khanclouds.obdscanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class Elm327Client {
    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null

    fun connect(device: BluetoothDevice) {
        close()
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        try {
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket!!.connect()
        } catch (first: Exception) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket = method.invoke(device, 1) as BluetoothSocket
                socket!!.connect()
            } catch (second: Exception) {
                throw Exception("Bluetooth connection failed: " + (second.cause?.message ?: second.message))
            }
        }
        reader = BufferedReader(InputStreamReader(socket!!.inputStream))
        command("ATZ", 7000)
        Thread.sleep(500)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) command(cmd)
    }

    fun command(cmd: String, timeout: Long = 5000): String {
        val active = socket ?: throw Exception("Not connected")
        active.outputStream.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        active.outputStream.flush()
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (reader!!.ready()) {
                val ch = reader!!.read()
                if (ch < 0 || ch.toChar() == '>') break
                output.append(ch.toChar())
            } else Thread.sleep(15)
        }
        val result = output.toString().replace("\r", " ").replace("\n", " ").trim()
        if (result.isBlank()) throw Exception("No response for $cmd")
        return result
    }

    private fun hexBytes(response: String): List<Int> {
        val clean = response.replace(Regex("[^0-9A-Fa-f]"), "")
        return clean.chunked(2).mapNotNull { it.toIntOrNull(16) }
    }

    private fun pid(pid: String): List<Int> {
        val bytes = hexBytes(command("01$pid"))
        val marker = listOf(0x41, pid.toInt(16))
        val index = bytes.windowed(2).indexOf(marker)
        return if (index >= 0) bytes.drop(index + 2) else emptyList()
    }

    fun readVin(): String {
        val bytes = hexBytes(command("0902", 8000))
        val text = bytes.filter { it in 32..126 }.map { it.toChar() }.joinToString("")
        return Regex("[A-HJ-NPR-Z0-9]{17}").find(text)?.value ?: "Not returned by ECU"
    }

    private fun readDtcs(): String {
        val bytes = hexBytes(command("03"))
        val start = bytes.indexOf(0x43)
        if (start < 0) return "No standard DTC response"
        val data = bytes.drop(start + 1)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            val a = data[i]
            val b = data[i + 1]
            if (a != 0 || b != 0) {
                val prefix = arrayOf("P", "C", "B", "U")[(a shr 6) and 3]
                codes.add(prefix + ((a shr 4) and 3) + (a and 15) + ((b shr 4) and 15) + (b and 15))
            }
            i += 2
        }
        return if (codes.isEmpty()) "No stored powertrain DTCs" else codes.joinToString(", ")
    }

    fun liveData(): String {
        val rpm = pid("0C")
        val speed = pid("0D")
        val coolant = pid("05")
        val load = pid("04")
        val throttle = pid("11")
        val maf = pid("10")
        val voltage = runCatching { command("ATRV") }.getOrDefault("N/A")
        val rpmText = if (rpm.size >= 2) ((rpm[0] * 256 + rpm[1]) / 4).toString() else "N/A"
        val speedText = speed.firstOrNull()?.toString() ?: "N/A"
        val coolantText = coolant.firstOrNull()?.let { (it - 40).toString() } ?: "N/A"
        val loadText = load.firstOrNull()?.let { (it * 100 / 255).toString() } ?: "N/A"
        val throttleText = throttle.firstOrNull()?.let { (it * 100 / 255).toString() } ?: "N/A"
        val mafText = if (maf.size >= 2) ((maf[0] * 256 + maf[1]) / 100.0).toString() else "N/A"
        return "LIVE ENGINE DATA\n" +
            "RPM: $rpmText\n" +
            "Vehicle speed: $speedText km/h\n" +
            "Coolant: $coolantText C\n" +
            "Engine load: $loadText %\n" +
            "Throttle: $throttleText %\n" +
            "MAF: $mafText g/s\n" +
            "Adapter voltage: $voltage"
    }

    fun vehicleIdentity(): String {\n        val vin = readVin()\n        val protocol = runCatching { command("ATDP") }.getOrDefault("Unknown")\n        val ecu = runCatching { command("090A") }.getOrDefault("Not available")\n        return "VIN: $vin\\nOBD protocol: $protocol\\nECU name/raw: $ecu\\n\\nVehicle-specific make/model/year decoding can be added from a VIN database; diagnostics below use data reported directly by the ECU."\n    }\n\n    fun fullScan(): String {
        val vin = runCatching { readVin() }.getOrDefault("Not available")
        val dtcs = runCatching { readDtcs() }.getOrDefault("Not available")
        val protocol = runCatching { command("ATDP") }.getOrDefault("Unknown")
        val live = runCatching { liveData() }.getOrDefault("Live data unavailable")
        val readiness = runCatching { command("0101") }.getOrDefault("N/A")
        val freeze = runCatching { command("0202") }.getOrDefault("N/A")
        return "FULL OBD-II REPORT\n\n" +
            "Vehicle VIN: $vin\n" +
            "Protocol: $protocol\n\n" +
            "Stored diagnostic trouble codes:\n$dtcs\n\n" +
            live + "\n\n" +
            "Readiness / supported monitors (raw):\n$readiness\n\n" +
            "Freeze frame DTC (raw):\n$freeze\n\n" +
            "Generic ELM327 reads standard OBD-II powertrain/emissions data. Manufacturer modules may need brand-specific diagnostics."
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        reader = null
    }
}
