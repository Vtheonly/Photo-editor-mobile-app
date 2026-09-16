package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Finds media that ordinary MediaStore enumeration can miss while remaining within
 * Android's user-granted storage boundaries. It deliberately does not claim access
 * to another application's private sandbox.
 */
class ArtifactMiner(private val context: Context) {
    data class Artifact(
        val uri: Uri,
        val displayName: String,
        val type: MediaType,
        val offset: Long = 0L,
        val length: Long = 0L,
        val source: String,
        val quality: RecoveryQuality = RecoveryQuality.LIKELY_RECOVERABLE
    )

    fun scanTree(treeUri: Uri, onProgress: (String) -> Unit): List<Artifact> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<Artifact>()
        walk(root, out, onProgress)
        return out.distinctBy { "${it.uri}|${it.offset}|${it.length}|${it.type}" }
    }

    private fun walk(dir: DocumentFile, out: MutableList<Artifact>, progress: (String) -> Unit) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                // .nomedia is a MediaScanner instruction, not a recovery instruction.
                walk(file, out, progress)
                continue
            }
            val name = file.name ?: "unnamed"
            progress(name)
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                val size = file.length()
                val detection = SignatureRegistry.detect(input, name)
                if (detection != null) {
                    out += Artifact(file.uri, name, detection.type, detection.offset, size, "filesystem")
                }
                // Legacy Samsung/Android thumbnail stores are often extensionless binary blobs.
                if (name.startsWith(".thumbdata", ignoreCase = true) || name.contains("thumbdata", true)) {
                    out += carveJpegs(file.uri, name, "thumbdata")
                }
            }
        }
    }

    fun carveJpegs(uri: Uri, sourceName: String, source: String = "binary-carve"): List<Artifact> {
        val results = mutableListOf<Artifact>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val stream = BufferedInputStream(input, 1024 * 1024)
            val buffer = ByteArray(1024 * 1024)
            var carry = ByteArray(0)
            var absolute = 0L
            var candidateStart = -1L
            var candidate = ByteArrayOutputStream()
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                val combined = ByteArray(carry.size + n)
                System.arraycopy(carry, 0, combined, 0, carry.size)
                System.arraycopy(buffer, 0, combined, carry.size, n)
                val base = absolute - carry.size
                var i = 0
                while (i < combined.size) {
                    if (candidateStart < 0 && i + 2 < combined.size &&
                        combined[i].toInt() and 0xFF == 0xFF && combined[i + 1].toInt() and 0xFF == 0xD8 && combined[i + 2].toInt() and 0xFF == 0xFF) {
                        candidateStart = base + i
                        candidate.reset()
                    }
                    if (candidateStart >= 0) {
                        candidate.write(combined[i].toInt())
                        if (candidate.size() > 12 * 1024 * 1024) {
                            candidateStart = -1L
                            candidate.reset()
                        } else if (i > 0 && combined[i - 1].toInt() and 0xFF == 0xFF && combined[i].toInt() and 0xFF == 0xD9) {
                            val bytes = candidate.toByteArray()
                            if (bytes.size >= 10 * 1024 && BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) {
                                results += Artifact(uri, "$sourceName-jpeg-${results.size + 1}.jpg", MediaType.JPEG, candidateStart, bytes.size.toLong(), source, RecoveryQuality.VALID)
                            }
                            candidateStart = -1L
                            candidate.reset()
                        }
                    }
                    i++
                }
                absolute += n
                carry = combined.takeLast(2).toByteArray()
            }
        }
        return results
    }

    /** Extracts embedded EXIF thumbnails from a user-accessible image. */
    fun extractExifThumbnail(uri: Uri, name: String): ByteArray? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                exif.thumbnail
            }
        } catch (_: Throwable) { null }
    }

    /** Parses accessible .sdocx/ZIP-based artifacts without renaming the source file. */
    fun listEmbeddedZipImages(uri: Uri, name: String): List<String> {
        val temp = File.createTempFile("recovery-archive-", ".zip", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ZipFile(temp).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.startsWith("media/", true) || it.name.startsWith("images/", true)) }
                    .filter { it.name.lowercase(Locale.US).matches(".*\\.(jpg|jpeg|png|webp|heic|gif)$") }
                    .map { it.name }.toList()
            }
        } catch (_: Throwable) { emptyList() }
        finally { temp.delete() }
    }

    /** Best-effort frame extraction from a complete or partially readable video file. */
    fun extractVideoFrame(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val output = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
            bitmap.recycle()
            output.toByteArray()
        } catch (_: Throwable) { null }
        finally { retriever.release() }
    }
}
