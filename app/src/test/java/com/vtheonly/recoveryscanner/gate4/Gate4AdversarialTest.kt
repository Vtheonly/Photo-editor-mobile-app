package com.vtheonly.recoveryscanner.gate4

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vtheonly.recoveryscanner.scanner.ArtifactMiner
import com.vtheonly.recoveryscanner.scanner.DeepContentScanner
import com.vtheonly.recoveryscanner.scanner.DirectStorageScanner
import com.vtheonly.recoveryscanner.scanner.MediaCarver
import com.vtheonly.recoveryscanner.scanner.MediaType
import com.vtheonly.recoveryscanner.scanner.RecoveryQuality
import com.vtheonly.recoveryscanner.scanner.SamsungForensics
import com.vtheonly.recoveryscanner.support.Fixtures
import com.vtheonly.recoveryscanner.support.Formats
import com.vtheonly.recoveryscanner.support.SAF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gate 4 - adversarial input, false positives, crash safety and archive security.
 * Covers plan Sections 27 (false positives), 40 (crash testing), 41 (security),
 * and the large-file stress aspects of Section 31.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Gate4AdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val miner = ArtifactMiner(context)
    private val forensics = SamsungForensics(context)
    private val carver = MediaCarver(context)

    private fun tempFileOf(name: String, bytes: ByteArray): File =
        createTempDir("g4-${name.hashCode().toUInt().toString(16)}").let { d ->
            File(d, name).apply { writeBytes(bytes) }
        }

    // ------------------------------------------------------------ Section 27: false positives

    @Test
    fun `section27 - deep scan of pure random data yields zero candidates`() = runBlocking {
        val dir = createTempDir("noise").apply { deleteOnExit() }
        File(dir, "random.bin").writeBytes(Formats.noise(2 * 1024 * 1024, seed = 77))
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        assertTrue("expected zero candidates, got ${results.size}", results.isEmpty())
    }

    @Test
    fun `section27 - planted signatures without structure never reach VALID`() = runBlocking {
        val planted = Formats.noise(64 * 1024, seed = 1) +
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +          // naked JPEG SOI
            Formats.noise(4096, seed = 2) +
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + // naked PNG sig
            Formats.noise(4096, seed = 3) +
            byteArrayOf(0, 0, 0, 32) + "ftypisom".toByteArray() +               // naked ftyp box
            Formats.noise(4096, seed = 4) +
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) +              // naked EBML magic
            Formats.noise(4096, seed = 5)
        val dir = createTempDir("planted").apply { deleteOnExit() }
        File(dir, "planted.bin").writeBytes(planted)
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        for (r in results) {
            assertTrue(
                "unstructured ${r.detectedType}@${r.offset} must not be VALID/PARTIAL-recoverable: ${r.quality}",
                r.quality == RecoveryQuality.SIGNATURE_ONLY || r.quality == RecoveryQuality.PARTIAL_OR_CORRUPT
            )
        }
        assertTrue("naked SOI/ftyp must at least produce PARTIAL candidates for triage", results.isNotEmpty())
    }

    @Test
    fun `section27 doc - planted RIFF-WEBP header in noise produces a false VALID`() {
        // Documents false-positive risk (D-18): a bare 12-byte RIFF....WEBP header planted in
        // unrelated data passes looksLikeWebp AND the carve trusts the (misread) declared
        // size, so the candidate is graded VALID with an absurd length.
        val fake = Formats.noise(256, seed = 9) +
            "RIFF".toByteArray() + byteArrayOf(0x10, 0x00, 0x00, 0x00) + "WEBP".toByteArray() +
            Formats.noise(64, seed = 10)
        val f = tempFileOf("fake.webp", fake)
        val r = carver.analyze(Uri.fromFile(f), 256, MediaType.WEBP, fake.size.toLong())
        assertEquals(RecoveryQuality.VALID, r.quality)
        assertTrue("absurd length accepted: ${r.length}", r.length > fake.size)
    }

    @Test
    fun `section27 doc - two-byte BMP signature floods deep scan with junk candidates`() = runBlocking {
        // Documents D-06 severity in deep-scan mode: 'BM' occurs constantly in ordinary
        // data, and the carve only sanity-checks three 32-bit header words. Crafted
        // headers (huge size, small pixel offset, plausible DIB size) sail through as
        // VALID with multi-gigabyte declared lengths inside a tiny file.
        val unit = byteArrayOf(0x42, 0x4D) +                      // 'BM'
            byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) +                 // declared size ~2 GB
            byteArrayOf(0, 0, 0, 0) +                             // reserved
            byteArrayOf(0x40, 0, 0, 0) +                          // pixel offset 64 < size
            byteArrayOf(0x28, 0, 0, 0) +                          // DIB size 40 >= 12
            "just some filler text to complete the header..".toByteArray()  // 36 bytes
        val blob = ByteArray(unit.size * 100) { unit[it % unit.size] }
        val dir = createTempDir("bm-flood").apply { deleteOnExit() }
        File(dir, "crafted.bin").writeBytes(blob)
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        val bmps = results.filter { it.detectedType == MediaType.BMP }
        assertTrue("expected a BMP candidate flood, got ${bmps.size}", bmps.size >= 90)
        val falseValid = bmps.filter { it.quality == RecoveryQuality.VALID && it.recoveredLength > blob.size }
        assertTrue("craft headers graded VALID with absurd lengths: ${falseValid.size} of ${bmps.size}",
            falseValid.size >= 80)
    }

    // ------------------------------------------------------------ Section 40: crash safety

    @Test
    fun `section40 - malformed inputs of every family never crash any scanner`() = runBlocking {
        val markerSoup = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
            Formats.noise(64 * 1024, seed = 11) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val pngGarbage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            Formats.noise(32 * 1024, seed = 12)
        val gifHeaderOnly = "GIF89a".toByteArray()
        val riffOnly = "RIFF".toByteArray()
        val ftypGarbage = byteArrayOf(0, 0, 0, 32) + "ftyp".toByteArray() + Formats.noise(2048, seed = 13)
        val ebmlGarbage = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + Formats.noise(2048, seed = 14)
        val sefGarbage = "SEFT".toByteArray() + "SEFH".toByteArray() + Formats.noise(1024, seed = 15)
        val hugeName = "x".repeat(200) + ".jpg"

        val dir = createTempDir("crash").apply { deleteOnExit() }
        val cases = mapOf(
            "marker_soup.jpg" to markerSoup,
            "png_garbage.png" to pngGarbage,
            "gif_header_only.gif" to gifHeaderOnly,
            "riff_only.webp" to riffOnly,
            "ftyp_garbage.mp4" to ftypGarbage,
            "ebml_garbage.mkv" to ebmlGarbage,
            "sef_garbage.jpg" to Fixtures.bytes("jpeg_baseline.jpg") + sefGarbage,
            hugeName to Fixtures.bytes("jpeg_small.jpg"),
            "zero_byte.jpg" to ByteArray(0),
            ".thumbdata3--broken" to Formats.noise(2048, seed = 16)
        )
        for ((name, bytes) in cases) {
            File(dir, name).writeBytes(bytes)
        }
        // If any of these throw, the test fails: one bad artifact must never kill a scan.
        val deep = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        for ((name, bytes) in cases) {
            val f = File(dir, name)
            miner.scanFile(f)
            forensics.scanFile(f)
            carver.analyze(Uri.fromFile(f), 0, MediaType.JPEG, bytes.size.toLong())
        }
        assertTrue(deep.isNotEmpty() || cases.isNotEmpty())
    }

    @Test
    fun `section40 doc - vanished source file crashes the carve and would kill the scan (D-19)`()
    {
        // CONFIRMED DEFECT (D-19): MediaCarver.analyze does not catch FileNotFoundException
        // from ContentResolver.openInputStream, and neither DeepContentScanner.visit nor
        // StorageScanner.visit wrap their per-file work in try/catch. A file deleted
        // between detection and carving therefore aborts the ENTIRE scan with an
        // uncaught exception (violates plan Sections 1 and 33).
        val gone = File(createTempDir("gone"), "deleted.jpg").apply { deleteOnExit() }
        gone.delete()
        assertTrue(forensics.scanUri(Uri.fromFile(gone), "deleted.jpg", null).isEmpty())
        assertTrue(miner.scanFile(gone).isEmpty())
        var threw = false
        try { carver.analyze(Uri.fromFile(gone), 0, MediaType.JPEG, 0) } catch (e: java.io.FileNotFoundException) { threw = true }
        assertTrue("carve of vanished file throws uncaught FileNotFoundException (defect D-19)", threw)
    }

    @Test
    fun `section40 - direct storage scanner is device-only under Robolectric`() {
        // Robolectric's Environment.isExternalStorageManager() shadow throws
        // ArrayIndexOutOfBoundsException, so DirectStorageScanner cannot be exercised on
        // the JVM at all. All-Files-Access traversal, symlink cycles and SD-card paths are
        // covered by the on-device checklist (plan Sections 2, 25, 34).
        var threw = false
        try { DirectStorageScanner(context).isAvailable() } catch (e: Throwable) { threw = true }
        assertTrue("expected Robolectric shadow limitation on isExternalStorageManager", threw)
    }

    @Test
    fun `section40 - malformed EXIF never breaks trash-name restoration`() {
        val brokenApp1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08) +             // APP1 claims 8 bytes, then garbage
            Formats.noise(64, seed = 17) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val f = tempFileOf("172640293012345678.jpg", brokenApp1)
        assertEquals("172640293012345678.jpg", forensics.restoreName(f))
    }

    // ------------------------------------------------------------ Section 31 (stress): large files

    @Test
    fun `section31 - 32MB stream with embedded media is scanned correctly without full-file loads`() = runBlocking {
        val j1 = Fixtures.bytes("jpeg_baseline.jpg")
        val j2 = Fixtures.bytes("jpeg_exif.jpg")
        val mp4 = Fixtures.bytes("mp4_mdat_first.mp4")
        val blob = Formats.noise(16 * 1024 * 1024, seed = 21) + j1 +
            Formats.noise(15 * 1024 * 1024, seed = 22) + j2 +
            Formats.noise(512 * 1024, seed = 23) + mp4 +
            Formats.noise(512 * 1024, seed = 24)
        assertEquals(32L * 1024 * 1024 + j1.size + j2.size + mp4.size, blob.size.toLong())
        val dir = createTempDir("big").apply { deleteOnExit() }
        File(dir, "huge.bin").writeBytes(blob)
        val t0 = System.currentTimeMillis()
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        val elapsed = System.currentTimeMillis() - t0
        val hit1 = results.first { it.detectedType == MediaType.JPEG && it.offset == 16L * 1024 * 1024 }
        val hit2 = results.first { it.detectedType == MediaType.JPEG && it.offset == 16L * 1024 * 1024 + j1.size + 15L * 1024 * 1024 }
        val hit3 = results.first { it.detectedType == MediaType.MP4 }
        assertEquals(j1.size.toLong(), hit1.recoveredLength)
        assertEquals(j2.size.toLong(), hit2.recoveredLength)
        assertEquals(mp4.size.toLong(), hit3.recoveredLength)
        assertTrue("deep scan of 32MB took ${elapsed}ms (streaming expected)", elapsed < 120_000)
    }

    // ------------------------------------------------------------ Section 41: security

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `section41 - nested zip-in-zip is not recursively extracted`() {
        val jpg = Fixtures.bytes("jpeg_baseline.jpg")
        val inner2 = zip("deep.jpg" to jpg)
        val inner1 = zip("inner2.zip" to inner2, "also-deep.jpg" to jpg)
        val outer = zip("inner1.zip" to inner1, "top.jpg" to jpg)
        val f = tempFileOf("nested.zip", outer)
        val artifacts = miner.scanFile(f).filter { it.source.startsWith("archive") }
        assertEquals("only top-level media entries are extracted (no recursion)", 1, artifacts.size)
        assertTrue(File(artifacts[0].uri.path!!).readBytes().contentEquals(jpg))
    }

    @Test
    fun `section41 - highly compressible archive entry extracts as a stream without OOM`() {
        // 64 MB of zeros compresses to a few KB; extraction streams to a temp file,
        // and the extracted content is correctly recognized as non-media garbage.
        val bomb = ByteArray(64 * 1024 * 1024)
        val f = tempFileOf("bomb.zip", zip("bomb.jpg" to bomb))
        val artifacts = miner.scanFile(f).filter { it.source.startsWith("archive") }
        assertTrue("zeros must not be recognized as media", artifacts.isEmpty())
    }

    @Test
    fun `section41 - absurd SEF metadata is rejected without overflow`() {
        val jpeg = Formats.jpeg(entropyBytes = 256)
        // SEFH count far beyond the accepted 4096 record limit
        val countBomb = Formats.samsungContainer(
            jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, Formats.mp4(64))),
            sefhCountOverride = 0x7FFFFFFFL
        )
        val f1 = tempFileOf("count_bomb.jpg", countBomb)
        assertTrue(forensics.scanFile(f1).none { it.length < 0 || it.length > countBomb.size })
        // directory record with absurd length must fail bounds check
        val lenBomb = Formats.samsungContainer(
            jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, Formats.mp4(64))),
            corruptDirRecord = true
        )
        val f2 = tempFileOf("len_bomb.jpg", lenBomb)
        val found = forensics.scanFile(f2)
        assertTrue(found.none { it.length > lenBomb.size })
        // absurd name-size field before a key string must be ignored (no huge allocation)
        val raw = jpeg + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "MotionPhoto_Data".toByteArray() + "SEFT".toByteArray() + "SEFH".toByteArray() +
            Formats.noise(64)
        val f3 = tempFileOf("namesize_bomb.jpg", raw)
        assertTrue(forensics.scanFile(f3).none { it.key == "MotionPhoto_Data" && it.length > raw.size })
    }

    @Test
    fun `section41 - MP4 with overflowing 64-bit box size does not hang or overflow`() {
        val mp4 = Formats.isoBmff(
            "isom",
            listOf(Formats.Box("mdat", 0, extended64 = true, sizeOverride = Long.MAX_VALUE / 2, fill = 0x11))
        )
        val f = tempFileOf("overflow.mp4", mp4)
        val r = carver.analyze(Uri.fromFile(f), 0, MediaType.MP4, mp4.size.toLong())
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
        assertTrue(r.length >= 0)
    }

    @Test
    fun `section41 - box size just under 4GB boundary is clamped to file size`() {
        val mp4 = Formats.isoBmff(
            "isom",
            listOf(Formats.Box("mdat", 0, sizeOverride = 0xFFFFFFFFL - 8, fill = 0x11), Formats.Box("moov", 32))
        )
        val f = tempFileOf("near4gb.mp4", mp4)
        val r = carver.analyze(Uri.fromFile(f), 0, MediaType.MP4, mp4.size.toLong())
        assertTrue("carve must not claim more than the file: ${r.length}", r.length <= mp4.size)
    }
}
