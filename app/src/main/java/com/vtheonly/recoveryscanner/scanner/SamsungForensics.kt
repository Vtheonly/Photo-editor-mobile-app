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

/** Samsung-specific forensic helpers. Operates only on bytes the app can actually read. */
class SamsungForensics(private val context: Context) {
    data class Embedded(val sourceUri: Uri, val sourceName: String, val type: MediaType, val offset: Long, val length: Long, val key: String, val quality: RecoveryQuality)

    companion object {
        val SAMSUNG_TRASH_ROOTS = listOf("/storage/emulated/0/Android/.Trash/com.sec.android.gallery3d", "/storage/emulated/0/DCIM/.trash", "/storage/emulated/0/DCIM/.Trash", "/storage/emulated/0/Pictures/.trash", "/storage/emulated/0/.Trash")
        val SAMSUNG_ARTIFACT_ROOTS = listOf("/storage/emulated/0/DCIM/.thumbnails", "/storage/emulated/0/Samsung", "/storage/emulated/0/Samsung/Samsung Notes", "/storage/emulated/0/SmartSwitch", "/storage/emulated/0/SmartSwitch/backup")
    }

    fun restoreName(file: File): String {
        if (!Regex("^\\d{15,20}$").matches(file.nameWithoutExtension) || !isImage(file)) return file.name
        return try {
            val exif = ExifInterface(file)
            val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (original.isNullOrBlank()) file.name else "${original.replace(":", "").replace(" ", "_")}_${file.name}.${file.extension}"
        } catch (_: Throwable) { file.name }
    }

    fun scanFile(file: File): List<Embedded> = if (file.isFile && file.canRead()) scanUri(Uri.fromFile(file), file.name, file) else emptyList()

    fun scanUri(uri: Uri, name: String, directFile: File? = null): List<Embedded> {
        val size = directFile?.length() ?: querySize(uri)
        if (size <= 0) return emptyList()
        val start = maxOf(0L, size - 128L * 1024L * 1024L)
        val data = readRange(uri, start, (size - start).toInt()) ?: return emptyList()
        val results = mutableListOf<Embedded>()
        val hasSeft = data.size >= 4 && asciiAtEnd(data, "SEFT")
        if (hasSeft) results += parseSefDirectory(uri, name, data, start, size)

        var p = 0
        while (p + 8 <= data.size) {
            val ftyp = indexOf(data, byteArrayOf(0x66, 0x74, 0x79, 0x70), p)
            if (ftyp < 4) break
            val localStart = ftyp - 4
            val length = parseIsoBmff(data, localStart)
            if (length != null) {
                val absolute = start + localStart
                if (absolute > 0 && results.none { it.offset == absolute && it.length == length }) results += Embedded(uri, name, MediaType.MP4, absolute, length, if (hasSeft) "MotionPhoto_Data / SEFT fallback" else "Embedded ISO-BMFF", RecoveryQuality.LIKELY_RECOVERABLE)
            }
            p = ftyp + 4
        }
        return results.distinctBy { "${it.offset}:${it.length}:${it.key}" }
    }

    private data class SefEntry(val key: String, val offset: Long, val length: Long)

    private fun parseSefDirectory(uri: Uri, name: String, data: ByteArray, globalStart: Long, fileSize: Long): List<Embedded> {
        val dirSize = u32(data, data.size - 8)
        if (dirSize <= 0 || dirSize > data.size - 8) return emptyList()
        val dirStart = data.size - 8 - dirSize.toInt()
        if (dirStart < 0) return emptyList()
        val directory = data.copyOfRange(dirStart, data.size - 8)
        val text = String(directory, Charsets.UTF_8)
        val keys = listOf("MotionPhoto_Data", "DualShot_ExtraImage", "DualShot_DepthMap")
        val result = mutableListOf<Embedded>()
        for (key in keys) {
            var at = 0
            while (true) {
                val found = text.indexOf(key, at)
                if (found < 0) break
                for (delta in -24..32 step 4) {
                    val pos = found + delta
                    if (pos < 0 || pos + 8 > directory.size) continue
                    val pairs = arrayOf(u32(directory, pos) to u32(directory, pos + 4), u32(directory, pos + 4) to u32(directory, pos))
                    for ((rawOffset, rawLength) in pairs) {
                        val absoluteOffset = when {
                            rawOffset < fileSize && rawOffset + rawLength <= fileSize -> rawOffset
                            globalStart + rawOffset < fileSize && globalStart + rawOffset + rawLength <= fileSize -> globalStart + rawOffset
                            else -> -1L
                        }
                        if (absoluteOffset < 0 || rawLength <= 16 || rawLength > fileSize - absoluteOffset) continue
                        val type = detectTypeAt(uri, absoluteOffset)
                        val accepted = when (key) {
                            "MotionPhoto_Data" -> type in setOf(MediaType.MP4, MediaType.MOV, MediaType.THREE_GP)
                            "DualShot_ExtraImage" -> type in setOf(MediaType.JPEG, MediaType.HEIC)
                            "DualShot_DepthMap" -> type != MediaType.UNKNOWN
                            else -> false
                        }
                        if (accepted) result += Embedded(uri, name, type, absoluteOffset, rawLength, key, RecoveryQuality.VALID)
                    }
                }
                at = found + key.length
            }
        }
        return result.distinctBy { "${it.offset}:${it.length}:${it.key}" }
    }

    private fun detectTypeAt(uri: Uri, offset: Long): MediaType = try {
        val bytes = readRange(uri, offset, 64 * 1024) ?: return MediaType.UNKNOWN
        SignatureRegistry.detect(bytes.inputStream(), "embedded")?.type ?: MediaType.UNKNOWN
    } catch (_: Throwable) { MediaType.UNKNOWN }

    private fun parseIsoBmff(data: ByteArray, start: Int): Long? {
        if (start < 0 || start + 8 > data.size) return null
        var p = start.toLong(); var sawFtyp = false; var sawMedia = false; var boxes = 0
        while (p + 8 <= data.size) {
            val pos = p.toInt(); val size32 = u32(data, pos); val type = String(data, pos + 4, 4, Charsets.US_ASCII)
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
            RandomAccessFile(uri.path!!, "r").use { raf -> raf.seek(offset); var done = 0; while (done < length) { val n = raf.read(out, done, length - done); if (n < 0) break; done += n }; if (done == length) out else out.copyOf(done) }
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> FileInputStream(pfd.fileDescriptor).use { input -> var skipped = 0L; while (skipped < offset) { val n = input.skip(offset - skipped); if (n <= 0) return null; skipped += n }; var done = 0; while (done < length) { val n = input.read(out, done, length - done); if (n < 0) break; done += n }; if (done == length) out else out.copyOf(done) } }
        }
    } catch (_: Throwable) { null }

    private fun querySize(uri: Uri): Long = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (_: Throwable) { 0L }
    private fun isImage(file: File) = file.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "heic", "heif")
    private fun asciiAtEnd(data: ByteArray, value: String) = data.size >= value.length && String(data, data.size - value.length, value.length, Charsets.US_ASCII) == value
    private fun u32(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    private fun u64(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int): Int { if (needle.isEmpty() || start >= data.size) return -1; outer@ for (i in start..data.size - needle.size) { for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer; return i }; return -1 }
}
