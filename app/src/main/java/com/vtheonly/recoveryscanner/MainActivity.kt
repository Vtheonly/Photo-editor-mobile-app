package com.vtheonly.recoveryscanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import com.vtheonly.recoveryscanner.scanner.DeepContentScanner
import com.vtheonly.recoveryscanner.scanner.RecoveryQuality
import com.vtheonly.recoveryscanner.scanner.RecoveryWriter
import com.vtheonly.recoveryscanner.scanner.ScanResult
import com.vtheonly.recoveryscanner.scanner.StorageScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var resultContainer: LinearLayout
    private lateinit var chooseFolder: Button
    private lateinit var deepScan: Button
    private lateinit var scanMedia: Button
    private lateinit var recover: Button
    private val selected = linkedMapOf<String, ScanResult>()
    private var results = emptyList<ScanResult>()
    private var pendingFolderAction = ACTION_SCAN_FOLDER

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,28,32,24) }
        root.addView(TextView(this).apply { text="Recovery Scanner"; textSize=28f }, match())
        root.addView(TextView(this).apply { text="Maximum no-root media carving over storage Android exposes to the app."; textSize=14f; setPadding(0,8,0,20) }, match())
        chooseFolder=Button(this).apply{text="Normal scan: selected folder";setOnClickListener{chooseScanFolder(false)}}
        deepScan=Button(this).apply{text="Maximum scan: carve every accessible byte";setOnClickListener{chooseScanFolder(true)}}
        scanMedia=Button(this).apply{text="Scan MediaStore images + videos";setOnClickListener{requestMediaPermissionAndScan()}}
        recover=Button(this).apply{text="Recover selected";isEnabled=false;setOnClickListener{chooseRecoveryFolder()}}
        root.addView(chooseFolder,match());root.addView(deepScan,match());root.addView(scanMedia,match());root.addView(recover,match())
        status=TextView(this).apply{text="Maximum scan detects signatures anywhere, structurally parses candidates, calculates exact boundaries, and refuses signature-only recovery.";textSize=14f;setPadding(0,18,0,14)}
        root.addView(status,match())
        val scroll=ScrollView(this);resultContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(resultContainer);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
    }

    private fun chooseScanFolder(deep:Boolean){pendingFolderAction=if(deep)ACTION_DEEP_SCAN else ACTION_SCAN_FOLDER;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_TREE)}
    private fun chooseRecoveryFolder(){pendingFolderAction=ACTION_RECOVER;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},REQUEST_TREE)}
    private fun requestMediaPermissionAndScan(){val permissions=if(android.os.Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE);val missing=permissions.filter{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED};if(missing.isEmpty())scanMediaStore()else ActivityCompat.requestPermissions(this,missing.toTypedArray(),REQUEST_PERMISSION)}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQUEST_PERMISSION&&grantResults.any{it==PackageManager.PERMISSION_GRANTED})scanMediaStore()else if(requestCode==REQUEST_PERMISSION)Toast.makeText(this,"Media permissions are required for MediaStore scanning.",Toast.LENGTH_LONG).show()}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQUEST_TREE||resultCode!=RESULT_OK)return;val uri=data?.data?:return;try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}catch(_:SecurityException){};when(pendingFolderAction){ACTION_SCAN_FOLDER->scanFolder(uri);ACTION_DEEP_SCAN->deepScanFolder(uri);ACTION_RECOVER->recoverSelected(uri)}}

    private fun scanFolder(uri:Uri){setBusy(true,"Scanning selected folder...");scope.launch{val scanner=StorageScanner(this@MainActivity);results=scanner.scanTree(uri){p->status.text="Scanned ${p.files} files (${formatBytes(p.bytes)})"};renderResults();setBusy(false,"Normal scan found ${results.size} candidates.")}}
    private fun deepScanFolder(uri:Uri){setBusy(true,"Maximum scan started: reading every accessible byte and validating candidates...");scope.launch{val scanner=DeepContentScanner(this@MainActivity);results=scanner.scanTree(uri){p->status.text="Carved ${p.files} files • ${formatBytes(p.bytes)} • ${p.candidates} candidates"};renderResults();val valid=results.count{it.quality==RecoveryQuality.VALID||it.quality==RecoveryQuality.LIKELY_RECOVERABLE};setBusy(false,"Maximum scan complete: ${results.size} candidates, $valid structurally recoverable.")}}
    private fun scanMediaStore(){setBusy(true,"Scanning MediaStore images and videos...");scope.launch{val scanner=StorageScanner(this@MainActivity);results=scanner.scanMediaStore{p->status.text="Checked ${p.files} indexed entries"};renderResults();setBusy(false,"MediaStore scan found ${results.size} candidates.")}}

    private fun renderResults(){resultContainer.removeAllViews();selected.clear();recover.isEnabled=false;if(results.isEmpty()){resultContainer.addView(TextView(this).apply{text="No recognizable media found.";textSize=16f});return};results.forEachIndexed{index,result->resultContainer.addView(CheckBox(this).apply{text=buildString{append(result.sourceName);append("\n${result.detectedExtension.uppercase()} • ${formatBytes(result.sizeBytes)} • ${result.quality.label()} • ${result.confidence}%");if(result.recoveredLength>0)append("\nExact carved length: ${formatBytes(result.recoveredLength)}");if(result.offset>0)append(" • offset ${result.offset}");if(result.isExtensionMismatch)append("\nFilename extension differs from detected content")};gravity=Gravity.CENTER_VERTICAL;setPadding(0,12,0,12);isEnabled=result.quality!=RecoveryQuality.SIGNATURE_ONLY;setOnCheckedChangeListener{_,checked->val key="${result.sourceUri}:${result.offset}:${result.detectedType}";if(checked)selected[key]=result else selected.remove(key);recover.isEnabled=selected.isNotEmpty()}});if(index!=results.lastIndex)resultContainer.addView(TextView(this).apply{setPadding(0,0,0,1);setBackgroundColor(0xFFE0E0E0.toInt())},matchHeight())}}
    private fun recoverSelected(destination:Uri){val items=selected.values.toList();setBusy(true,"Recovering ${items.size} validated candidate(s)...");scope.launch(Dispatchers.IO){val writer=RecoveryWriter(this@MainActivity);var success=0;items.forEach{if(writer.recover(it,destination)!=null)success++};withContext(Dispatchers.Main){setBusy(false,"Recovered $success/${items.size}. Only structurally validated candidates were written.");selected.clear();recover.isEnabled=false}}}
    private fun setBusy(busy:Boolean,message:String){chooseFolder.isEnabled=!busy;deepScan.isEnabled=!busy;scanMedia.isEnabled=!busy;recover.isEnabled=!busy&&selected.isNotEmpty();status.text=message}
    private fun formatBytes(v:Long)=when{v<1024->"$v B";v<1024*1024->"%.1f KB".format(v/1024.0);v<1024*1024*1024->"%.1f MB".format(v/(1024.0*1024.0));else->"%.2f GB".format(v/(1024.0*1024.0*1024.0))}
    private fun RecoveryQuality.label()=when(this){RecoveryQuality.VALID->"VALID";RecoveryQuality.LIKELY_RECOVERABLE->"LIKELY RECOVERABLE";RecoveryQuality.PARTIAL_OR_CORRUPT->"PARTIAL/CORRUPT";RecoveryQuality.SIGNATURE_ONLY->"SIGNATURE ONLY"}
    private fun match()=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT);private fun matchHeight()=LinearLayout.LayoutParams(-1,1)
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object{private const val REQUEST_TREE=1001;private const val REQUEST_PERMISSION=1002;private const val ACTION_SCAN_FOLDER=1;private const val ACTION_DEEP_SCAN=2;private const val ACTION_RECOVER=3}
}
