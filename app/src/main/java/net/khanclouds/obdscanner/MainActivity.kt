package net.khanclouds.obdscanner
import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

class MainActivity:AppCompatActivity(){
 private val client=Elm327Client(); private var devices:List<BluetoothDevice> = emptyList()
 private lateinit var status:TextView; private lateinit var spinner:Spinner; private lateinit var scan:Button; private lateinit var live:Button; private lateinit var clear:Button; private lateinit var results:TextView
 override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main)
  status=findViewById(R.id.status);spinner=findViewById(R.id.deviceSpinner);scan=findViewById(R.id.scanBtn);live=findViewById(R.id.liveBtn);clear=findViewById(R.id.clearBtn);results=findViewById(R.id.results)
  if(!perm()) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN),7) else refresh()
  findViewById<Button>(R.id.connectBtn).setOnClickListener{refresh();if(devices.isEmpty()){status.text="No paired OBD found";return@setOnClickListener};status.text="Connecting..."
   thread{try{client.connect(devices[spinner.selectedItemPosition.coerceAtLeast(0)]);runOnUiThread{status.text="Connected to OBDII";scan.isEnabled=true;live.isEnabled=true;clear.isEnabled=true}}catch(e:Exception){runOnUiThread{status.text="Connection failed: ${e.message}"}}}}
  scan.setOnClickListener{results.text="Full scan in progress...";thread{try{val report=client.fullScan();runOnUiThread{results.text=report}}catch(e:Exception){runOnUiThread{results.text="Scan error: ${e.message}"}}}}
  live.setOnClickListener{thread{try{val s=client.liveData();runOnUiThread{results.text=s}}catch(e:Exception){runOnUiThread{results.text="Live data error: ${e.message}"}}}}
  clear.setOnClickListener{thread{try{val r=client.command("04");runOnUiThread{results.text="Clear DTC response:\n$r\n\nTurn ignition off/on and rescan."}}catch(e:Exception){runOnUiThread{results.text="Clear error: ${e.message}"}}}}
 }
 private fun perm()=Build.VERSION.SDK_INT<31||ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
 private fun refresh(){if(!perm())return;val a=getSystemService(BluetoothManager::class.java).adapter?:return;devices=a.bondedDevices.toList().sortedByDescending{(it.name?:"").contains("OBD",true)||(it.name?:"").contains("ELM",true)};spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,devices.map{"${it.name?:"OBD"}  ${it.address}"})}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.all{it==PackageManager.PERMISSION_GRANTED})refresh()}
 override fun onResume(){super.onResume();if(::status.isInitialized&&perm())refresh()}
 override fun onDestroy(){client.close();super.onDestroy()}
}