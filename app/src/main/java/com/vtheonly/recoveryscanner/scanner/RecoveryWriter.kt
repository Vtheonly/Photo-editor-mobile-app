package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.FileInputStream

class RecoveryWriter(private val context: Context) {
    fun recover(result: ScanResult, destinationTree: Uri): Uri? {
        if (result.quality == RecoveryQuality.SIGNATURE_ONLY) return null
        val destination = DocumentFile.fromTreeUri(context, destinationTree) ?: return null
        val source = Uri.parse(result.sourceUri)
        val base = result.sourceName.substringBeforeLast('.', result.sourceName).replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val filename = "$base.recovered.${result.detectedExtension}"
        val output = destination.createFile(mime(result.detectedType), uniqueName(destination, filename)) ?: return null
        try {
            openSource(source)?.use { raw ->
                BufferedInputStream(raw, DEFAULT_BUFFER).use { input ->
                    context.contentResolver.openOutputStream(output.uri)?.use { out ->
                        if (!skipFully(input, result.offset)) return null
                        val limit = result.recoveredLength.takeIf { it > 0 } ?: Long.MAX_VALUE
                        val copied = copyExactly(input, out, limit)
                        if (result.recoveredLength > 0 && copied != result.recoveredLength) return null
                    }
                }
            } ?: return null
            return output.uri
        } catch (_: Exception) { output.delete(); return null }
    }

    private fun openSource(uri: Uri): java.io.InputStream? = when (uri.scheme) { "file" -> try { FileInputStream(uri.path!!) } catch (_: Throwable) { null }; else -> context.contentResolver.openInputStream(uri) }
    private fun copyExactly(input: java.io.InputStream, out: java.io.OutputStream, maxBytes: Long): Long { val buffer = ByteArray(DEFAULT_BUFFER); var remaining = maxBytes; var copied = 0L; while (remaining > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) break; if (n == 0) continue; out.write(buffer, 0, n); copied += n; remaining -= n }; return copied }
    private fun skipFully(input: java.io.InputStream, bytes: Long): Boolean { var remaining = bytes; while (remaining > 0) { val skipped = input.skip(remaining); if (skipped > 0) { remaining -= skipped; continue }; if (input.read() < 0) return false; remaining-- }; return true }
    private fun uniqueName(parent: DocumentFile, desired: String): String { if (parent.findFile(desired) == null) return desired; val dot = desired.lastIndexOf('.'); val stem = if (dot > 0) desired.substring(0, dot) else desired; val ext = if (dot > 0) desired.substring(dot) else ""; var index = 2; while (parent.findFile("$stem-$index$ext") != null) index++; return "$stem-$index$ext" }
    private fun mime(type: MediaType): String = when (type) {
        MediaType.JPEG -> "image/jpeg"; MediaType.PNG -> "image/png"; MediaType.GIF -> "image/gif"; MediaType.WEBP -> "image/webp"; MediaType.BMP -> "image/bmp"; MediaType.HEIC -> "image/heic"
        MediaType.MP4 -> "video/mp4"; MediaType.MOV -> "video/quicktime"; MediaType.THREE_GP -> "video/3gpp"; MediaType.MKV -> "video/x-matroska"; MediaType.WEBM -> "video/webm"; MediaType.UNKNOWN -> "application/octet-stream"
    }
    companion object { private const val DEFAULT_BUFFER = 64 * 1024 }
}
