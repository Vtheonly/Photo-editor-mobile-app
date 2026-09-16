package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

class ArtifactMiner(private val context: Context) {
    data class Artifact(val uri: Uri, val displayName: String, val type: MediaType, val offset: Long = 0L, val length: Long = 0L, val source: String, val quality: RecoveryQuality = RecoveryQuality.LIKELY_RECOVERABLE)

    fun scanTree(treeUri: Uri, onProgress: (String) -> Unit): List<Artifact> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<Artifact>(); walk(root, out, onProgress)
        return out.distinctBy { "${it.uri}|${it.offset}|${it.length}|${it.type}" }
    }

    private fun walk(dir: DocumentFile, out: MutableList<Artifact>, progress: (String) -> Unit) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) { walk(file, out, progress); continue }
            val name = file.name ?: "unnamed"; progress(name)
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                val detection = SignatureRegistry.detect(input, name)
                if (detection != null) out += Artifact(file.uri, name, detection.type, detection.offset, file.length(), "filesystem")
            }
            if (name.contains("thumbdata", true)) out += carveJpegs(file.uri, name, "thumbdata")
        }
    }

    fun carveJpegs(uri: Uri, sourceName: String, source: String = "binary-carve"): List<Artifact> {
        val results = mutableListOf<Artifact>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 1024 * 1024)
            var absolute = 0L; var previous1 = -1; var previous2 = -1; var start = -1L; var candidate = ByteArrayOutputStream()
            while (true) {
                val b = input.read(); if (b < 0) break
                val value = b and 0xFF
                if (start < 0 && previous2 == 0xFF && previous1 == 0xD8 && value == 0xFF) { start = absolute - 2; candidate.reset() }
                if (start >= 0) {
                    candidate.write(value)
                    if (candidate.size() > 16 * 1024 * 1024) { start = -1; candidate.reset() }
                    else if (previous1 == 0xFF && value == 0xD9) {
                        val bytes = candidate.toByteArray()
                        if (bytes.size >= 10 * 1024 && BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) results += Artifact(uri, "$sourceName-jpeg-${results.size + 1}.jpg", MediaType.JPEG, start, bytes.size.toLong(), source, RecoveryQuality.VALID)
                        start = -1; candidate.reset()
                    }
                }
                previous2 = previous1; previous1 = value; absolute++
            }
        }
        return results
    }

    fun extractExifThumbnail(uri: Uri): ByteArray? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor).thumbnail }
    } catch (_: Throwable) { null }

    fun listEmbeddedZipImages(uri: Uri): List<String> {
        val temp = File.createTempFile("recovery-archive-", ".zip", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ZipFile(temp).use { zip -> zip.entries().asSequence().filter { !it.isDirectory && (it.name.startsWith("media/", true) || it.name.startsWith("images/", true)) }.filter { it.name.lowercase(Locale.US).matches(".*\\.(jpg|jpeg|png|webp|heic|gif)$") }.map { it.name }.toList() }
        } catch (_: Throwable) { emptyList() } finally { temp.delete() }
    }

    fun extractVideoFrame(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri); val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it); bitmap.recycle() }.toByteArray()
        } catch (_: Throwable) { null } finally { retriever.release() }
    }
}
