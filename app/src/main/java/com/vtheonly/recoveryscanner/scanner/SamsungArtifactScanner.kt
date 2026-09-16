package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Samsung-focused secondary-stream discovery. */
class SamsungArtifactScanner(private val context: Context) {
    data class Embedded(val sourceUri: Uri, val sourceName: String, val type: MediaType, val offset: Long, val length: Long, val key: String, val quality: RecoveryQuality)

    fun scan(uri: Uri, name: String): List<Embedded> {
        val data = readTail(uri, 32 * 1024 * 1024) ?: return emptyList()
        val results = mutableListOf<Embedded>()
        val seft = indexOf(data, ascii("SEFT"))
        var from = if (seft >= 0) maxOf(0, seft - 32 * 1024 * 1024) else 0
        while (true) {
            val ftyp = indexOf(data, ascii("ftyp"), from)
            if (ftyp < 4) break
            val start = ftyp - 4
            val length = parseIsoBmff(data, start)
            if (length != null && start > 0) {
                results += Embedded(uri, name, MediaType.MP4, start.toLong(), length, if (seft >= 0) "Samsung SEFT / Motion Photo" else "Embedded ISO-BMFF", RecoveryQuality.LIKELY_RECOVERABLE)
            }
            from = ftyp + 4
        }
        return results.distinctBy { "${it.offset}:${it.length}:${it.type}" }
    }

    private fun parseIsoBmff(data: ByteArray, start: Int): Long? {
        var p = start.toLong(); var boxes = 0; var sawFtyp = false; var sawMedia = false
        while (p + 8 <= data.size) {
            val pos = p.toInt(); val size32 = u32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            val size = when (size32) { 0L -> (data.size - pos).toLong(); 1L -> if (pos + 16 <= data.size) u64(data, pos + 8) else return null; else -> size32 }
            val header = if (size32 == 1L) 16 else 8
            if (size < header || pos.toLong() + size > data.size) return null
            if (type == "ftyp") sawFtyp = true
            if (type == "mdat" || type == "moov" || type == "meta") sawMedia = true
            p += size; boxes++
            if (boxes > 4096) return null
            if (p >= data.size) break
        }
        return if (sawFtyp && sawMedia) p - start else null
    }

    private fun readTail(uri: Uri, maxBytes: Int): ByteArray? = try {
        if (uri.scheme == "file") {
            FileInputStream(uri.path!!).use { input -> readTailFrom(input, uri.path!!.let { java.io.File(it).length() }, maxBytes) }
        } else {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { input -> readTailFrom(input, descriptor.statSize, maxBytes) } }
        }
    } catch (_: Throwable) { null }

    private fun readTailFrom(input: FileInputStream, size: Long, maxBytes: Int): ByteArray {
        val start = maxOf(0L, size - maxBytes)
        var skipped = 0L
        while (skipped < start) { val n = input.skip(start - skipped); if (n <= 0) break; skipped += n }
        val out = ByteArrayOutputStream(minOf(maxBytes.toLong(), size).toInt()); val buffer = ByteArray(1024 * 1024)
        while (out.size() < maxBytes) { val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size())); if (n <= 0) break; out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    private fun ascii(v: String) = v.toByteArray(Charsets.US_ASCII)
    private fun u32(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    private fun u64(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty() || start >= data.size) return -1
        outer@ for (i in start..data.size - needle.size) { for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer; return i }
        return -1
    }
}
