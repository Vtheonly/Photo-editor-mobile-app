package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

class ArtifactMiner(private val context: Context) {
    data class Artifact(val uri: Uri, val displayName: String, val type: MediaType, val offset: Long = 0L, val length: Long = 0L, val source: String, val quality: RecoveryQuality = RecoveryQuality.LIKELY_RECOVERABLE)

    fun scanTree(treeUri: Uri, onProgress: (String) -> Unit): List<Artifact> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<Artifact>()
        walk(root, out, onProgress)
        return out.distinctBy { "${it.uri}|${it.offset}|${it.length}|${it.type}|${it.source}" }
    }

    /** Same artifact pipeline for a direct File found through MANAGE_EXTERNAL_STORAGE. */
    fun scanFile(file: File): List<Artifact> {
        if (!file.isFile || !file.canRead()) return emptyList()
        val uri = Uri.fromFile(file)
        val out = mutableListOf<Artifact>()
        val name = file.name
        try {
            FileInputStreamCompat.open(file).use { input ->
                val detection = SignatureRegistry.detect(input, name)
                if (detection != null) out += Artifact(uri, name, detection.type, detection.offset, file.length(), "filesystem", RecoveryQuality.LIKELY_RECOVERABLE)
            }
        } catch (_: Throwable) { }
        if (name.contains("thumbdata", true) || name.startsWith(".thumbdata", true)) out += carveJpegs(uri, name, "Samsung .thumbdata binary carve")
        if (name.endsWith(".sdoc", true) || name.endsWith(".sdocx", true) || name.endsWith(".zip", true) || name.endsWith(".ssbak", true)) out += extractZipImages(uri, name)
        return out.distinctBy { "${it.uri}|${it.offset}|${it.length}|${it.type}|${it.source}" }
    }

    private fun walk(dir: DocumentFile, out: MutableList<Artifact>, progress: (String) -> Unit) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) { walk(file, out, progress); continue }
            val name = file.name ?: "unnamed"
            progress(name)
            val detection = try { context.contentResolver.openInputStream(file.uri)?.use { SignatureRegistry.detect(it, name) } } catch (_: Throwable) { null }
            if (detection != null) {
                out += Artifact(file.uri, name, detection.type, detection.offset, file.length(), "filesystem")
                if (detection.type == MediaType.JPEG || detection.type == MediaType.HEIC) extractExifThumbnail(file.uri)?.let { bytes -> if (bytes.size > 2048) out += Artifact(Uri.fromFile(saveTemp("exif-", bytes)), "$name — EXIF preview.jpg", MediaType.JPEG, 0, bytes.size.toLong(), "EXIF embedded thumbnail", RecoveryQuality.VALID) }
                if (detection.type == MediaType.MP4 || detection.type == MediaType.MOV || detection.type == MediaType.THREE_GP) extractVideoFrame(file.uri)?.let { bytes -> if (bytes.size > 2048) out += Artifact(Uri.fromFile(saveTemp("video-frame-", bytes)), "$name — keyframe.jpg", MediaType.JPEG, 0, bytes.size.toLong(), "video keyframe preview", RecoveryQuality.VALID) }
            }
            if (name.contains("thumbdata", true) || name.startsWith(".thumbdata", true)) out += carveJpegs(file.uri, name, "Samsung .thumbdata binary carve")
            if (name.endsWith(".sdoc", true) || name.endsWith(".sdocx", true) || name.endsWith(".zip", true) || name.endsWith(".ssbak", true)) out += extractZipImages(file.uri, name)
        }
    }

    private fun saveTemp(prefix: String, bytes: ByteArray): File {
        val f = File.createTempFile(prefix, ".jpg", context.cacheDir)
        f.outputStream().use { it.write(bytes) }
        return f
    }

    private fun extractZipImages(uri: Uri, sourceName: String): List<Artifact> {
        val temp = File.createTempFile("archive-", ".zip", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return emptyList()
            ZipFile(temp).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { Regex(".*\\.(jpg|jpeg|png|webp|heic|heif|gif|bmp)$", RegexOption.IGNORE_CASE).matches(it.name) }
                    .mapNotNull { entry ->
                        try {
                            val ext = entry.name.substringAfterLast('.', "bin")
                            val f = File.createTempFile("archive-media-", ".$ext", context.cacheDir)
                            zip.getInputStream(entry).use { input -> f.outputStream().use { input.copyTo(it) } }
                            val type = f.inputStream().use { input -> SignatureRegistry.detect(input, entry.name)?.type } ?: return@mapNotNull null
                            Artifact(Uri.fromFile(f), "$sourceName — ${entry.name}", type, 0, f.length(), "archive $entry.name", RecoveryQuality.VALID)
                        } catch (_: Throwable) { null }
                    }.toList()
            }
        } catch (_: Throwable) { emptyList() } finally { temp.delete() }
    }

    fun carveJpegs(uri: Uri, sourceName: String, source: String = "binary-carve"): List<Artifact> {
        val results = mutableListOf<Artifact>()
        context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 1024 * 1024)
            var absolute = 0L; var p1 = -1; var p2 = -1; var start = -1L; var candidate = ByteArrayOutputStream()
            while (true) {
                val b = input.read(); if (b < 0) break
                val v = b and 0xFF
                if (start < 0 && p2 == 0xFF && p1 == 0xD8 && v == 0xFF) { start = absolute - 2; candidate.reset(); candidate.write(0xFF); candidate.write(0xD8); candidate.write(0xFF) }
                else if (start >= 0) candidate.write(v)
                if (start >= 0) {
                    if (candidate.size() > 16 * 1024 * 1024) { start = -1; candidate.reset() }
                    else if (p1 == 0xFF && v == 0xD9) {
                        val bytes = candidate.toByteArray()
                        val jpegMarker = bytes.size >= 10 && ((bytes[6] == 'J'.code.toByte() && bytes[7] == 'F'.code.toByte() && bytes[8] == 'I'.code.toByte()) || (bytes[6] == 'E'.code.toByte() && bytes[7] == 'x'.code.toByte() && bytes[8] == 'i'.code.toByte()))
                        if (bytes.size >= 10 * 1024 && jpegMarker && BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) results += Artifact(uri, "$sourceName-jpeg-${results.size + 1}.jpg", MediaType.JPEG, start, bytes.size.toLong(), source, RecoveryQuality.VALID)
                        start = -1; candidate.reset()
                    }
                }
                p2 = p1; p1 = v; absolute++
            }
        }
        return results
    }

    fun extractExifThumbnail(uri: Uri): ByteArray? = try { context.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor).thumbnail } } catch (_: Throwable) { null }

    fun extractVideoFrame(uri: Uri): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it); b.recycle() }.toByteArray()
        } catch (_: Throwable) { null } finally { r.release() }
    }

    private object FileInputStreamCompat {
        fun open(file: File) = java.io.FileInputStream(file)
    }
}
