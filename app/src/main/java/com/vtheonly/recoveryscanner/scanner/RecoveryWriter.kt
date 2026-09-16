package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.OutputStream

class RecoveryWriter(private val context: Context) {
    fun recover(result: ScanResult, destinationTree: Uri): Uri? {
        val destination = DocumentFile.fromTreeUri(context, destinationTree) ?: return null
        val source = Uri.parse(result.sourceUri)
        val base = result.sourceName.substringBeforeLast('.', result.sourceName)
        val filename = "$base.recovered.${result.detectedExtension}"
        val output = destination.createFile(mime(result.detectedType), uniqueName(destination, filename)) ?: return null

        context.contentResolver.openInputStream(source)?.use { input ->
            context.contentResolver.openOutputStream(output.uri)?.use { out ->
                if (result.isEmbeddedCandidate) {
                    copyEmbedded(input, out, result.detectedType, result.offset)
                } else {
                    input.copyTo(out, DEFAULT_BUFFER)
                }
            }
        }
        return output.uri
    }

    private fun copyEmbedded(input: java.io.InputStream, out: OutputStream, type: MediaType, offset: Long) {
        var skipped = 0L
        while (skipped < offset) {
            val n = input.skip(offset - skipped)
            if (n <= 0) return
            skipped += n
        }
        if (type == MediaType.JPEG) {
            val buffered = BufferedInputStream(input, DEFAULT_BUFFER)
            out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            var previous = -1
            while (true) {
                val current = buffered.read()
                if (current < 0) break
                out.write(current)
                if (previous == 0xFF && current == 0xD9) break
                previous = current
            }
        } else if (type == MediaType.PNG) {
            val marker = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            out.write(marker)
            val tail = byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82)
            var matched = 0
            while (matched < tail.size) {
                val b = input.read()
                if (b < 0) break
                out.write(b)
                matched = if (b == (tail[matched].toInt() and 0xFF)) matched + 1 else 0
            }
        } else {
            input.copyTo(out, DEFAULT_BUFFER)
        }
    }

    private fun uniqueName(parent: DocumentFile, desired: String): String {
        if (parent.findFile(desired) == null) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var index = 2
        while (parent.findFile("$stem-$index$ext") != null) index++
        return "$stem-$index$ext"
    }

    private fun mime(type: MediaType): String = when (type) {
        MediaType.JPEG -> "image/jpeg"
        MediaType.PNG -> "image/png"
        MediaType.GIF -> "image/gif"
        MediaType.WEBP -> "image/webp"
        MediaType.BMP -> "image/bmp"
        MediaType.HEIC -> "image/heic"
        MediaType.MP4, MediaType.MOV, MediaType.THREE_GP -> "video/*"
        MediaType.UNKNOWN -> "application/octet-stream"
    }

    companion object { private const val DEFAULT_BUFFER = 64 * 1024 }
}
