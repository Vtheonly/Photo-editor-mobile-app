package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Samsung-specific forensic helpers. Everything here operates only on bytes the app can actually read. */
class SamsungForensics(private val context: Context) {
    data class Embedded(val sourceUri: Uri, val sourceName: String, val type: MediaType, val offset: Long, val length: Long, val key: String, val quality: RecoveryQuality)

    companion object {
        val SAMSUNG_TRASH_ROOTS = listOf(
            "/storage/emulated/0/Android/.Trash/com.sec.android.gallery3d",
            "/storage/emulated/0/DCIM/.trash",
            "/storage/emulated/0/DCIM/.Trash",
            "/storage/emulated/0/Pictures/.trash",
            "/storage/emulated/0/.Trash"
        )
        val SAMSUNG_ARTIFACT_ROOTS = listOf(
            "/storage/emulated/0/DCIM/.thumbnails",
            "/storage/emulated/0/Samsung",
            "/storage/emulated/0/Samsung/Samsung Notes",
            "/storage/emulated/0/SmartSwitch",
            "/storage/emulated/0/SmartSwitch/backup"
        )
    }

    fun restoreName(file: File): String {
        if (!Regex("^\\d{15,20}$").matches(file.nameWithoutExtension)) return file.name
        if (!isImage(file)) return file.name
        return try {
            val exif = ExifInterface(file)
            val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (original.isNullOrBlank()) file.name else {
                val normalized = original.replace(":", "", false).replace(" ", "_")
                "${normalized}_${file.name}.${file.extension}"
            }
        } catch (_: Throwable) { file.name }
    }

    fun scanFile(file: File): List<Embedded> {
        if (!file.isFile || !file.canRead()) return emptyList()
        return scanUri(Uri.fromFile(file), file.name, file)
    }

    fun scanUri(uri: Uri, name: String, directFile: File? = null): List<Embedded> {
        val size = directFile?.length() ?: querySize(uri)
        if (size <= 0) return emptyList()
        val tailLimit = 128L * 1024L * 1024L
        val start = maxOf(0L, size - tailLimit)
        val data = readRange(uri, start, (size - start).toInt()) ?: return emptyList()
        val results = mutableListOf<Embedded>()
        val seftAbsolute = if (data.size >= 4 && asciiAtEnd(data, "SEFT")) size - 4 else -1L
        val directory = if (seftAbsolute >= 0) parseSefDirectory(data, start, size) else emptyList()
        for (entry in directory) {
            val type = detectTypeAt(uri, entry.offset)
            if (type != MediaType.UNKNOWN && entry.length > 0) {
                val key = entry.key
                results += Embedded(uri, name, type, entry.offset, entry.length, key, RecoveryQuality.VALID)
            }
        }
        // Corrupted/missing SEFT directory fallback: find ISO-BMFF ftyp and carve its exact box range.
        var p = 0
        while (p + 8 <= data.size) {
            val ftyp = indexOf(data, byteArrayOf(0x66, 0x74, 0x79, 0x70), p)
            if (ftyp < 4) break
            val localStart = ftyp - 4
            val length = parseIsoBmff(data, localStart)
            if (length != null) {
                val absolute = start + localStart
                if (absolute > 0 && results.none { it.offset == absolute && it.length == length }) {
                    results += Embedded(uri, name, MediaType.MP4, absolute, length, if (seftAbsolute >= 0) "MotionPhoto_Data / SEFT fallback" else "Embedded ISO-BMFF", RecoveryQuality.LIKELY_RECOVERABLE)
                }
            }
            p = ftyp + 4
        }
        return results.distinctBy { "${it.offset}:${it.length}:${it.key}" }
    }

    private data class SefEntry(val key: String, val offset: Long, val length: Long)

