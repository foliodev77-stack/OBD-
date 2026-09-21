package net.khanclouds.obdscanner
import android.bluetooth.*
import java.io.*
import java.util.UUID
class Elm327Client{
 private var socket:BluetoothSocket?=null;private var reader:BufferedReader?=null
 fun connect(d:BluetoothDevice){close();BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery();val u=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
  try{socket=d.createRfcommSocketToServiceRecord(u).also{it.connect()}}catch(e:Exception){val m=d.javaClass.getMethod("createRfcommSocket",Int::class.javaPrimitiveType);socket=(m.invoke(d,1) as BluetoothSocket).also{it.connect()}}
  reader=BufferedReader(InputStreamReader(socket!!.inputStream));command("ATZ",7000);Thread.sleep(500);listOf("ATE0","ATL0","ATS0","ATH0","ATSP0").forEach{command(it)}
 }
 fun command(c:String,timeout:Long=5000):String{val s=socket?:error("Not connected");s.outputStream.write((c+"\r").toByteArray());s.outputStream.flush();val o=StringBuilder();val end=System.currentTimeMillis()+timeout
  while(System.currentTimeMillis()<end){if(reader!!.ready()){val x=reader!!.read();if(x<0||x.toChar()=='>')break;o.append(x.toChar())}else Thread.sleep(15)}
  val z=o.toString().replace("\r"," ").replace("\n"," ").trim();if(z.isBlank())throw Exception("No response for $c");return z
 }
 private fun hexBytes(r:String):List<Int>{val clean=r.replace(Regex("[^0-9A-Fa-f]"),"");return clean.chunked(2).mapNotNull{it.toIntOrNull(16)}}
 private fun value(pid:String):List<Int>{val b=hexBytes(command("01$pid"));val marker=listOf(0x41,pid.toInt(16));val i=b.windowed(2).indexOf(marker);return if(i>=0)b.drop(i+2) else emptyList()}
 private fun vin():String{val b=hexBytes(command("0902",8000));val chars=b.filter{it in 32..126}.map{it.toChar()}.joinToString("");return Regex("[A-HJ-NPR-Z0-9]{17}").find(chars)?.value?:"Not returned by ECU"}
 private fun dtcs():String{val b=hexBytes(command("03"));val i=b.indexOf(0x43);if(i<0)return "No standard DTC response";val x=b.drop(i+1);val out=mutableListOf<String>();for(k in x.indices step 2){if(k+1>=x.size)break;val a=x[k];val c=x[k+1];if(a==0&&c==0)continue;val pre=arrayOf("P","C","B","U")[(a shr 6) and 3];out.add("$pre${(a shr 4) and 3}${a and 15}${(c shr 4) and 15}${c and 15}")};return if(out.isEmpty())"No stored powertrain DTCs" else out.joinToString(", ")}
 fun liveData():String{fun one(p:String)=value(p);val rpm=one("0C");val speed=one("0D");val cool=one("05");val load=one("04");val throttle=one("11");val maf=one("10");val volt=runCatching{command("ATRV")}.getOrDefault("N/A")
  return """LIVE ENGINE DATA
RPM: ${if(rpm.size>=2)(rpm[0]*256+rpm[1])/4 else "N/A"}
Vehicle speed: ${speed.firstOrNull()?:"N/A"} km/h
Coolant: ${cool.firstOrNull()?.minus(40)?:"N/A"} °C
Engine load: ${load.firstOrNull()?.times(100)?.div(255)?:"N/A"} %
Throttle: ${throttle.firstOrNull()?.times(100)?.div(255)?:"N/A"} %
MAF: ${if(maf.size>=2)(maf[0]*256+maf[1])/100.0 else "N/A"} g/s
Adapter voltage: $volt"""
 }
 fun fullScan():String{val vin=runCatching{vin()}.getOrDefault("Not available");val dtc=runCatching{dtcs()}.getOrDefault("Not available");val proto=runCatching{command("ATDP")}.getOrDefault("Unknown");val live=liveData()
  return """FULL OBD-II REPORT

Vehicle VIN: $vin
Protocol: $proto

Stored diagnostic trouble codes:
$dtc

$live

Readiness / supported monitors (raw):
${runCatching{command("0101")}.getOrDefault("N/A")}

Freeze frame DTC (raw):
${runCatching{command("0202")}.getOrDefault("N/A")}

Note: Generic ELM327 OBD-II exposes emissions/powertrain data. ABS, airbag, body, service reset and manufacturer-specific modules require vehicle-specific diagnostic protocols."""
 }
 fun close(){try{socket?.close()}catch(_:Exception){};socket=null;reader=null}
}