package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

class StorageScanner(private val context: Context) {
    suspend fun scanTree(
        treeUri: Uri,
        onProgress: suspend (ScannedProgress) -> Unit
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val results = mutableListOf<ScanResult>()
        var scanned = 0L
        var bytes = 0L

        suspend fun visit(node: DocumentFile) {
            ensureActive()
            if (node.isDirectory) {
                node.listFiles().forEach { visit(it) }
                return
            }
            if (!node.isFile) return

            val size = node.length().coerceAtLeast(0L)
            val name = node.name ?: "unknown"
            context.contentResolver.openInputStream(node.uri)?.use { input ->
                val detection = SignatureRegistry.detect(input, name)
                if (detection != null) {
                    val originalExt = extension(name)
                    results += ScanResult(
                        sourceName = name,
                        sourceUri = node.uri.toString(),
                        detectedType = detection.type,
                        detectedExtension = detection.extension,
                        originalExtension = originalExt,
                        sizeBytes = size,
                        confidence = detection.confidence,
                        isExtensionMismatch = originalExt.isNotEmpty() &&
                            originalExt != detection.extension,
                        isEmbeddedCandidate = detection.offset > 0,
                        offset = detection.offset
                    )
                }
            }
            scanned++
            bytes += size
            if (scanned % 20L == 0L) onProgress(ScannedProgress(scanned, bytes))
        }

        visit(root)
        onProgress(ScannedProgress(scanned, bytes, true))
        results
    }

    suspend fun scanMediaStore(
        onProgress: suspend (ScannedProgress) -> Unit
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScanResult>()
        val projection = arrayOf(
            android.provider.MediaStore.Files.FileColumns._ID,
            android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
            android.provider.MediaStore.Files.FileColumns.SIZE,
            android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        val collection = android.provider.MediaStore.Files.getContentUri("external")
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                ensureActive()
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "unknown"
                val uri = Uri.withAppendedPath(collection, id.toString())
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val detection = SignatureRegistry.detect(input, name)
                    if (detection != null) {
                        val originalExt = extension(name)
                        results += ScanResult(
                            name, uri.toString(), detection.type, detection.extension,
                            originalExt, cursor.getLong(sizeCol), detection.confidence,
                            originalExt.isNotEmpty() && originalExt != detection.extension,
                            detection.offset > 0, detection.offset
                        )
                    }
                }
                if (results.size % 20 == 0) onProgress(ScannedProgress(results.size.toLong(), results.sumOf { it.sizeBytes }))
            }
        }
        onProgress(ScannedProgress(results.size.toLong(), results.sumOf { it.sizeBytes }, true))
        results
    }

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase()
}

data class ScannedProgress(
    val files: Long,
    val bytes: Long,
    val complete: Boolean = false
)
