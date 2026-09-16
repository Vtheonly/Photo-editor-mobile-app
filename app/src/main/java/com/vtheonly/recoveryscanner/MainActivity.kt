package com.vtheonly.recoveryscanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vtheonly.recoveryscanner.scanner.ArtifactMiner
import com.vtheonly.recoveryscanner.scanner.DeepContentScanner
import com.vtheonly.recoveryscanner.scanner.DirectStorageScanner
import com.vtheonly.recoveryscanner.scanner.MediaStoreTrashScanner
import com.vtheonly.recoveryscanner.scanner.RecoveryQuality
import com.vtheonly.recoveryscanner.scanner.RecoveryWriter
import com.vtheonly.recoveryscanner.scanner.SamsungArtifactScanner
import com.vtheonly.recoveryscanner.scanner.ScanResult
import com.vtheonly.recoveryscanner.scanner.StorageScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var resultContainer: LinearLayout
    private lateinit var access: Button
    private lateinit var chooseFolder: Button
    private lateinit var deepScan: Button
    private lateinit var artifactScan: Button
    private lateinit var scanMedia: Button
    private lateinit var recover: Button
    private val selected = linkedMapOf<String, ScanResult>()
    private var results = emptyList<ScanResult>()
    private var pendingFolderAction = ACTION_SCAN_FOLDER

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }
    override fun onResume() { super.onResume(); if (::access.isInitialized) updateAccessButton() }

    private fun buildUi() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,28,32,24)}
        root.addView(TextView(this).apply{text="Recovery Scanner";textSize=28f},match())
        root.addView(TextView(this).apply{text="Forensic-style no-root media recovery over storage the user explicitly grants to the app.";textSize=14f;setPadding(0,8,0,20)},match())
        access=Button(this).apply{setOnClickListener{requestAllFilesAccess()}};root.addView(access,match())
        chooseFolder=Button(this).apply{text="Normal scan: selected folder";setOnClickListener{chooseScanFolder(false)}}
        deepScan=Button(this).apply{text="Maximum scan: every accessible byte";setOnClickListener{chooseScanFolder(true)}}
        artifactScan=Button(this).apply{text="Artifact mine: thumbnails + hidden media + Samsung";setOnClickListener{chooseScanFolder(ACTION_ARTIFACTS)}}
        scanMedia=Button(this).apply{text="MediaStore: normal + trash + pending";setOnClickListener{requestMediaPermissionAndScan()}}
        recover=Button(this).apply{text="Recover selected";isEnabled=false;setOnClickListener{chooseRecoveryFolder()}}
        root.addView(chooseFolder,match());root.addView(deepScan,match());root.addView(artifactScan,match());root.addView(scanMedia,match());root.addView(recover,match())
        status=TextView(this).apply{text="The scanner never reads deleted physical UFS/eMMC blocks; it mines every user-accessible source instead.";textSize=14f;setPadding(0,18,0,14)};root.addView(status,match())
        val scroll=ScrollView(this);resultContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(resultContainer);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);updateAccessButton()
    }

    private fun updateAccessButton(){val granted=Build.VERSION.SDK_INT<30||Environment.isExternalStorageManager();access.text=if(granted)"All Files Access: GRANTED — scan /storage/emulated/0" else "Grant All Files Access — unlock deepest shared-storage scan"}
    private fun requestAllFilesAccess(){if(Build.VERSION.SDK_INT>=30){try{startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")))}catch(_:Throwable){startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))}}}
    private fun chooseScanFolder(mode:Boolean){pendingFolderAction=if(mode)ACTION_DEEP_SCAN else ACTION_SCAN_FOLDER;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_TREE)}
    private fun chooseScanFolder(action:Int){pendingFolderAction=action;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_TREE)}
    private fun chooseRecoveryFolder(){pendingFolderAction=ACTION_RECOVER;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_TREE)}
    private fun requestMediaPermissionAndScan(){val permissions=if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE);val missing=permissions.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(missing.isEmpty())scanMediaStore()else ActivityCompat.requestPermissions(this,missing.toTypedArray(),REQUEST_PERMISSION)}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_PERMISSION&&grantResults.any{it==PackageManager.PERMISSION_GRANTED})scanMediaStore()else if(requestCode==REQUEST_PERMISSION)Toast.makeText(this,"Media permissions are required for indexed scanning.",Toast.LENGTH_LONG).show()}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_TREE||resultCode!=RESULT_OK)return;val uri=data?.data?:return;try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException){};when(pendingFolderAction){ACTION_SCAN_FOLDER->scanFolder(uri);ACTION_DEEP_SCAN->deepScanFolder(uri);ACTION_ARTIFACTS->artifactMine(uri);ACTION_RECOVER->recoverSelected(uri)}}

    private fun scanFolder(uri:Uri){setBusy(true,"Scanning selected folder...");scope.launch{val scanner=StorageScanner(this@MainActivity);results=scanner.scanTree(uri){p->status.text="Scanned ${p.files} files (${formatBytes(p.bytes)})"};renderResults();setBusy(false,"Normal scan found ${results.size} candidates.")}}
    private fun deepScanFolder(uri:Uri){setBusy(true,"Maximum scan: scanning every accessible byte...");scope.launch{val scanner=DeepContentScanner(this@MainActivity);results=scanner.scanTree(uri){p->status.text="Carved ${p.files} files • ${formatBytes(p.bytes)} • ${p.candidates} candidates"};renderResults();val valid=results.count{it.quality==RecoveryQuality.VALID||it.quality==RecoveryQuality.LIKELY_RECOVERABLE};setBusy(false,"Maximum scan complete: ${results.size} candidates, $valid recoverable.")}}
    private fun scanMediaStore(){setBusy(true,"Scanning indexed media, including trashed/pending entries...");scope.launch(Dispatchers.IO){val normal=StorageScanner(this@MainActivity).scanMediaStore();val trash=MediaStoreTrashScanner(this@MainActivity).scan();withContext(Dispatchers.Main){results=(normal+trash).distinctBy{"${it.sourceUri}:${it.offset}:${it.detectedType}"};renderResults();setBusy(false,"MediaStore scan found ${results.size} normal/trashed/pending candidates.")}}}

    private fun artifactMine(uri:Uri){setBusy(true,"Mining hidden folders, extensionless files, thumbdata and Samsung embedded media...");scope.launch(Dispatchers.IO){val miner=ArtifactMiner(this@MainActivity);val artifacts=miner.scanTree(uri){name->status.text="Mining $name"};val samsung=SamsungArtifactScanner(this@MainActivity);val converted=mutableListOf<ScanResult>();for(a in artifacts){converted+=ScanResult(a.displayName,a.uri.toString(),a.type,a.type.extension(),a.displayName.substringAfterLast('.',""),a.length,99,a.displayName.substringAfterLast('.',"").lowercase()!=a.type.extension(),a.offset>0,a.offset,a.length,a.quality);val exif=miner.extractExifThumbnail(a.uri);if(exif!=null&&exif.size>2048)status.text="Found embedded EXIF thumbnail in ${a.displayName}";val zipImages=if(a.displayName.endsWith(".sdocx",true))miner.listEmbeddedZipImages(a.uri)else emptyList();if(zipImages.isNotEmpty())status.text="Found ${zipImages.size} embedded images in ${a.displayName}";if(a.type==com.vtheonly.recoveryscanner.scanner.MediaType.JPEG||a.type==com.vtheonly.recoveryscanner.scanner.MediaType.HEIC){for(e in samsung.scan(a.uri,a.displayName)){converted+=ScanResult("${a.displayName}-${e.key}",e.sourceUri.toString(),e.type,"mp4",a.displayName.substringAfterLast('.',""),e.length,94,true,true,e.offset,e.length,e.quality)}}};if(Environment.getExternalStorageDirectory().exists()&&DirectStorageScanner(this@MainActivity).isAvailable()){for(file in DirectStorageScanner(this@MainActivity).scan{p->status.text="Direct storage: $p"}){val detected=FileInputStream(file).use{SignatureDetectorCompat.detect(it,file.name)};if(detected!=null)converted+=detected.copy(sourceUri=Uri.fromFile(file).toString())}};withContext(Dispatchers.Main){results=converted.distinctBy{"${it.sourceUri}:${it.offset}:${it.detectedType}"};renderResults();setBusy(false,"Artifact mining complete: ${results.size} recoverable candidates.")}}}

    private fun renderResults(){resultContainer.removeAllViews();selected.clear();recover.isEnabled=false;if(results.isEmpty()){resultContainer.addView(TextView(this).apply{text="No recognizable media found.";textSize=16f});return};results.forEachIndexed{index,result->resultContainer.addView(CheckBox(this).apply{text=buildString{append(result.sourceName);append("\n${result.detectedExtension.uppercase()} • ${formatBytes(result.sizeBytes)} • ${result.quality.label()} • ${result.confidence}%");if(result.recoveredLength>0)append("\nExact carved length: ${formatBytes(result.recoveredLength)}");if(result.offset>0)append(" • offset ${result.offset}");if(result.isExtensionMismatch)append("\nFilename extension differs from detected content")};gravity=Gravity.CENTER_VERTICAL;setPadding(0,12,0,12);isEnabled=result.quality!=RecoveryQuality.SIGNATURE_ONLY;setOnCheckedChangeListener{_,checked->val key="${result.sourceUri}:${result.offset}:${result.detectedType}";if(checked)selected[key]=result else selected.remove(key);recover.isEnabled=selected.isNotEmpty()}});if(index!=results.lastIndex)resultContainer.addView(TextView(this).apply{setPadding(0,0,0,1);setBackgroundColor(0xFFE0E0E0.toInt())},matchHeight())}}
    private fun recoverSelected(destination:Uri){val items=selected.values.toList();setBusy(true,"Recovering ${items.size} candidates...");scope.launch(Dispatchers.IO){val writer=RecoveryWriter(this@MainActivity);var success=0;items.forEach{if(writer.recover(it,destination)!=null)success++};withContext(Dispatchers.Main){setBusy(false,"Recovered $success/${items.size} validated candidates.");selected.clear();recover.isEnabled=false}}}
    private fun setBusy(busy:Boolean,message:String){access.isEnabled=!busy;chooseFolder.isEnabled=!busy;deepScan.isEnabled=!busy;artifactScan.isEnabled=!busy;scanMedia.isEnabled=!busy;recover.isEnabled=!busy&&selected.isNotEmpty();status.text=message}
    private fun formatBytes(v:Long)=when{v<1024->"$v B";v<1024*1024->"%.1f KB".format(v/1024.0);v<1024*1024*1024->"%.1f MB".format(v/(1024.0*1024.0));else->"%.2f GB".format(v/(1024.0*1024.0*1024.0))}
    private fun RecoveryQuality.label()=when(this){RecoveryQuality.VALID->"VALID";RecoveryQuality.LIKELY_RECOVERABLE->"LIKELY RECOVERABLE";RecoveryQuality.PARTIAL_OR_CORRUPT->"PARTIAL/CORRUPT";RecoveryQuality.SIGNATURE_ONLY->"SIGNATURE ONLY"}
    private fun match()=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT);private fun matchHeight()=LinearLayout.LayoutParams(-1,1)
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object{private const val REQUEST_TREE=1001;private const val REQUEST_PERMISSION=1002;private const val ACTION_SCAN_FOLDER=1;private const val ACTION_DEEP_SCAN=2;private const val ACTION_RECOVER=3;private const val ACTION_ARTIFACTS=4}
}

