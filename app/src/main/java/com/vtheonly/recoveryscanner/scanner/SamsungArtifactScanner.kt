package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Samsung-focused secondary-stream discovery. It does not assume one undocumented
 * SEF layout; it first searches for structurally plausible ISO-BMFF streams after
 * the primary image and exposes them as candidates for later validation.
 */
class SamsungArtifactScanner(private val context: Context) {
    data class Embedded(
        val sourceUri: Uri,
        val sourceName: String,
        val type: MediaType,
        val offset: Long,
        val length: Long,
        val key: String,
        val quality: RecoveryQuality
    )

    fun scan(uri: Uri, name: String): List<Embedded> {
        val data = readTail(uri, 16 * 1024 * 1024) ?: return emptyList()
        val results = mutableListOf<Embedded>()

        // Samsung SEFT trailers are searched rather than hard-coded to one device generation.
        val seft = indexOf(data, byteArrayOf('S'.code.toByte(), 'E'.code.toByte(), 'F'.code.toByte(), 'T'.code.toByte()))
        if (seft >= 0) {
            // A nearby ftyp is a stronger indication of an appended Motion Photo stream.
            val searchStart = maxOf(0, seft - 16 * 1024 * 1024)
            val ftyp = indexOf(data, byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()), searchStart)
            if (ftyp >= 4) {
                val boxStart = ftyp - 4
                val parsed = parseIsoBmff(data, boxStart)
                if (parsed != null) {
                    results += Embedded(uri, name, MediaType.MP4, boxStart.toLong(), parsed, "Samsung Motion Photo / SEF", RecoveryQuality.LIKELY_RECOVERABLE)
                }
            }
        }

        // Also inspect appended ISO-BMFF streams even if the Samsung trailer is damaged.
        var from = 0
        while (true) {
            val ftyp = indexOf(data, byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()), from)
            if (ftyp < 4) break
            val start = ftyp - 4
            val parsed = parseIsoBmff(data, start)
            if (parsed != null && start > 0) {
                val duplicate = results.any { it.offset == start.toLong() }
                if (!duplicate) results += Embedded(uri, name, MediaType.MP4, start.toLong(), parsed, "Embedded ISO-BMFF", RecoveryQuality.LIKELY_RECOVERABLE)
            }
            from = ftyp + 4
        }
        return results
    }

    private fun parseIsoBmff(data: ByteArray, start: Int): Long? {
        var p = start.toLong()
        var boxes = 0
        var sawFtyp = false
        var sawMedia = false
        while (p + 8 <= data.size) {
            val pos = p.toInt()
            val size32 = u32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            val size = when (size32) {
                0L -> (data.size - pos).toLong()
                1L -> if (pos + 16 <= data.size) u64(data, pos + 8) else return null
                else -> size32
            }
            val header = if (size32 == 1L) 16 else 8
            if (size < header || pos.toLong() + size > data.size) return null
            if (type == "ftyp") sawFtyp = true
            if (type == "mdat" || type == "moov" || type == "meta") sawMedia = true
            p += size
            boxes++
            if (boxes > 4096) return null
            if (p >= data.size) break
        }
        if (!sawFtyp || !sawMedia) return null
        return p - start
    }

    private fun readTail(uri: Uri, maxBytes: Int): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytesLimited(maxBytes)
                bytes
            }
        } catch (_: Throwable) { null }
    }

    private fun InputStream.readBytesLimited(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 1024 * 1024))
        val buffer = ByteArray(1024 * 1024)
        var total = 0
        while (total < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun u64(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty()) return start
        outer@ for (i in start..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