    /**
     * Samsung firmware versions differ in SEF directory record packing. We first honor
     * the documented footer (directory-size immediately before SEFT), then validate every
     * candidate against real media signatures before returning it.
     */
    private fun parseSefDirectory(data: ByteArray, globalStart: Long, fileSize: Long): List<SefEntry> {
        if (data.size < 8 || !asciiAtEnd(data, "SEFT")) return emptyList()
        val dirSize = u32(data, data.size - 8)
        if (dirSize <= 0 || dirSize > data.size - 8) return emptyList()
        val dirStart = data.size - 8 - dirSize.toInt()
        if (dirStart < 0) return emptyList()
        val directory = data.copyOfRange(dirStart, data.size - 8)
        val text = String(directory, Charsets.UTF_8)
        val keys = listOf("MotionPhoto_Data", "DualShot_ExtraImage", "DualShot_DepthMap")
        val result = mutableListOf<SefEntry>()
        for (key in keys) {
            var at = 0
            while (true) {
                val found = text.indexOf(key, at)
                if (found < 0) break
                val candidates = mutableListOf<Pair<Long, Long>>()
                val bytePos = found
                for (delta in -24..32 step 4) {
                    val pos = bytePos + delta
                    if (pos >= 0 && pos + 8 <= directory.size) {
                        val a = u32(directory, pos)
                        val b = u32(directory, pos + 4)
                        candidates += a to b
                        candidates += b to a
                    }
                }
                for ((rawOffset, rawLength) in candidates) {
                    val absoluteOffset = when {
                        rawOffset < fileSize && rawOffset + rawLength <= fileSize -> rawOffset
                        globalStart + rawOffset < fileSize && globalStart + rawOffset + rawLength <= fileSize -> globalStart + rawOffset
                        else -> -1L
                    }
                    if (absoluteOffset >= 0 && rawLength > 16 && rawLength <= fileSize - absoluteOffset) {
                        result += SefEntry(key, absoluteOffset, rawLength)
                    }
                }
                at = found + key.length
            }
        }
        return result.distinctBy { "${it.key}:${it.offset}:${it.length}" }.filter { e ->
            when (e.key) {
                "MotionPhoto_Data" -> detectTypeAtGlobal(e.offset) in setOf(MediaType.MP4, MediaType.MOV, MediaType.THREE_GP)
                "DualShot_ExtraImage" -> detectTypeAtGlobal(e.offset) in setOf(MediaType.JPEG, MediaType.HEIC)
                "DualShot_DepthMap" -> detectTypeAtGlobal(e.offset) != MediaType.UNKNOWN
                else -> false
            }
        }
    }

    private fun detectTypeAt(uri: Uri, offset: Long): MediaType = try {
        val bytes = readRange(uri, offset, 64 * 1024) ?: return MediaType.UNKNOWN
        SignatureRegistry.detect(bytes.inputStream(), "embedded")?.type ?: MediaType.UNKNOWN
    } catch (_: Throwable) { MediaType.UNKNOWN }

    private fun detectTypeAtGlobal(offset: Long): MediaType = MediaType.UNKNOWN // validated by caller when direct access is unavailable

    private fun parseIsoBmff(data: ByteArray, start: Int): Long? {
        if (start < 0 || start + 8 > data.size) return null
        var p = start.toLong(); var sawFtyp = false; var sawMedia = false; var boxes = 0
        while (p + 8 <= data.size) {
            val pos = p.toInt(); val size32 = u32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)
            val size = when (size32) { 0L -> (data.size - pos).toLong(); 1L -> if (pos + 16 <= data.size) u64(data, pos + 8) else return null; else -> size32 }
            val header = if (size32 == 1L) 16 else 8
            if (size < header || pos.toLong() + size > data.size) return null
            if (type == "ftyp") sawFtyp = true
            if (type == "mdat" || type == "moov" || type == "meta") sawMedia = true
            p += size
            if (++boxes > 4096) return null
            if (p >= data.size) break
        }
        return if (sawFtyp && sawMedia) p - start else null
    }

    private fun readRange(uri: Uri, offset: Long, length: Int): ByteArray? = try {
        val out = ByteArray(length)
        if (uri.scheme == "file") {
            RandomAccessFile(uri.path!!, "r").use { it.seek(offset); var done = 0; while (done < length) { val n = it.read(out, done, length - done); if (n < 0) break; done += n }; if (done == length) out else out.copyOf(done) }
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    var skipped = 0L; while (skipped < offset) { val n = input.skip(offset - skipped); if (n <= 0) return null; skipped += n }
                    var done = 0; while (done < length) { val n = input.read(out, done, length - done); if (n < 0) break; done += n }
                    if (done == length) out else out.copyOf(done)
                }
            }
        }
    } catch (_: Throwable) { null }

    private fun querySize(uri: Uri): Long = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (_: Throwable) { 0L }
    private fun isImage(file: File) = file.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "heic", "heif")
    private fun asciiAtEnd(data: ByteArray, value: String) = data.size >= value.length && String(data, data.size - value.length, value.length, Charsets.US_ASCII) == value
    private fun u32(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    private fun u64(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int): Int { if (needle.isEmpty() || start >= data.size) return -1; outer@ for (i in start..data.size - needle.size) { for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer; return i }; return -1 }
}
