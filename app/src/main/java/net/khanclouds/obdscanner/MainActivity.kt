package net.khanclouds.obdscanner
import android.Manifest
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

class MainActivity:AppCompatActivity(){
 private val client=Elm327Client(); private var devices:List<BluetoothDevice> = emptyList()
 private lateinit var status:TextView;private lateinit var spinner:Spinner;private lateinit var vinBtn:Button;private lateinit var panel:View;private lateinit var card:View;private lateinit var info:TextView;private lateinit var results:TextView
 override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main)
  status=findViewById(R.id.status);spinner=findViewById(R.id.deviceSpinner);vinBtn=findViewById(R.id.vinBtn);panel=findViewById(R.id.diagnosticPanel);card=findViewById(R.id.vehicleCard);info=findViewById(R.id.vehicleInfo);results=findViewById(R.id.results)
  if(!perm())ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN),7)else refresh()
  findViewById<Button>(R.id.connectBtn).setOnClickListener{refresh();if(devices.isEmpty()){status.text="No paired OBD adapter found";return@setOnClickListener};status.text="Connecting to adapter..."
   thread{try{client.connect(devices[spinner.selectedItemPosition.coerceAtLeast(0)]);runOnUiThread{status.text="Connected • Step 2: scan VIN";vinBtn.isEnabled=true}}catch(e:Exception){runOnUiThread{status.text="Connection failed: ${e.message}"}}}}
  vinBtn.setOnClickListener{status.text="Reading VIN from ECU...";results.text="Identifying vehicle..."
   thread{try{val v=client.vehicleIdentity();runOnUiThread{info.text=v;card.visibility=View.VISIBLE;panel.visibility=View.VISIBLE;status.text="Vehicle identified • Diagnostics unlocked";results.text="Choose Full Diagnostic Scan or Live Engine Data."}}catch(e:Exception){runOnUiThread{results.text="VIN scan failed: ${e.message}\nKeep ignition ON and try again."}}}}
  findViewById<Button>(R.id.quickBtn).setOnClickListener{results.text="Test rapide en cours...";thread{try{val r=client.quickTest();runOnUiThread{results.text=r}}catch(e:Exception){runOnUiThread{results.text="Diagnostic error: ${e.message}"}}}}\n  findViewById<Button>(R.id.scanBtn).setOnClickListener{results.text="Scanning ECU, DTCs, readiness and freeze-frame...";thread{try{val r=client.fullScan();runOnUiThread{results.text=r}}catch(e:Exception){runOnUiThread{results.text="Diagnostic error: ${e.message}"}}}}
  findViewById<Button>(R.id.liveBtn).setOnClickListener{thread{try{val r=client.liveData();runOnUiThread{results.text=r}}catch(e:Exception){runOnUiThread{results.text="Live data error: ${e.message}"}}}}
  findViewById<Button>(R.id.clearBtn).setOnClickListener{thread{try{val r=client.command("04");runOnUiThread{results.text="Clear request sent: $r\nSwitch ignition OFF/ON, then scan again."}}catch(e:Exception){runOnUiThread{results.text="Clear error: ${e.message}"}}}}
 }
 private fun perm()=Build.VERSION.SDK_INT<31||ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
 private fun refresh(){if(!perm())return;val a=getSystemService(BluetoothManager::class.java).adapter?:return;devices=a.bondedDevices.toList().sortedByDescending{(it.name?:"").contains("OBD",true)||(it.name?:"").contains("ELM",true)};spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,devices.map{"${it.name?:"OBD"}  ${it.address}"})}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.isNotEmpty()&&g.all{it==PackageManager.PERMISSION_GRANTED})refresh()}
 override fun onResume(){super.onResume();if(::status.isInitialized&&perm())refresh()}
 override fun onDestroy(){client.close();super.onDestroy()}
}