private object SignatureDetectorCompat {
    fun detect(input:java.io.InputStream,name:String):ScanResult?{val d=com.vtheonly.recoveryscanner.scanner.SignatureRegistry.detect(input,name)?:return null;val size=try{File(name).length()}catch(_:Throwable){0L};return ScanResult(name,"",d.type,d.extension,name.substringAfterLast('.',""),size,d.confidence,!name.lowercase().endsWith(".${d.extension}"),d.offset>0,d.offset,size,RecoveryQuality.LIKELY_RECOVERABLE)}
}

private fun com.vtheonly.recoveryscanner.scanner.MediaType.extension()=when(this){com.vtheonly.recoveryscanner.scanner.MediaType.JPEG->"jpg";com.vtheonly.recoveryscanner.scanner.MediaType.PNG->"png";com.vtheonly.recoveryscanner.scanner.MediaType.GIF->"gif";com.vtheonly.recoveryscanner.scanner.MediaType.WEBP->"webp";com.vtheonly.recoveryscanner.scanner.MediaType.BMP->"bmp";com.vtheonly.recoveryscanner.scanner.MediaType.HEIC->"heic";com.vtheonly.recoveryscanner.scanner.MediaType.MP4->"mp4";com.vtheonly.recoveryscanner.scanner.MediaType.MOV->"mov";com.vtheonly.recoveryscanner.scanner.MediaType.THREE_GP->"3gp";com.vtheonly.recoveryscanner.scanner.MediaType.UNKNOWN->"bin"}
