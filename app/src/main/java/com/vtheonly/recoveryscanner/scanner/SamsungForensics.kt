package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.Locale

/** Samsung-specific forensic helpers for SEF/SEFT, Motion Photo and auxiliary camera payloads. */
class SamsungForensics(private val context: Context) {
    data class Embedded(val sourceUri: Uri, val sourceName: String, val type: MediaType, val offset: Long, val length: Long, val key: String, val quality: RecoveryQuality)
    companion object { val SAMSUNG_TRASH_ROOTS = listOf("/storage/emulated/0/Android/.Trash/com.sec.android.gallery3d", "/storage/emulated/0/DCIM/.trash", "/storage/emulated/0/DCIM/.Trash", "/storage/emulated/0/Pictures/.trash", "/storage/emulated/0/.Trash"); val SAMSUNG_ARTIFACT_ROOTS = listOf("/storage/emulated/0/DCIM/.thumbnails", "/storage/emulated/0/Samsung", "/storage/emulated/0/Samsung/Samsung Notes", "/storage/emulated/0/SmartSwitch", "/storage/emulated/0/SmartSwitch/backup") }
    fun restoreName(file: File): String { if (!Regex("^\\d{15,20}$").matches(file.nameWithoutExtension) || !isImage(file)) return file.name; return try { val exif = ExifInterface(file); val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME); if (original.isNullOrBlank()) file.name else "${original.replace(":", "").replace(" ", "_")}_${file.name}.${file.extension}" } catch (_: Throwable) { file.name } }
    fun scanFile(file: File): List<Embedded> = if (file.isFile && file.canRead()) scanUri(Uri.fromFile(file), file.name, file) else emptyList()
    fun scanUri(uri: Uri, name: String, directFile: File? = null): List<Embedded> {
        val size = directFile?.length() ?: querySize(uri); if (size <= 0) return emptyList(); val start = maxOf(0L, size - 128L * 1024L * 1024L); val tail = readRange(uri, start, (size - start).toInt()) ?: return emptyList(); val results = mutableListOf<Embedded>()
        val seft = indexOf(tail, ascii("SEFT"), 0); val sefh = indexOf(tail, ascii("SEFH"), 0); if (seft >= 0) results += parseSef(uri, name, tail, start, size, sefh)
        var search = 0
        while (search + 8 <= tail.size) { val ftyp = indexOf(tail, ascii("ftyp"), search); if (ftyp < 4) break; val localStart = ftyp - 4; val absolute = start + localStart; val boundary = when { sefh > localStart -> start + sefh; seft > localStart -> start + seft; else -> size }; val length = boundary - absolute; val header = readRange(uri, absolute, 64) ?: ByteArray(0); val detected = SignatureRegistry.detect(header.inputStream(), "embedded.mp4")?.type; if (length > 1024 && detected in setOf(MediaType.MP4, MediaType.MOV, MediaType.THREE_GP) && results.none { it.offset == absolute && it.length == length }) results += Embedded(uri, name, MediaType.MP4, absolute, length, if (seft >= 0) "MotionPhoto_Data / SEFT fallback" else "Embedded ISO-BMFF", RecoveryQuality.LIKELY_RECOVERABLE); search = ftyp + 4 }
        return results.distinctBy { "${it.offset}:${it.length}:${it.key}" }
    }
    private fun parseSef(uri: Uri, name: String, tail: ByteArray, globalStart: Long, fileSize: Long, sefhLocal: Int): List<Embedded> {
        val sefh = if (sefhLocal >= 0) globalStart + sefhLocal else -1L; val result = mutableListOf<Embedded>()
        if (sefh >= 0 && sefhLocal + 12 <= tail.size) {
            val version = le32(tail, sefhLocal + 4); val count = le32(tail, sefhLocal + 8)
            if (count in 1L..4096L && version in 1L..10000L) {
                var p = sefhLocal + 12
                repeat(count.toInt()) { if (p + 12 > tail.size) return@repeat; val marker = le16(tail, p + 2); val offsetBack = le32(tail, p + 4); val length = le32(tail, p + 8); val absolute = sefh - offsetBack; if (absolute >= 0 && length > 16 && absolute + length <= fileSize) { val type = detectTypeAt(uri, absolute); val key = if (marker == 0x0A30L) "MotionPhoto_Data" else "SEF field 0x${marker.toString(16)}"; if (type != MediaType.UNKNOWN || marker == 0x0A30L) result += Embedded(uri, name, if (type == MediaType.UNKNOWN) MediaType.MP4 else type, absolute, length, key, RecoveryQuality.VALID) }; p += 12 }
            }
        }
        for (key in listOf("MotionPhoto_Data", "DualShot_ExtraImage", "DualShot_DepthMap")) { var at = 0; while (true) { val found = indexOf(tail, ascii(key), at); if (found < 0) break; val candidate = nearestPayload(uri, tail, globalStart, fileSize, sefhLocal, found); if (candidate != null) result += Embedded(uri, name, candidate.first, candidate.second, candidate.third, key, RecoveryQuality.VALID); at = found + key.length } }
        return result.distinctBy { "${it.offset}:${it.length}:${it.key}" }
    }
    private fun nearestPayload(uri: Uri, tail: ByteArray, globalStart: Long, fileSize: Long, sefhLocal: Int, keyPos: Int): Triple<MediaType, Long, Long>? {
        val nameSize = if (keyPos >= 4) le32(tail, keyPos - 4).toInt() else 0
        if (nameSize > 0 && nameSize <= 1024) { val payloadStart = globalStart + keyPos + nameSize; val end = if (sefhLocal >= 0) globalStart + sefhLocal else fileSize; if (payloadStart >= 0 && end > payloadStart && end <= fileSize) { val type = detectTypeAt(uri, payloadStart); if (type != MediaType.UNKNOWN) return Triple(type, payloadStart, end - payloadStart) } }
        val ftyp = indexOf(tail, ascii("ftyp"), keyPos); if (ftyp >= 4) { val payloadStart = globalStart + ftyp - 4; val end = if (sefhLocal >= 0) globalStart + sefhLocal else fileSize; if (end > payloadStart) return Triple(MediaType.MP4, payloadStart, end - payloadStart) }
        val jpeg = indexOf(tail, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), keyPos); if (jpeg >= 0) { val payloadStart = globalStart + jpeg; val end = if (sefhLocal >= 0) globalStart + sefhLocal else fileSize; if (end > payloadStart) return Triple(MediaType.JPEG, payloadStart, end - payloadStart) }; return null
    }
    private fun detectTypeAt(uri: Uri, offset: Long): MediaType = try { val bytes = readRange(uri, offset, 64 * 1024) ?: return MediaType.UNKNOWN; SignatureRegistry.detect(bytes.inputStream(), "embedded")?.type ?: MediaType.UNKNOWN } catch (_: Throwable) { MediaType.UNKNOWN }
    private fun readRange(uri: Uri, offset: Long, length: Int): ByteArray? = try { val out = ByteArray(length); if (uri.scheme == "file") RandomAccessFile(uri.path!!, "r").use { raf -> raf.seek(offset); var done = 0; while (done < length) { val n = raf.read(out, done, length - done); if (n < 0) break; done += n }; if (done == length) out else out.copyOf(done) } else context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> FileInputStream(pfd.fileDescriptor).use { input -> var skipped = 0L; while (skipped < offset) { val n = input.skip(offset - skipped); if (n <= 0) return null; skipped += n }; var done = 0; while (done < length) { val n = input.read(out, done, length - done); if (n < 0) break; done += n }; if (done == length) out else out.copyOf(done) } } } catch (_: Throwable) { null }
    private fun querySize(uri: Uri): Long = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (_: Throwable) { 0L }
    private fun isImage(file: File) = file.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "heic", "heif")
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun le16(b: ByteArray, p: Int): Long = (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8)
    private fun le32(b: ByteArray, p: Int): Long = (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int): Int { if (needle.isEmpty() || start >= data.size) return -1; outer@ for (i in start..data.size - needle.size) { for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer; return i }; return -1 }
}
