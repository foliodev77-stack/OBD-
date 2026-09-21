package net.khanclouds.obdscanner

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val client = Elm327Client()
    private var devices: List<BluetoothDevice> = emptyList()
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var scan: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status=findViewById(R.id.status)
        spinner=findViewById(R.id.deviceSpinner)
        scan=findViewById(R.id.scanBtn)
        val results=findViewById<TextView>(R.id.results)
        val connect=findViewById<Button>(R.id.connectBtn)

        if (!hasBluetoothPermission()) {
            ActivityCompat.requestPermissions(this,
                if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
        } else refreshDevices()

        connect.setOnClickListener {
            refreshDevices()
            if(devices.isEmpty()){ status.text="No paired Bluetooth devices found. Pair OBDII first."; return@setOnClickListener }
            val pos=spinner.selectedItemPosition.coerceAtLeast(0)
            status.text="Connecting to ${safeName(devices[pos])}..."
            thread {
                try {
                    client.connect(devices[pos])
                    runOnUiThread { status.text="Connected to ${safeName(devices[pos])}"; scan.isEnabled=true }
                } catch(e:Exception) {
                    runOnUiThread { status.text="Connection failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}" }
                }
            }
        }

        scan.setOnClickListener {
            results.text="Scanning..."
            thread {
                try {
                    val vin=client.command("0902")
                    val dtc=client.command("03")
                    val rpm=client.command("010C")
                    val coolant=client.command("0105")
                    runOnUiThread { results.text="VIN raw:\n$vin\n\nDTC raw:\n$dtc\n\nRPM raw:\n$rpm\n\nCoolant raw:\n$coolant" }
                } catch(e:Exception){ runOnUiThread { results.text="Scan error: ${e.javaClass.simpleName}: ${e.message}" } }
            }
        }
    }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < 31 || ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun safeName(d: BluetoothDevice): String =
        try { if(hasBluetoothPermission()) d.name ?: "OBD" else "OBD" } catch(_:SecurityException) { "OBD" }

    private fun refreshDevices() {
        if(!hasBluetoothPermission()){ status.text="Bluetooth permission required"; return }
        try {
            val adapter=getSystemService(BluetoothManager::class.java).adapter
            if(adapter == null){ status.text="Bluetooth not supported"; return }
            if(!adapter.isEnabled){ status.text="Turn Bluetooth ON"; return }
            devices=adapter.bondedDevices.toList().sortedByDescending {
                val n=safeName(it).uppercase()
                n.contains("OBD") || n.contains("ELM")
            }
            spinner.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                devices.map { "${safeName(it)}  ${it.address}" })
            status.text=if(devices.isEmpty()) "No paired device found" else "Found ${devices.size} paired device(s). Select OBDII."
        } catch(e:SecurityException){ status.text="Bluetooth permission denied" }
    }

    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode==7 && grantResults.isNotEmpty() && grantResults.all { it==PackageManager.PERMISSION_GRANTED }) refreshDevices()
        else if(requestCode==7) status.text="Bluetooth permission denied. Allow Nearby devices in app settings."
    }

    override fun onResume(){ super.onResume(); if(::status.isInitialized && hasBluetoothPermission()) refreshDevices() }
    override fun onDestroy(){ client.close(); super.onDestroy() }
}
