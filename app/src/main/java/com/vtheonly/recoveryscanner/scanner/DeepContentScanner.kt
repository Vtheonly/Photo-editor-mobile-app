package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

/** Full-stream scanner. Detection is followed by a fresh-stream structural parse. */
class DeepContentScanner(private val context: Context) {
    private val carver = MediaCarver(context)

    suspend fun scanTree(treeUri: Uri, onProgress: suspend (DeepProgress) -> Unit): List<ScanResult> = withContext(Dispatchers.IO) {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext emptyList()
        val results = mutableListOf<ScanResult>()
        var files = 0L
        var bytes = 0L

        suspend fun visit(node: androidx.documentfile.provider.DocumentFile) {
            ensureActive()
            if (node.isDirectory) { node.listFiles().forEach { visit(it) }; return }
            if (!node.isFile) return
            val uri = node.uri
            val name = node.name ?: "unknown"
            val size = node.length().coerceAtLeast(0L)
            context.contentResolver.openInputStream(uri)?.use { input ->
                val found = findSignatures(input)
                found.forEach { detection ->
                    val carved = carver.analyze(uri, detection.offset, detection.type, size)
                    val ext = extension(name)
                    val effectiveConfidence = maxOf(detection.confidence, carved.confidence)
                    val recoveredLength = carved.length
                    results += ScanResult(
                        sourceName = name,
                        sourceUri = uri.toString(),
                        detectedType = detection.type,
                        detectedExtension = detection.extension,
                        originalExtension = ext,
                        sizeBytes = if (recoveredLength > 0) recoveredLength else size,
                        confidence = effectiveConfidence,
                        isExtensionMismatch = ext.isNotEmpty() && ext != detection.extension,
                        isEmbeddedCandidate = detection.offset > 0,
                        offset = detection.offset,
                        recoveredLength = recoveredLength,
                        quality = carved.quality
                    )
                }
            }
            files++; bytes += size
            if (files % 5L == 0L) onProgress(DeepProgress(files, bytes, results.size))
        }
        visit(root)
        onProgress(DeepProgress(files, bytes, results.size, true))
        results.distinctBy { "${it.sourceUri}:${it.offset}:${it.detectedType}" }
    }

    private fun findSignatures(input: java.io.InputStream): List<DeepDetection> {
        val stream = BufferedInputStream(input, 256 * 1024)
        val result = mutableListOf<DeepDetection>()
        val buffer = ByteArray(BUFFER_SIZE)
        val signatures = listOf(
            Sig(MediaType.JPEG, "jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
            Sig(MediaType.PNG, "png", byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A)),
            Sig(MediaType.GIF, "gif", "GIF89a".toByteArray()), Sig(MediaType.GIF, "gif", "GIF87a".toByteArray()),
            Sig(MediaType.BMP, "bmp", byteArrayOf(0x42,0x4D)), Sig(MediaType.WEBP, "webp", "RIFF".toByteArray())
        )
        var absolute = 0L
        var carry = ByteArray(0)
        while (true) {
            val count = stream.read(buffer); if (count <= 0) break
            val combined = ByteArray(carry.size + count)
            carry.copyInto(combined); buffer.copyInto(combined, carry.size, 0, count)
            for (sig in signatures) {
                var start = 0
                while (start <= combined.size - sig.magic.size) {
                    val index = indexOf(combined, sig.magic, start); if (index < 0) break
                    val offset = absolute - carry.size + index
                    if (offset >= 0 && !result.any { it.offset == offset && it.type == sig.type }) {
                        if (sig.type != MediaType.WEBP || looksLikeWebp(combined, index)) result += DeepDetection(sig.type, sig.extension, if (offset == 0L) 99 else 86, offset)
                    }
                    start = index + 1
                }
            }
            var ftyp = indexOf(combined, "ftyp".toByteArray(), 0)
            while (ftyp >= 4) {
                val boxStart = ftyp - 4; val offset = absolute - carry.size + boxStart
                if (offset >= 0 && boxStart + 12 <= combined.size) {
                    val brand = String(combined, ftyp + 4, 4, Charsets.US_ASCII).lowercase()
                    val type = when { brand == "qt  " -> MediaType.MOV; brand.startsWith("3gp") || brand.startsWith("3g2") -> MediaType.THREE_GP; brand in setOf("heic","heix","hevc","hevx","mif1","msf1") -> MediaType.HEIC; else -> MediaType.MP4 }
                    if (!result.any { it.offset == offset && it.type == type }) result += DeepDetection(type, type.extensionName(), 84, offset)
                }
                ftyp = indexOf(combined, "ftyp".toByteArray(), ftyp + 4)
            }
            absolute += count.toLong(); carry = combined.takeLast(MAX_MAGIC - 1).toByteArray()
        }
        return result
    }

    private fun looksLikeWebp(b: ByteArray, o: Int) = o + 12 <= b.size && b.copyOfRange(o,o+4).contentEquals("RIFF".toByteArray()) && b.copyOfRange(o+8,o+12).contentEquals("WEBP".toByteArray())
    private fun indexOf(b: ByteArray, n: ByteArray, from: Int): Int { if(n.isEmpty() || from > b.size-n.size)return -1; outer@for(i in from..b.size-n.size){for(j in n.indices)if(b[i+j]!=n[j])continue@outer;return i};return -1 }
    private fun extension(name:String)=name.substringAfterLast('.',"").lowercase()
    private fun MediaType.extensionName()=when(this){MediaType.THREE_GP->"3gp";else->name.lowercase()}
    private data class Sig(val type:MediaType,val extension:String,val magic:ByteArray)
    private data class DeepDetection(val type:MediaType,val extension:String,val confidence:Int,val offset:Long)
    companion object { private const val BUFFER_SIZE=1024*1024; private const val MAX_MAGIC=16 }
}

data class DeepProgress(val files:Long,val bytes:Long,val candidates:Int,val complete:Boolean=false)
