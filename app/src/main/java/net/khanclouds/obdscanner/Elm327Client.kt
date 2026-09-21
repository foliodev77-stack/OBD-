package net.khanclouds.obdscanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlinx.coroutines.runBlocking
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import com.github.eltonvs.obd.command.control.VINCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.SpeedCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.engine.MassAirFlowCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand

class Elm327Client {
    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var obd: ObdDeviceConnection? = null

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
        obd = ObdDeviceConnection(socket!!.inputStream, socket!!.outputStream)
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

    private fun cleanHex(s: String): String =
        s.uppercase().replace(Regex("[^0-9A-F]"), "")

    private fun hexBytes(response: String): List<Int> {
        val compact = cleanHex(response)
        return compact.chunked(2).mapNotNull { pair ->
            if (pair.length == 2) pair.toIntOrNull(16) else null
        }
    }

    private fun pid(pid: String): List<Int> {
        val raw = command("01$pid")
        val normalized = raw.uppercase()
            .replace("SEARCHING...", " ")
            .replace("NO DATA", " ")
            .replace("STOPPED", " ")
            .replace("?", " ")
        val bytes = Regex("[0-9A-F]{2}").findAll(normalized).map { it.value.toInt(16) }.toList()
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x41 && bytes[i + 1] == pid.toInt(16)) return bytes.drop(i + 2)
        }
        return emptyList()
    }

    private fun mode09Records(raw: String, pid: String): List<Pair<Int,String>> {
        val records = mutableListOf<Pair<Int,String>>()
        Regex("(?:(?:^|\\s)(\\d+):\\s*)?49\\s*0$pid\\s*([0-9A-F]{2})\\s*((?:[0-9A-F]{2}\\s*)+)", RegexOption.IGNORE_CASE)
            .findAll(raw.replace("\\r"," ").replace("\\n"," ")).forEach { m ->
                val seq=m.groupValues[2].toInt(16)
                val data=m.groupValues[3].replace(Regex("\\s+"),"")
                records.add(seq to data)
            }
        if(records.isNotEmpty()) return records.sortedBy { it.first }
        val compact=cleanHex(raw)
        val marker="490$pid"
        var pos=0
        while(true){
            val i=compact.indexOf(marker,pos); if(i<0) break
            val after=compact.substring(i+marker.length)
            if(after.length>=2){
                val seq=after.substring(0,2).toIntOrNull(16) ?: 0
                records.add(seq to after.drop(2).take(32))
            }
            pos=i+marker.length
        }
        return records.sortedBy { it.first }
    }

    fun readVin(): String {
        val connection = obd ?: throw Exception("Not connected")
        return runBlocking {
            val response = connection.run(VINCommand(), useCache = false, maxRetries = 8)
            val vin = response.value.trim().uppercase()
            if (Regex("^[A-HJ-NPR-Z0-9]{17}$").matches(vin)) vin
            else "Non disponible (ECU VIN OBD-II invalide/non fourni)"
        }
    }

    fun readEcuName(): String {
        val raw=command("090A",8000)
        val records=mode09Records(raw,"A")
        val text=records.joinToString("") { it.second }.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .filter { it in 32..126 }.map { it.toChar() }.joinToString("").trim()
        return text.ifBlank { "Non disponible" }
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
                codes.add(prefix + ((a shr 4) and 3).toString() +
                    (a and 15).toString(16).uppercase() +
                    ((b shr 4) and 15).toString(16).uppercase() +
                    (b and 15).toString(16).uppercase())
            }
            i += 2
        }
        return if (codes.isEmpty()) "No stored powertrain DTCs" else codes.distinct().joinToString(", ")
    }

    fun liveData(): String {
        val connection = obd ?: throw Exception("Not connected")
        return runBlocking {
            suspend fun metric(label: String, block: suspend () -> String): String =
                try { label + ": " + block() } catch (e: Exception) { label + ": N/A (" + (e.message ?: "unsupported") + ")" }
            val lines = listOf(
                metric("RPM") { val r=connection.run(RPMCommand(), maxRetries=5); r.value + " " + r.unit },
                metric("Vitesse") { val r=connection.run(SpeedCommand(), maxRetries=5); r.value + " " + r.unit },
                metric("Liquide refroidissement") { val r=connection.run(EngineCoolantTemperatureCommand(), maxRetries=5); r.value + " " + r.unit },
                metric("Papillon") { val r=connection.run(ThrottlePositionCommand(), maxRetries=5); r.value + " " + r.unit },
                metric("MAF") { val r=connection.run(MassAirFlowCommand(), maxRetries=5); r.value + " " + r.unit },
                metric("Air admission") { val r=connection.run(AirIntakeTemperatureCommand(), maxRetries=5); r.value + " " + r.unit },
                metric("Carburant") { val r=connection.run(FuelLevelCommand(), maxRetries=5); r.value + " " + r.unit }
            )
            "DONNÉES ECU RÉELLES — kotlin-obd-api\n" + lines.joinToString("\n")
        }
    }

    fun vinDiagnosticCapture(): String {
        val out = StringBuilder()
        fun capture(cmd: String, timeout: Long = 8000) {
            out.append("\n> ").append(cmd).append("\n")
            out.append(runCatching { command(cmd, timeout) }.getOrElse { "ERROR: " + (it.message ?: "unknown") }).append("\n")
        }
        capture("ATDP")
        capture("ATDPN")
        capture("0900")
        capture("0902", 12000)
        capture("090A", 10000)
        capture("ATH1")
        capture("0902", 12000)
        capture("ATH0")
        return out.toString().trim()
    }

    fun vehicleIdentity(): String {
        val vin = readVin()
        val protocol = runCatching { command("ATDP") }.getOrDefault("Unknown")
        val ecu = runCatching { readEcuName() }.getOrDefault("Non disponible")
        return "VIN: $vin\nProtocole OBD: $protocol\nNom ECU / Mode 09: $ecu\n\nCes informations viennent directement du véhicule. Aucune marque/modèle n’est inventée."
    }

    private fun explain(code: String): String {
        val known = mapOf("P0300" to "Rates allumage multiples", "P0301" to "Rate cylindre 1", "P0302" to "Rate cylindre 2", "P0401" to "Debit EGR insuffisant", "P0420" to "Efficacite catalyseur sous le seuil", "P0171" to "Melange trop pauvre banc 1", "P0101" to "Debitmetre air plage/performance", "P0562" to "Tension systeme trop faible")
        return known[code] ?: when(code.firstOrNull()) { 'P' -> "Defaut moteur/transmission"; 'C' -> "Defaut chassis"; 'B' -> "Defaut carrosserie"; 'U' -> "Defaut communication reseau"; else -> "Code diagnostic" }
    }

    fun quickTest(): String {
        val raw = readDtcs()
        val codes = Regex("[PCBU][0-3][0-9A-F]{3}").findAll(raw).map { it.value }.toList()
        val engine = if(codes.isEmpty()) "normal - aucun code defaut" else "ANOMALIE (" + codes.size + ")\n" + codes.joinToString("\n") { it + " - " + explain(it) }
        return "RAPPORT INSPECTION\n\n01 Electronique du moteur\n" + engine + "\n\n02 Electronique boite de vitesses\nNon accessible via OBD-II generique\n\n03 Electronique freins / ABS\nNon accessible via OBD-II generique\n\n15 Airbag / SRS\nNon accessible via OBD-II generique\n\nLes systemes non accessibles necessitent les protocoles constructeur."
    }

    fun fullScan(): String {
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
        obd = null
    }
}
