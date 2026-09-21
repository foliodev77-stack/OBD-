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
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun connect(device: BluetoothDevice) {
        close()
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        var first: Exception? = null
        try {
            socket=device.createRfcommSocketToServiceRecord(spp).also { it.connect() }
        } catch(e:Exception) {
            first=e
            try {
                val method=device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                socket=(method.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch(e2:Exception) {
                throw Exception("RFCOMM failed. Standard: ${first.message}; fallback: ${e2.cause?.message ?: e2.message}")
            }
        }
        reader=BufferedReader(InputStreamReader(socket!!.inputStream))
        command("ATZ")
        Thread.sleep(700)
        listOf("ATE0","ATL0","ATS0","ATH0","ATSP0").forEach { command(it) }
    }

    fun command(cmd:String):String {
        val s=socket ?: error("Not connected")
        s.outputStream.write((cmd+"\r").toByteArray(Charsets.US_ASCII))
        s.outputStream.flush()
        val out=StringBuilder()
        val deadline=System.currentTimeMillis()+5000
        while(System.currentTimeMillis()<deadline) {
            if(reader!!.ready()) {
                val c=reader!!.read()
                if(c<0 || c.toChar()=='>') break
                out.append(c.toChar())
            } else Thread.sleep(20)
        }
        if(out.isEmpty()) throw Exception("No response from ELM327")
        return out.toString().trim()
    }
    fun close(){ try{socket?.close()}catch(_:Exception){}; socket=null; reader=null }
}
