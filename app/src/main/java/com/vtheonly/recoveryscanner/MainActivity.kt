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
    private lateinit var scanMedia: Button
    private lateinit var recover: Button
    private val selected = linkedMapOf<String, ScanResult>()
    private var results = emptyList<ScanResult>()
    private var pendingFolderAction = ACTION_SCAN_FOLDER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
        }
        root.addView(TextView(this).apply {
            text = "Recovery Scanner"
            textSize = 28f
            setTextColor(0xFF172027.toInt())
        }, match())
        root.addView(TextView(this).apply {
            text = "Content-based media discovery for accessible storage. This app does not access raw deleted blocks."
            textSize = 14f
            setPadding(0, 8, 0, 20)
        }, match())

        chooseFolder = Button(this).apply { text = "Choose folder and scan"; setOnClickListener { chooseScanFolder() } }
        scanMedia = Button(this).apply { text = "Scan MediaStore"; setOnClickListener { requestMediaPermissionAndScan() } }
        recover = Button(this).apply { text = "Recover selected"; isEnabled = false; setOnClickListener { chooseRecoveryFolder() } }
        root.addView(chooseFolder, match())
        root.addView(scanMedia, match())
        root.addView(recover, match())

        status = TextView(this).apply {
            text = "Choose a folder to inspect arbitrary files, or scan MediaStore for indexed media."
            textSize = 14f
            setPadding(0, 18, 0, 14)
        }
        root.addView(status, match())

        val scroll = ScrollView(this)
        resultContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(resultContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun chooseScanFolder() {
        pendingFolderAction = ACTION_SCAN_FOLDER
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_TREE)
    }

    private fun chooseRecoveryFolder() {
        pendingFolderAction = ACTION_RECOVER
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_TREE)
    }

    private fun requestMediaPermissionAndScan() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) scanMediaStore() else ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) return
        val allGranted = permissions.indices.all { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted) scanMediaStore()
        else Toast.makeText(this, "Media permissions are required for MediaStore scanning.", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: SecurityException) {}
        if (pendingFolderAction == ACTION_SCAN_FOLDER) scanFolder(uri) else recoverSelected(uri)
    }

    private fun scanFolder(uri: Uri) {
        setBusy(true, "Scanning selected folder...")
        scope.launch {
            val scanner = StorageScanner(this@MainActivity)
            results = scanner.scanTree(uri) { progress -> status.text = "Scanned ${progress.files} files (${formatBytes(progress.bytes)})" }
            renderResults()
            setBusy(false, "Found ${results.size} media candidates.")
        }
    }

    private fun scanMediaStore() {
        setBusy(true, "Scanning MediaStore...")
        scope.launch {
            val scanner = StorageScanner(this@MainActivity)
            results = scanner.scanMediaStore { progress -> status.text = "Checked ${progress.files} entries" }
            renderResults()
            setBusy(false, "Found ${results.size} media candidates.")
        }
    }

    private fun renderResults() {
        resultContainer.removeAllViews()
        selected.clear()
        recover.isEnabled = false
        if (results.isEmpty()) {
            resultContainer.addView(TextView(this).apply { text = "No recognizable media found."; textSize = 16f })
            return
        }
        results.forEachIndexed { index, result ->
            val row = CheckBox(this).apply {
                text = buildString {
                    append(result.sourceName)
                    append("\nDetected: ${result.detectedExtension.uppercase()}  •  ${formatBytes(result.sizeBytes)}  •  ${result.confidence}%")
                    if (result.isExtensionMismatch) append("\nExtension mismatch")
                    if (result.isEmbeddedCandidate) append("\nEmbedded media candidate at byte ${result.offset}")
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected[result.sourceUri] = result else selected.remove(result.sourceUri)
                    recover.isEnabled = selected.isNotEmpty()
                }
            }
            resultContainer.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (index != results.lastIndex) resultContainer.addView(TextView(this).apply {
                setPadding(0, 0, 0, 1)
                setBackgroundColor(0xFFE0E0E0.toInt())
            }, matchHeight())
        }
    }

    private fun recoverSelected(destination: Uri) {
        val items = selected.values.toList()
        setBusy(true, "Recovering ${items.size} selected file(s)...")
        scope.launch(Dispatchers.IO) {
            val writer = RecoveryWriter(this@MainActivity)
            var success = 0
            items.forEach { if (writer.recover(it, destination) != null) success++ }
            withContext(Dispatchers.Main) {
                setBusy(false, "Recovered $success/${items.size}. Files were written to the chosen destination.")
                selected.clear()
                recover.isEnabled = false
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        chooseFolder.isEnabled = !busy
        scanMedia.isEnabled = !busy
        recover.isEnabled = !busy && selected.isNotEmpty()
        status.text = message
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
        value < 1024 * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024.0))
        else -> "%.2f GB".format(value / (1024.0 * 1024.0 * 1024.0))
    }

    private fun match() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun matchHeight() = LinearLayout.LayoutParams(-1, 1)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_TREE = 1001
        private const val REQUEST_PERMISSION = 1002
        private const val ACTION_SCAN_FOLDER = 1
        private const val ACTION_RECOVER = 2
    }
}
