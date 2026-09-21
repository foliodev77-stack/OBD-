package net.khanclouds.obdscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val client = Elm327Client()
    private lateinit var devices: List<android.bluetooth.BluetoothDevice>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status=findViewById<TextView>(R.id.status)
        val results=findViewById<TextView>(R.id.results)
        val spinner=findViewById<Spinner>(R.id.deviceSpinner)
        val connect=findViewById<Button>(R.id.connectBtn)
        val scan=findViewById<Button>(R.id.scanBtn)

        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 7)
            status.text="Allow Bluetooth, then reopen the app."
            return
        }

        val adapter=getSystemService(BluetoothManager::class.java).adapter
        devices=adapter?.bondedDevices?.toList() ?: emptyList()
        spinner.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.name ?: "OBD"}  ${it.address}" })

        connect.setOnClickListener {
            if(devices.isEmpty()){ status.text="Pair your ELM327 in Android Bluetooth settings first."; return@setOnClickListener }
            status.text="Connecting..."
            thread {
                try {
                    client.connect(devices[spinner.selectedItemPosition])
                    runOnUiThread { status.text="Connected"; scan.isEnabled=true }
                } catch(e:Exception) { runOnUiThread { status.text="Connection failed: ${e.message}" } }
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
                } catch(e:Exception){ runOnUiThread { results.text="Scan error: ${e.message}" } }
            }
        }
    }
    override fun onDestroy(){ client.close(); super.onDestroy() }
}
