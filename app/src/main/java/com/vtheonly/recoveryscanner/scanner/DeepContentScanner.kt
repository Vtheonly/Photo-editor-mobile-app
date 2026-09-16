package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

/**
 * Aggressive no-root content scan.
 *
 * Unlike the normal scanner, this reads the complete byte stream and looks for
 * media signatures anywhere inside accessible files. It can therefore find
 * renamed files, media appended to another file, and embedded JPEG/PNG data.
 * It still cannot see deleted blocks that Android does not expose to the app.
 */
class DeepContentScanner(private val context: Context) {
    suspend fun scanTree(
        treeUri: Uri,
        onProgress: suspend (DeepProgress) -> Unit
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext emptyList()
        val results = mutableListOf<ScanResult>()
        var files = 0L
        var bytes = 0L

        suspend fun visit(node: androidx.documentfile.provider.DocumentFile) {
            ensureActive()
            if (node.isDirectory) {
                node.listFiles().forEach { visit(it) }
                return
            }
            if (!node.isFile) return
            val uri = node.uri
            val name = node.name ?: "unknown"
            val size = node.length().coerceAtLeast(0L)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val found = scanStream(input, size)
                found.forEach { detection ->
                    val ext = extension(name)
                    results += ScanResult(
                        sourceName = name,
                        sourceUri = uri.toString(),
                        detectedType = detection.type,
                        detectedExtension = detection.extension,
                        originalExtension = ext,
                        sizeBytes = detection.length.takeIf { it > 0 } ?: size,
                        confidence = detection.confidence,
                        isExtensionMismatch = ext.isNotEmpty() && ext != detection.extension,
                        isEmbeddedCandidate = detection.offset > 0,
                        offset = detection.offset
                    )
                }
            }
            files++
            bytes += size
            if (files % 5L == 0L) onProgress(DeepProgress(files, bytes, results.size))
        }

        visit(root)
        onProgress(DeepProgress(files, bytes, results.size, true))
        results.distinctBy { "${it.sourceUri}:${it.offset}:${it.detectedType}" }
    }

    private fun scanStream(input: java.io.InputStream, size: Long): List<DeepDetection> {
        val stream = BufferedInputStream(input, 256 * 1024)
        val result = mutableListOf<DeepDetection>()
        val buffer = ByteArray(BUFFER_SIZE)
        val signatures = listOf(
            Sig(MediaType.JPEG, "jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            Sig(MediaType.PNG, "png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
            Sig(MediaType.GIF, "gif", "GIF89a".toByteArray()),
            Sig(MediaType.GIF, "gif", "GIF87a".toByteArray()),
            Sig(MediaType.BMP, "bmp", byteArrayOf(0x42, 0x4D)),
            Sig(MediaType.WEBP, "webp", "RIFF".toByteArray())
        )
        var absolute = 0L
        var carry = ByteArray(0)
        var firstHeaderChecked = false

        while (true) {
            val count = stream.read(buffer)
            if (count <= 0) break
            val combined = ByteArray(carry.size + count)
            carry.copyInto(combined)
            buffer.copyInto(combined, carry.size, 0, count)

            for (sig in signatures) {
                var start = 0
                while (start <= combined.size - sig.magic.size) {
                    val index = indexOf(combined, sig.magic, start)
                    if (index < 0) break
                    val offset = absolute - carry.size + index
                    if (offset >= 0 && !result.any { it.offset == offset && it.type == sig.type }) {
                        val confidence = if (offset == 0L) 99 else 86
                        val length = when (sig.type) {
                            MediaType.JPEG -> findJpegLength(stream, combined, index, offset, size)
                            MediaType.PNG -> findPngLength(stream, combined, index, offset, size)
                            else -> 0L
                        }
                        if (sig.type != MediaType.WEBP || looksLikeWebp(combined, index)) {
                            result += DeepDetection(sig.type, sig.extension, confidence, offset, length)
                        }
                    }
                    start = index + 1
                }
            }

            // Search ISO-BMFF/MP4 family at any offset. We only report embedded
            // video candidates; recovery of a fragmented container needs a parser.
            var ftyp = indexOf(combined, "ftyp".toByteArray(), 0)
            while (ftyp >= 4) {
                val boxStart = ftyp - 4
                val offset = absolute - carry.size + boxStart
                if (offset >= 0 && boxStart + 12 <= combined.size) {
                    val brand = String(combined, ftyp + 4, 4, Charsets.US_ASCII).lowercase()
                    val type = when {
                        brand == "qt  " -> MediaType.MOV
                        brand.startsWith("3gp") || brand.startsWith("3g2") -> MediaType.THREE_GP
                        brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> MediaType.HEIC
                        else -> MediaType.MP4
                    }
                    if (!result.any { it.offset == offset && it.type == type }) {
                        result += DeepDetection(type, type.extensionName(), 84, offset, 0L)
                    }
                }
                ftyp = indexOf(combined, "ftyp".toByteArray(), ftyp + 4)
            }

            firstHeaderChecked = true
            absolute += count
            carry = combined.takeLast(MAX_MAGIC - 1).toByteArray()
        }
        return result
    }

    private fun findJpegLength(
        stream: BufferedInputStream,
        combined: ByteArray,
        localOffset: Int,
        absoluteOffset: Long,
        totalSize: Long
    ): Long {
        // We deliberately don't consume the underlying stream here. Boundary
        // extraction is performed again by RecoveryWriter, which validates SOI/EOI.
        return 0L
    }

    private fun findPngLength(
        stream: BufferedInputStream,
        combined: ByteArray,
        localOffset: Int,
        absoluteOffset: Long,
        totalSize: Long
    ): Long = 0L

    private fun looksLikeWebp(buffer: ByteArray, offset: Int): Boolean =
        offset + 12 <= buffer.size &&
            buffer.copyOfRange(offset, offset + 4).contentEquals("RIFF".toByteArray()) &&
            buffer.copyOfRange(offset + 8, offset + 12).contentEquals("WEBP".toByteArray())

    private fun indexOf(buffer: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from >= buffer.size) return -1
        outer@ for (i in from..buffer.size - needle.size) {
            for (j in needle.indices) if (buffer[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun MediaType.extensionName(): String = when (this) {
        MediaType.THREE_GP -> "3gp"
        else -> name.lowercase()
    }

    private data class Sig(val type: MediaType, val extension: String, val magic: ByteArray)
    private data class DeepDetection(val type: MediaType, val extension: String, val confidence: Int, val offset: Long, val length: Long)

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val MAX_MAGIC = 16
    }
}

data class DeepProgress(
    val files: Long,
    val bytes: Long,
    val candidates: Int,
    val complete: Boolean = false
)
