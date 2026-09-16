package com.vtheonly.recoveryscanner.gate1

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vtheonly.recoveryscanner.scanner.MediaType
import com.vtheonly.recoveryscanner.scanner.SignatureRegistry
import com.vtheonly.recoveryscanner.scanner.StorageScanner
import com.vtheonly.recoveryscanner.support.Fixtures
import com.vtheonly.recoveryscanner.support.Formats
import com.vtheonly.recoveryscanner.support.SAF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Gate 1 - basic correctness of signature detection and extension-mismatch handling.
 * Covers plan Sections 4 (basic file detection) and 5 (extension mismatch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Gate1DetectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun detect(bytes: ByteArray, name: String = "probe.bin") =
        SignatureRegistry.detect(ByteArrayInputStream(bytes), name)

    // ------------------------------------------------------------ Section 4: detection matrix

    @Test
    fun `section4 - real image fixtures are detected with correct type and extension`() {
        val matrix = mapOf(
            "jpeg_baseline.jpg" to MediaType.JPEG, "jpeg_progressive.jpg" to MediaType.JPEG,
            "jpeg_exif.jpg" to MediaType.JPEG, "jpeg_large.jpg" to MediaType.JPEG,
            "jpeg_small.jpg" to MediaType.JPEG,
            "png_rgba.png" to MediaType.PNG, "png_large.png" to MediaType.PNG,
            "gif87a_single.gif" to MediaType.GIF, "gif89a_single.gif" to MediaType.GIF,
            "gif_animated.gif" to MediaType.GIF,
            "webp_lossy.webp" to MediaType.WEBP, "webp_lossless.webp" to MediaType.WEBP,
            "webp_extended.webp" to MediaType.WEBP, "webp_animated.webp" to MediaType.WEBP,
            "bmp_24bit.bmp" to MediaType.BMP,
            "mp4_h264.mp4" to MediaType.MP4, "mp4_mdat_first.mp4" to MediaType.MP4,
            "mp4_hevc.mp4" to MediaType.MP4, "mp4_large.mp4" to MediaType.MP4,
            "mov_qt.mov" to MediaType.MOV, "video_3gp.3gp" to MediaType.THREE_GP,
            "video.mkv" to MediaType.MKV, "video.webm" to MediaType.WEBM
        )
        for ((name, expected) in matrix) {
            val d = detect(Fixtures.bytes(name), name)
            assertNotNull("no detection for $name", d)
            assertEquals("type mismatch for $name", expected, d!!.type)
            val expectedExt = when (expected) {
                MediaType.JPEG -> "jpg"; MediaType.THREE_GP -> "3gp"; else -> expected.name.lowercase()
            }
            assertEquals("extension mismatch for $name", expectedExt, d.extension)
        }
    }

    @Test
    fun `section4 - HEIC and HEIF brands map to HEIC`() {
        for (name in listOf("heic_brand.heic", "heix_brand.heic", "mif1_brand.heif", "msf1_brand.heif")) {
            val d = detect(Fixtures.bytes(name), name)
            assertNotNull("no detection for $name", d)
            assertEquals("brand not mapped to HEIC for $name", MediaType.HEIC, d!!.type)
        }
    }

    @Test
    fun `section4 - confidence is reported for header detections`() {
        val jpeg = detect(Fixtures.bytes("jpeg_baseline.jpg"))
        assertEquals(99, jpeg!!.confidence)
        val embedded = detect(Formats.noise(1024) + Formats.jpeg(entropyBytes = 64))
        assertTrue("embedded JPEG confidence expected 88, was ${embedded?.confidence}", embedded!!.confidence == 88)
    }

    @Test
    fun `section4 - random data without signatures is not detected`() {
        assertNull(detect(Formats.noise(4096)))
        assertNull(detect(ByteArray(0)))
    }

    // ------------------------------------------------------------ embedded detection

    @Test
    fun `section4 - JPEG embedded in unrelated binary is detected with exact offset`() {
        val prefix = Formats.noise(2048)
        val jpeg = Formats.jpeg(entropyBytes = 512)
        val d = detect(prefix + jpeg)
        assertNotNull(d)
        assertEquals(MediaType.JPEG, d!!.type)
        assertEquals("embedded offset wrong", prefix.size.toLong(), d.offset)
        assertTrue("embedded candidate flag expected true", d.offset > 0)
    }

    @Test
    fun `section4 - PNG embedded in unrelated binary is detected with exact offset`() {
        val prefix = Formats.noise(517)
        val png = Formats.png(idatBytes = 128)
        val d = detect(prefix + png)
        assertNotNull(d)
        assertEquals(MediaType.PNG, d!!.type)
        assertEquals(prefix.size.toLong(), d!!.offset)
    }

    @Test
    fun `section4 - detection window is limited to the first 64KB`() {
        // JPEG magic far beyond the 64 KB read window must not be reported at header level
        val big = Formats.noise(200 * 1024) + Formats.jpeg(entropyBytes = 64)
        assertNull(detect(big))
    }

    // ------------------------------------------------------------ Section 5: extension mismatch

    @Test
    fun `section5 - content type wins over filename through the normal scan`() = runBlocking {
        val mismatches = mapOf(
            "image.jpg" to Formats.png(64),       // named .jpg, actually PNG
            "image.png" to Formats.jpeg(64),      // named .png, actually JPEG
            "image.mp4" to Formats.jpeg(64),      // named .mp4, actually JPEG
            "image.jpg2.mp4" to Formats.mp4(64),  // named .mp4, actually MP4 (control)
            "random.bin" to Formats.jpeg(64),     // no known ext, JPEG inside
            "123456" to Formats.png(64),          // no extension at all
            "no_extension" to Formats.webp(payloadBytes = 64)    // no extension, WebP inside
        )
        val dir = createTempDir("mismatch").apply { deleteOnExit() }
        mismatches.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        val tree = SAF.install(dir)
        val results = StorageScanner(context).scanTree(tree) { }
        assertEquals("expected one result per file", mismatches.size, results.size)
        for ((name, bytes) in mismatches) {
            val r = results.first { it.sourceName == name }
            val expectedType = detect(bytes)!!.type
            assertEquals("content type must win for $name", expectedType, r.detectedType)
            val originalExt = if (name.contains('.')) name.substringAfterLast('.') else ""
            val expectedMismatch = originalExt.isNotEmpty() && originalExt != r.detectedExtension
            assertEquals("isExtensionMismatch wrong for $name", expectedMismatch, r.isExtensionMismatch)
        }
    }

    @Test
    fun `section5 - original extension is preserved as metadata`() = runBlocking {
        val dir = createTempDir("meta").apply { deleteOnExit() }
        File(dir, "photo.jpg").writeBytes(Formats.png(32))   // mismatch: png content, jpg name
        File(dir, "photo.png").writeBytes(Formats.png(32))   // match
        val results = StorageScanner(context).scanTree(SAF.install(dir)) { }
        val mismatch = results.first { it.sourceName == "photo.jpg" }
        assertEquals("jpg", mismatch.originalExtension)
        assertEquals("png", mismatch.detectedExtension)
        val match = results.first { it.sourceName == "photo.png" }
        assertEquals("png", match.originalExtension)
        assertEquals("png", match.detectedExtension)
        assertTrue(!match.isExtensionMismatch)
    }

    // ------------------------------------------------------------ Section 3: folder behaviors

    @Test
    fun `section3 - empty folder scan returns no results without crashing`() = runBlocking {
        val dir = createTempDir("empty").apply { deleteOnExit() }
        val results = StorageScanner(context).scanTree(SAF.install(dir)) { }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `section3 - nested directory tree is fully traversed`() = runBlocking {
        val dir = createTempDir("nested").apply { deleteOnExit() }
        File(dir, "DCIM/Camera").mkdirs()
        File(dir, "DCIM/Camera/a.jpg").writeBytes(Formats.jpeg(32))
        File(dir, "DCIM/b.png").writeBytes(Formats.png(32))
        File(dir, "c.gif").writeBytes(Formats.gif())
        val results = StorageScanner(context).scanTree(SAF.install(dir)) { }
        assertEquals(setOf("a.jpg", "b.png", "c.gif"), results.map { it.sourceName }.toSet())
    }

    @Test
    fun `section28 doc - normal scan results are SIGNATURE_ONLY and can never be recovered`() = runBlocking {
        // CONFIRMED DEFECT (D-07): StorageScanner builds ScanResult without recoveredLength
        // or quality, so every normal-scan candidate defaults to SIGNATURE_ONLY. The UI
        // disables those checkboxes and RecoveryWriter refuses SIGNATURE_ONLY results:
        // the normal scan can list files but the user can never recover any of them.
        val dir = createTempDir("deadend").apply { deleteOnExit() }
        File(dir, "photo.jpg").writeBytes(Formats.jpeg(64))
        val results = StorageScanner(context).scanTree(SAF.install(dir)) { }
        assertEquals(1, results.size)
        assertEquals(com.vtheonly.recoveryscanner.scanner.RecoveryQuality.SIGNATURE_ONLY, results[0].quality)
        val dest = createTempDir("dest7").apply { deleteOnExit() }
        val out = com.vtheonly.recoveryscanner.scanner.RecoveryWriter(context)
            .recover(results[0], SAF.install(dest))
        assertTrue("normal-scan candidate must be recoverable in principle, writer returned $out", out == null)
        assertEquals(0, dest.listFiles()!!.size)
    }

    // ------------------------------------------------------------ gap documentation (asserting current behavior)

    @Test
    fun `section27 doc - any file starting with the two bytes BM is detected as BMP`() {
        // Documents a false-positive risk: "BM" is only a 2-byte signature.
        val arbitraryText = "BM Hello, I am a text file about BitMaps.\n".toByteArray()
        val d = detect(arbitraryText)
        assertNotNull("2-byte BM signature accepted arbitrary text as BMP", d)
        // NOTE: asserting CURRENT behavior to document the risk (see defect report D-06).
        assertEquals(MediaType.BMP, d!!.type)
    }

    @Test
    fun `section12 doc - RIFF container that is not WEBP is not misdetected`() {
        val aviLike = "RIFF".toByteArray() + byteArrayOf(0x10, 0, 0, 0) + "AVI ".toByteArray() + Formats.noise(64)
        assertNull(detect(aviLike))
    }
}
