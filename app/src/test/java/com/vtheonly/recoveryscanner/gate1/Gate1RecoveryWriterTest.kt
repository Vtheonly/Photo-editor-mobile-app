package com.vtheonly.recoveryscanner.gate1

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vtheonly.recoveryscanner.scanner.MediaType
import com.vtheonly.recoveryscanner.scanner.RecoveryQuality
import com.vtheonly.recoveryscanner.scanner.RecoveryWriter
import com.vtheonly.recoveryscanner.scanner.ScanResult
import com.vtheonly.recoveryscanner.support.Fixtures
import com.vtheonly.recoveryscanner.support.Formats
import com.vtheonly.recoveryscanner.support.SAF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Gate 1 - recovery output correctness (plan Section 28): exact offset/length respect,
 * byte-identical output, duplicate names, refusal to destroy existing files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Gate1RecoveryWriterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val writer = RecoveryWriter(context)

    private fun cleanSource(name: String): File =
        createTempDir("src-${name.hashCode()}").let { File(it, name).apply { writeBytes(Fixtures.bytes(name)) } }

    private fun result(
        source: File, type: MediaType, ext: String, offset: Long, length: Long,
        quality: RecoveryQuality = RecoveryQuality.VALID
    ): ScanResult = ScanResult(
        sourceName = source.name,
        sourceUri = Uri.fromFile(source).toString(),
        detectedType = type,
        detectedExtension = ext,
        originalExtension = source.extension,
        sizeBytes = length,
        confidence = 99,
        isExtensionMismatch = false,
        isEmbeddedCandidate = offset > 0,
        offset = offset,
        recoveredLength = length,
        quality = quality
    )

    @Test
    fun `section28 - recovery writes byte-identical output for exact carve results`() {
        val source = cleanSource("jpeg_baseline.jpg")
        val dest = createTempDir("dest").apply { deleteOnExit() }
        val destTree = SAF.install(dest)
        val out = writer.recover(result(source, MediaType.JPEG, "jpg", 0, source.length()), destTree)
        assertNotNull(out)
        val recovered = File(dest, "jpeg_baseline.recovered.jpg")
        assertTrue(recovered.exists())
        assertEquals(Fixtures.sha256(Fixtures.bytes("jpeg_baseline.jpg")), Fixtures.sha256(recovered.readBytes()))
        assertEquals(source.length(), recovered.length())
    }

    @Test
    fun `section28 - embedded candidate recovery respects offset and length exactly`() {
        val j = Formats.jpeg(entropyBytes = 1024)
        val blob = Formats.noise(4096) + j + Formats.noise(2048)
        val source = File.createTempFile("blob", ".bin").apply { deleteOnExit(); writeBytes(blob) }
        val dest = createTempDir("dest2").apply { deleteOnExit() }
        val out = writer.recover(result(source, MediaType.JPEG, "jpg", 4096L, j.size.toLong()), SAF.install(dest))
        assertNotNull(out)
        val recovered = dest.listFiles()!!.first()
        assertEquals("embedded slice must be byte-identical", Fixtures.sha256(j), Fixtures.sha256(recovered.readBytes()))
        assertEquals("no preceding bytes", j.size.toLong(), recovered.length())
    }

    @Test
    fun `section28 - SIGNATURE_ONLY results are never written`() {
        val source = File.createTempFile("garbage", ".jpg").apply { deleteOnExit(); writeBytes(Formats.noise(1024)) }
        val dest = createTempDir("dest3").apply { deleteOnExit() }
        val out = writer.recover(result(source, MediaType.JPEG, "jpg", 0, 0, RecoveryQuality.SIGNATURE_ONLY), SAF.install(dest))
        assertNull(out)
        assertEquals(0, dest.listFiles()!!.size)
    }

    @Test
    fun `section28 - duplicate filenames get unique suffixes without destroying the first`() {
        val source = cleanSource("jpeg_baseline.jpg")
        val dest = createTempDir("dest4").apply { deleteOnExit() }
        val preExisting = File(dest, "jpeg_baseline.recovered.jpg").apply { writeBytes("precious".toByteArray()) }
        val destTree = SAF.install(dest)
        val out1 = writer.recover(result(source, MediaType.JPEG, "jpg", 0, source.length()), destTree)
        assertNotNull(out1)
        val recovered = dest.listFiles()!!.filter { it.name.startsWith("jpeg_baseline") }
        assertEquals("original destination file must survive untouched", "precious", File(dest, "jpeg_baseline.recovered.jpg").readText())
        assertEquals("expected original + uniquely-suffixed new file", 2, recovered.size)
        val suffixed = recovered.first { it.name != "jpeg_baseline.recovered.jpg" }
        assertTrue("expected -N suffix, got ${suffixed.name}", Regex("jpeg_baseline\\.recovered-\\d+\\.jpg").matches(suffixed.name))
    }

    @Test
    fun `section28 - recovery of mismatched extension uses detected extension in filename`() {
        val source = File.createTempFile("mystery", ".bin").apply { deleteOnExit(); writeBytes(Formats.png(128)) }
        val dest = createTempDir("dest5").apply { deleteOnExit() }
        val out = writer.recover(result(source, MediaType.PNG, "png", 0, source.length()), SAF.install(dest))
        assertNotNull(out)
        val name = dest.listFiles()!![0].name
        assertTrue("expected .recovered.png suffix, got $name", name.endsWith(".recovered.png"))
    }

    @Test
    fun `section28 - unsafe filename characters are sanitized`() {
        val source = File.createTempFile("weird", ".jpg").apply { deleteOnExit(); writeBytes(Formats.jpeg(64)) }
        source.delete(); File(source.parentFile, "we:ird*name?.jpg").writeBytes(Formats.jpeg(64))
        val bad = File(source.parentFile, "we:ird*name?.jpg")
        val dest = createTempDir("dest6").apply { deleteOnExit() }
        val out = writer.recover(result(bad, MediaType.JPEG, "jpg", 0, bad.length()), SAF.install(dest))
        assertNotNull(out)
        val name = dest.listFiles()!![0].name
        assertTrue("expected sanitized name, got $name", !name.contains(':') && !name.contains('*') && !name.contains('?'))
    }

    @Test
    fun `section28 - real fixture ground truth - all fixture types round-trip exactly`() {
        for (name in listOf("jpeg_exif.jpg", "png_large.png", "gif_animated.gif", "webp_animated.webp", "mp4_h264.mp4")) {
            val source = Fixtures.tempFile(name)
            val dest = createTempDir("rt-${name.replace('.', '-')}").apply { deleteOnExit() }
            val type = when {
                name.startsWith("jpeg") -> MediaType.JPEG
                name.startsWith("png") -> MediaType.PNG
                name.startsWith("gif") -> MediaType.GIF
                name.startsWith("webp") -> MediaType.WEBP
                else -> MediaType.MP4
            }
            val out = writer.recover(
                result(source, type, type.name.lowercase(), 0, source.length()),
                SAF.install(dest)
            )
            assertNotNull("recovery failed for $name", out)
            val recovered = dest.listFiles()!![0]
            assertEquals("SHA-256 mismatch for $name (ground truth)",
                Fixtures.sha256(Fixtures.bytes(name)), Fixtures.sha256(recovered.readBytes()))
        }
    }
}
