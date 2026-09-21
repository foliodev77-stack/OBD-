package net.khanclouds.obdscanner

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val client = Elm327Client()
    private var devices: List<BluetoothDevice> = emptyList()
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var home: View
    private lateinit var diagnostic: View
    private lateinit var spinner: Spinner
    private lateinit var vinBtn: Button
    private lateinit var vehicleCard: View
    private lateinit var panel: View
    private lateinit var vehicleInfo: TextView
    private lateinit var results: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        status=findViewById(R.id.status); title=findViewById(R.id.title); back=findViewById(R.id.backBtn)
        home=findViewById(R.id.homePage); diagnostic=findViewById(R.id.diagnosticPage)
        spinner=findViewById(R.id.deviceSpinner); vinBtn=findViewById(R.id.vinBtn)
        vehicleCard=findViewById(R.id.vehicleCard); panel=findViewById(R.id.diagnosticPanel)
        vehicleInfo=findViewById(R.id.vehicleInfo); results=findViewById(R.id.results)

        back.setOnClickListener { showHome() }
        findViewById<Button>(R.id.diagnoseTile).setOnClickListener { showDiagnostic("Diagnostic intelligent") }
        findViewById<Button>(R.id.obdTile).setOnClickListener { showDiagnostic("OBD II") }
        findViewById<Button>(R.id.dataTile).setOnClickListener { showDiagnostic("Données en direct"); if(panel.visibility==View.VISIBLE) loadLive() }
        findViewById<Button>(R.id.batteryTile).setOnClickListener { showDiagnostic("Tension de la batterie"); runCommand("ATRV","TENSION BATTERIE") }
        findViewById<Button>(R.id.readinessTile).setOnClickListener { showDiagnostic("I/M Readiness"); runCommand("0101","I/M READINESS • DONNÉES ECU") }
        findViewById<Button>(R.id.resetTile).setOnClickListener { showDiagnostic("Réinitialiser"); results.text="Connectez le véhicule puis utilisez « Effacer les codes moteur »." }
        findViewById<Button>(R.id.reportTile).setOnClickListener { showDiagnostic("Rapport d’inspection"); results.text="Connectez le véhicule, scannez le VIN puis lancez « Rapport / Codes défaut »." }
        findViewById<Button>(R.id.updateTile).setOnClickListener { Toast.makeText(this,"Application à jour",Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.settingsTile).setOnClickListener { Toast.makeText(this,"Paramètres OBD • Bluetooth",Toast.LENGTH_SHORT).show() }

        if(!perm()) requestBluetooth() else refresh()

        findViewById<Button>(R.id.connectBtn).setOnClickListener {
            refresh()
            if(devices.isEmpty()){ status.text="● Aucun adaptateur appairé"; results.text="Appairez OBDII/ELM327 dans les réglages Bluetooth."; return@setOnClickListener }
            status.text="● Connexion…"
            thread {
                try {
                    client.connect(devices[spinner.selectedItemPosition.coerceAtLeast(0)])
                    runOnUiThread { status.text="● Connecté"; status.setTextColor(0xFF27C985.toInt()); vinBtn.isEnabled=true; results.text="Connexion réussie. Lancez maintenant VIN Scan." }
                } catch(e:Exception) { runOnUiThread { status.text="● Échec connexion"; results.text="Connexion impossible : ${e.message}" } }
            }
        }

        vinBtn.setOnClickListener {
            results.text="VIN Scan en cours…\nLecture directe du calculateur."
            thread {
                try {
                    val identity=client.vehicleIdentity()
                    runOnUiThread { vehicleInfo.text=identity; vehicleCard.visibility=View.VISIBLE; panel.visibility=View.VISIBLE; results.text="Identification terminée. Sélectionnez un test."; title.text="VIN Scan" }
                } catch(e:Exception) { runOnUiThread { results.text="VIN non lu : ${e.message}\nContact moteur sur ON, puis réessayez." } }
            }
        }

        findViewById<Button>(R.id.quickBtn).setOnClickListener {
            title.text="Test rapide"; results.text="Analyse du calculateur moteur…"
            thread { try { val r=client.quickTest(); runOnUiThread { results.text=r } } catch(e:Exception){ runOnUiThread{results.text="Erreur diagnostic : ${e.message}"} } }
        }
        findViewById<Button>(R.id.scanBtn).setOnClickListener {
            title.text="Rapport d’inspection"; results.text="Lecture des codes défaut…"
            thread { try { val r=client.fullScan(); runOnUiThread { results.text=r } } catch(e:Exception){ runOnUiThread{results.text="Erreur rapport : ${e.message}"} } }
        }
        findViewById<Button>(R.id.liveBtn).setOnClickListener { title.text="Données en direct"; loadLive() }
        findViewById<Button>(R.id.healthBtn).setOnClickListener { title.text="Santé OBD"; loadLive("SANTÉ OBD") }
        findViewById<Button>(R.id.infoBtn).setOnClickListener {
            title.text="Informations ECU"
            thread { try { val r=client.vehicleIdentity(); runOnUiThread{results.text=r} } catch(e:Exception){runOnUiThread{results.text="Erreur : ${e.message}"}} }
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener {
            title.text="Effacer les codes"
            thread { try { val r=client.command("04"); runOnUiThread{results.text="Commande ECU envoyée : $r\nCoupez puis remettez le contact avant un nouveau scan."} } catch(e:Exception){runOnUiThread{results.text="Effacement impossible : ${e.message}"}} }
        }
    }

    private fun showDiagnostic(name:String){ home.visibility=View.GONE; diagnostic.visibility=View.VISIBLE; back.visibility=View.VISIBLE; title.text=name; refresh() }
    private fun showHome(){ diagnostic.visibility=View.GONE; home.visibility=View.VISIBLE; back.visibility=View.GONE; title.text="OBD GARAGE" }
    private fun loadLive(prefix:String="DONNÉES EN DIRECT"){ results.text="Lecture des paramètres ECU…"; thread{try{val r=client.liveData();runOnUiThread{results.text="$prefix\n\n$r"}}catch(e:Exception){runOnUiThread{results.text="Données indisponibles : ${e.message}"}}} }
    private fun runCommand(cmd:String,label:String){ thread{try{val r=client.command(cmd);runOnUiThread{results.text="$label\n\n$r"}}catch(e:Exception){runOnUiThread{results.text="Connectez d’abord l’adaptateur OBD.\n${e.message}"}}} }
    private fun perm() = Build.VERSION.SDK_INT<31 || ActivityCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun requestBluetooth(){ if(Build.VERSION.SDK_INT>=31) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN),7) }
    private fun refresh(){
        if(!perm()) return
        try {
            val a=getSystemService(BluetoothManager::class.java).adapter ?: return
            devices=a.bondedDevices.toList().sortedByDescending{(it.name?:"").contains("OBD",true)||(it.name?:"").contains("ELM",true)}
            spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,devices.map{"${it.name?:"OBD"}  ${it.address}"})
        } catch(_:SecurityException){}
    }
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==7&&g.isNotEmpty()&&g.all{it==PackageManager.PERMISSION_GRANTED})refresh()}
    override fun onResume(){super.onResume();if(::status.isInitialized&&perm())refresh()}
    override fun onBackPressed(){if(::diagnostic.isInitialized&&diagnostic.visibility==View.VISIBLE)showHome()else super.onBackPressed()}
    override fun onDestroy(){client.close();super.onDestroy()}
}