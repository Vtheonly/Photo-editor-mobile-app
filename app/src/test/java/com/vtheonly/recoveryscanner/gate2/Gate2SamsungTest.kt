package com.vtheonly.recoveryscanner.gate2

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vtheonly.recoveryscanner.scanner.ArtifactMiner
import com.vtheonly.recoveryscanner.scanner.MediaType
import com.vtheonly.recoveryscanner.scanner.RecoveryQuality
import com.vtheonly.recoveryscanner.scanner.SamsungArtifactScanner
import com.vtheonly.recoveryscanner.scanner.SamsungForensics
import com.vtheonly.recoveryscanner.support.Fixtures
import com.vtheonly.recoveryscanner.support.Formats
import com.vtheonly.recoveryscanner.support.SAF
import com.vtheonly.recoveryscanner.scanner.StorageScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gate 2 - Samsung-specific recovery mechanisms.
 * Covers plan Sections 7 (.thumbdata carving), 17 (Gallery trash naming), 18-19 (Motion Photo
 * and corrupted SEF fallback), 20-21 (DualShot ExtraImage / DepthMap), 22-23 (Samsung Notes /
 * Smart Switch archives), 24 (cache recovery).
 *
 * NATIVE graphics: ArtifactMiner's thumbdata gate calls BitmapFactory.decodeByteArray for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Gate2SamsungTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val forensics = SamsungForensics(context)
    private val miner = ArtifactMiner(context)

    private fun tempFileOf(name: String, bytes: ByteArray): File =
        createTempDir("g2-${name.hashCode().toUInt().toString(16)}").let { d ->
            File(d, name).apply { writeBytes(bytes) }
        }

    // ------------------------------------------------------------ Section 17: trash filename restoration

    @Test
    fun `section17 doc - restored trash name carries a doubled extension`() {
        // CONFIRMED DEFECT (D-15): restoreName builds "<date>_<file.name>.<file.extension>",
        // but file.name already contains the extension -> "...678.jpg.jpg".
        val f = tempFileOf("172640293012345678.jpg", Formats.jpeg(64, exifDateTimeOriginal = "2024:05:21 09:30:00"))
        val restored = forensics.restoreName(f)
        assertEquals("20240521_093000_172640293012345678.jpg.jpg", restored)
    }

    @Test
    fun `section17 - real PIL EXIF round-trips into the restored name`() {
        val f = tempFileOf("172640293012345678.jpg", Fixtures.bytes("jpeg_exif.jpg"))
        val restored = forensics.restoreName(f)
        assertEquals("20240521_093000_172640293012345678.jpg.jpg", restored)  // .jpg twice: defect D-15
    }

    @Test
    fun `section17 - timestamp without EXIF keeps original name`() {
        val f = tempFileOf("172640293012345678.jpg", Formats.jpeg(64))
        assertEquals("172640293012345678.jpg", forensics.restoreName(f))
    }

    @Test
    fun `section17 - digit lengths 15 through 20 are all candidates`() {
        for (digits in 15..20) {
            val name = "1" + "0".repeat(digits - 1) + ".jpg"
            val restored = forensics.restoreName(tempFileOf(name, Formats.jpeg(64, exifDateTimeOriginal = "2024:05:21 09:30:00")))
            assertTrue("length $digits not treated as candidate: $restored", restored != name)
        }
        for (digits in listOf(14, 21)) {
            val name = "1" + "0".repeat(digits - 1) + ".jpg"
            assertEquals("length $digits should be rejected", name, forensics.restoreName(tempFileOf(name, Formats.jpeg(64))))
        }
    }

    @Test
    fun `section17 - non-image or normal-named files are left alone`() {
        assertEquals("vacation.jpg", forensics.restoreName(tempFileOf("vacation.jpg", Formats.jpeg(64))))
        assertEquals("172640293012345678.bin", forensics.restoreName(tempFileOf("172640293012345678.bin", Formats.jpeg(64))))
    }

    // ------------------------------------------------------------ Section 18: Motion Photo

    @Test
    fun `section18 - MotionPhoto_Data MP4 offset and length are exact`() {
        val jpeg = Formats.jpeg(entropyBytes = 512)
        val mp4 = Formats.mp4(mdatBytes = 2048)
        val container = Formats.samsungContainer(jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4)))
        val f = tempFileOf("motion_photo.jpg", container)
        val embedded = forensics.scanFile(f)
        val motion = embedded.first { it.key == "MotionPhoto_Data" }
        assertEquals(MediaType.MP4, motion.type)
        val expectedOffset = (jpeg.size + 4 + "MotionPhoto_Data".length).toLong()
        assertEquals("MP4 offset", expectedOffset, motion.offset)
        assertEquals("MP4 length", mp4.size.toLong(), motion.length)
        assertEquals(RecoveryQuality.VALID, motion.quality)
    }

    @Test
    fun `section18 - extracted Motion Photo payload is byte-identical to the embedded MP4`() {
        val jpeg = Fixtures.bytes("jpeg_exif.jpg")
        val mp4 = Fixtures.bytes("mp4_mdat_first.mp4")
        val container = Formats.samsungContainer(jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4)))
        val f = tempFileOf("motion_photo.jpg", container)
        val motion = forensics.scanFile(f).first { it.key == "MotionPhoto_Data" }
        val extracted = f.inputStream().use { it.skip(motion.offset); it.readNBytes(motion.length.toInt()) }
        assertTrue("extracted MP4 differs from ground truth", extracted.contentEquals(mp4))
    }

    @Test
    fun `section19 doc - corrupted SEF directory falls back to string scan but includes SEFT bytes`() {
        // CONFIRMED DEFECT (D-16): the string-scan fallback sets payload end = SEFH position,
        // so the 4 'SEFT' marker bytes between payload and SEFH are included in the length.
        val jpeg = Formats.jpeg(entropyBytes = 256)
        val mp4 = Formats.mp4(mdatBytes = 1024)
        val container = Formats.samsungContainer(
            jpeg,
            listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4)),
            corruptDirRecord = true      // zeroed offsetBack/absurd length: directory record unusable
        )
        val f = tempFileOf("broken_sef.jpg", container)
        val embedded = forensics.scanFile(f)
        val motion = embedded.first { it.key == "MotionPhoto_Data" }
        val expectedOffset = (jpeg.size + 4 + "MotionPhoto_Data".length).toLong()
        assertEquals("offset stays exact via fallback", expectedOffset, motion.offset)
        assertEquals("length includes 4 SEFT trailer bytes (defect D-16)", mp4.size + 4L, motion.length)
        val slice = f.inputStream().use { it.skip(motion.offset); it.readNBytes(motion.length.toInt()) }
        assertTrue("payload is the MP4 plus 'SEFT' suffix", slice.copyOfRange(0, mp4.size).contentEquals(mp4) &&
            slice.copyOfRange(mp4.size, slice.size).contentEquals("SEFT".toByteArray()))
    }

    @Test
    fun `section19 - destroyed SEFH version or count still yields the payload via fallback`() {
        val jpeg = Formats.jpeg(entropyBytes = 256)
        val mp4 = Formats.mp4(mdatBytes = 1024)
        val container = Formats.samsungContainer(
            jpeg,
            listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4)),
            sefhVersion = 999_999        // out of accepted range -> directory path disabled
        )
        val f = tempFileOf("broken_sefh.jpg", container)
        val motion = forensics.scanFile(f).first { it.key == "MotionPhoto_Data" }
        assertEquals("length is mp4 + 4 SEFT bytes (defect D-16)", mp4.size + 4L, motion.length)
    }

    @Test
    fun `section19 - missing SEFT entirely still finds embedded MP4 via ftyp fallback`() {
        val jpeg = Formats.jpeg(entropyBytes = 256)
        val mp4 = Formats.mp4(mdatBytes = 1024)
        val container = Formats.samsungContainer(jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4)), seft = false)
        val f = tempFileOf("no_seft.jpg", container)
        val embedded = forensics.scanFile(f)
        assertTrue("ftyp fallback must still locate the MP4", embedded.isNotEmpty())
        val anyMp4 = embedded.first { it.type == MediaType.MP4 }
        assertTrue(anyMp4.length > 0)
    }

    @Test
    fun `section12 - JPEG plus MP4 plus SEF plus garbage yields only MP4 from forensic scan`() {
        val jpeg = Formats.jpeg(entropyBytes = 128)
        val mp4 = Formats.mp4(mdatBytes = 512)
        val container = Formats.samsungContainer(jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4))) +
            Formats.noise(64)
        val f = tempFileOf("combo.jpg", container)
        val embedded = forensics.scanFile(f)
        assertTrue("only MP4 embeddeds expected: ${embedded.map { "${it.type}/${it.key}" }}",
            embedded.all { it.type == MediaType.MP4 })
    }

    // ------------------------------------------------------------ Sections 20/21: DualShot

    @Test
    fun `section20 doc - DualShot_ExtraImage payload includes the 4 SEFT trailer bytes`() {
        // Same D-16 manifestation on the string-scan path: length is secondary + 4.
        val primary = Formats.jpeg(entropyBytes = 256)
        val secondary = Fixtures.bytes("jpeg_baseline.jpg")
        val container = Formats.samsungContainer(primary, listOf(Formats.SefRecord("DualShot_ExtraImage", 0x2001, secondary)))
        val f = tempFileOf("portrait.jpg", container)
        val embedded = forensics.scanFile(f)
        val extra = embedded.first { it.key == "DualShot_ExtraImage" }
        assertEquals(MediaType.JPEG, extra.type)
        val expectedOffset = (primary.size + 4 + "DualShot_ExtraImage".length).toLong()
        assertEquals(expectedOffset, extra.offset)
        assertEquals("length includes SEFT marker (defect D-16)", secondary.size + 4L, extra.length)
        val slice = f.inputStream().use { it.skip(extra.offset); it.readNBytes(extra.length.toInt()) }
        assertTrue(slice.copyOfRange(0, secondary.size).contentEquals(secondary))
    }

    @Test
    fun `section21 - DualShot_DepthMap with non-media payload is conservatively ignored`() {
        val primary = Formats.jpeg(entropyBytes = 256)
        val container = Formats.samsungContainer(primary, listOf(Formats.SefRecord("DualShot_DepthMap", 0x2002, Formats.noise(1024))))
        val f = tempFileOf("depth.jpg", container)
        val embedded = forensics.scanFile(f)
        assertTrue(
            "non-media depth payload must not be reported as media: ${embedded.map { it.key }}",
            embedded.none { it.key.contains("DepthMap") }
        )
    }

    @Test
    fun `section21 - depth map with JPEG payload is classified as JPEG, length includes SEFT bytes`() {
        val primary = Formats.jpeg(entropyBytes = 256)
        val depth = Formats.jpeg(entropyBytes = 4096)
        val container = Formats.samsungContainer(primary, listOf(Formats.SefRecord("DualShot_DepthMap", 0x2002, depth)))
        val f = tempFileOf("depth2.jpg", container)
        val depthHit = forensics.scanFile(f).first { it.key == "DualShot_DepthMap" }
        assertEquals(MediaType.JPEG, depthHit.type)
        assertEquals(depth.size + 4L, depthHit.length)   // + SEFT (defect D-16)
    }

    // ------------------------------------------------------------ Section 7: .thumbdata

    @Test
    fun `section7 - thumbdata3 containing real JPEGs carves each with exact offset and length`() {
        val j1 = Fixtures.bytes("jpeg_baseline.jpg")
        val j2 = Fixtures.bytes("jpeg_exif.jpg")
        val blob = Formats.noise(64) + j1 + Formats.noise(200) + j2 + Formats.noise(32)
        val f = tempFileOf(".thumbdata3--123456789", blob)
        val carved = miner.carveJpegs(Uri.fromFile(f), f.name, "Samsung .thumbdata binary carve")
        assertEquals(2, carved.size)
        val hit1 = carved.first { it.offset == 64L }
        val hit2 = carved.first { it.offset == (64 + j1.size + 200).toLong() }
        assertEquals(j1.size.toLong(), hit1.length)
        assertEquals(j2.size.toLong(), hit2.length)
        assertEquals(RecoveryQuality.VALID, hit1.quality)
        assertEquals(MediaType.JPEG, hit1.type)
    }

    @Test
    fun `section7 - JPEG smaller than 10KB is dropped by the thumbdata gate`() {
        val small = Fixtures.bytes("jpeg_small.jpg")     // 1,030 bytes
        assertTrue(small.size < 10 * 1024)
        val blob = Formats.noise(64) + small + Formats.noise(32)
        val f = tempFileOf(".thumbdata4--123", blob)
        val carved = miner.carveJpegs(Uri.fromFile(f), f.name, "carve")
        assertTrue("small JPEG should not pass the 10KB gate", carved.none { it.offset == 64L })
    }

    @Test
    fun `section7 - fake JPEG with JFIF marker and FFD9 is rejected by decode gate`() {
        val fake = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10) +
            "JFIF".toByteArray() + ByteArray(10 * 1024 + 64) { 0x33 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val blob = Formats.noise(16) + fake + Formats.noise(16)
        val f = tempFileOf(".thumbdata3--999", blob)
        val carved = miner.carveJpegs(Uri.fromFile(f), f.name, "carve")
        assertTrue("garbage with JPEG structure must fail BitmapFactory validation", carved.isEmpty())
    }

    @Test
    fun `section7 - JPEG crossing the 1MiB read boundary is carved byte-exact`() {
        val j = Fixtures.bytes("jpeg_baseline.jpg")
        val blob = Formats.noise(1024 * 1024 - 512) + j + Formats.noise(16)
        val f = tempFileOf(".thumbdata3--777", blob)
        val carved = miner.carveJpegs(Uri.fromFile(f), f.name, "carve")
        val hit = carved.first { it.offset == (1024 * 1024 - 512).toLong() }
        assertEquals(j.size.toLong(), hit.length)
    }

    @Test
    fun `section7 - duplicated JPEG inside one thumbdata is carved twice (documents no dedup)`() {
        val j = Fixtures.bytes("jpeg_baseline.jpg")
        val blob = Formats.noise(8) + j + Formats.noise(8) + j + Formats.noise(8)
        val f = tempFileOf(".thumbdata3--555", blob)
        val carved = miner.carveJpegs(Uri.fromFile(f), f.name, "carve")
        assertEquals("identical bytes at two offsets: both reported (Section 26 dedup gap)", 2, carved.size)
    }

    // ------------------------------------------------------------ Sections 22/23: archives

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
    fun `section22 - sdocx-style zip yields extracted images with original bytes`() {
        val jpg = Fixtures.bytes("jpeg_baseline.jpg")
        val png = Fixtures.bytes("png_rgba.png")
        val webp = Fixtures.bytes("webp_lossy.webp")
        val archive = zip(
            "manifest.xml" to "<note/>".toByteArray(),
            "media/001.jpg" to jpg,
            "media/002.png" to png,
            "media/003.webp" to webp
        )
        val f = tempFileOf("note.sdocx", archive)
        val artifacts = miner.scanFile(f).filter { it.source.startsWith("archive") }
        assertEquals(3, artifacts.size)
        val types = artifacts.map { it.type }.toSet()
        assertEquals(setOf(MediaType.JPEG, MediaType.PNG, MediaType.WEBP), types)
        for (a in artifacts) {
            assertEquals(RecoveryQuality.VALID, a.quality)
            val bytes = File(a.uri.path!!).readBytes()
            assertTrue("extracted bytes must be original", bytes.contentEquals(jpg) || bytes.contentEquals(png) || bytes.contentEquals(webp))
        }
    }

    @Test
    fun `section23 - ssbak that is really a zip extracts its media`() {
        val jpg = Fixtures.bytes("jpeg_exif.jpg")
        val f = tempFileOf("backup.ssbak", zip("SDCARD/DCIM/Camera/photo.jpg" to jpg))
        val artifacts = miner.scanFile(f).filter { it.source.startsWith("archive") }
        assertEquals(1, artifacts.size)
        assertEquals(MediaType.JPEG, artifacts[0].type)
        assertTrue(File(artifacts[0].uri.path!!).readBytes().contentEquals(jpg))
    }

    @Test
    fun `section23 - ssbak that is NOT a zip reports nothing and does not crash`() {
        val f = tempFileOf("corrupt.ssbak", Formats.noise(4096))
        assertTrue(miner.scanFile(f).isEmpty())
    }

    @Test
    fun `section23 - truncated zip reports nothing and does not crash`() {
        val good = zip("a.jpg" to Fixtures.bytes("jpeg_baseline.jpg"))
        val f = tempFileOf("cut.zip", good.copyOf(good.size / 2))
        assertTrue(miner.scanFile(f).isEmpty())
    }

    @Test
    fun `section41 - zip entry names with path traversal never escape the temp directory`() {
        val jpg = Fixtures.bytes("jpeg_baseline.jpg")
        val before = File(context.cacheDir, "escape-marker.jpg").let { it.delete(); !it.exists() }
        val f = tempFileOf("evil.zip", zip("../escape-marker.jpg" to jpg, "normal/photo.jpg" to jpg))
        val artifacts = miner.scanFile(f).filter { it.source.startsWith("archive") }
        assertTrue(before)
        // entries are extracted into freshly created temp files; entry names never become paths
        val escaped = File(context.cacheDir, "escape-marker.jpg")
        assertTrue("path traversal escaped cache dir", !escaped.exists())
        assertTrue(artifacts.isNotEmpty())
        for (a in artifacts) {
            val p = File(a.uri.path!!)
            assertTrue(p.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        }
    }

    // ------------------------------------------------------------ Section 24: caches

    @Test
    fun `section24 - extensionless cache files are detected by content`() {
        val cases = mapOf(
            "1234567890abcdef" to (Formats.jpeg(64) to MediaType.JPEG),
            "cache.0" to (Formats.png(64) to MediaType.PNG),
            "68b329da9893e34099c7d8ad5cb9c940" to (Formats.webp(payloadBytes = 64) to MediaType.WEBP),
            "temp_0" to (Formats.mp4(mdatBytes = 256) to MediaType.MP4)
        )
        for ((name, expected) in cases) {
            val artifacts = miner.scanFile(tempFileOf(name, expected.first))
            assertEquals("cache file $name not detected by content", 1, artifacts.size)
            assertEquals(expected.second, artifacts[0].type)
            assertEquals("filesystem", artifacts[0].source)
        }
    }

    @Test
    fun `section24 - corrupt cache item produces no artifacts and no crash`() {
        assertTrue(miner.scanFile(tempFileOf("deadbeef-hash", Formats.noise(2048))).isEmpty())
        assertTrue(miner.scanFile(tempFileOf("empty.0", ByteArray(0))).isEmpty())
    }

    // ------------------------------------------------------------ SamsungArtifactScanner (tail scanner)

    @Test
    fun `section18b doc - SamsungArtifactScanner finds nothing in a Motion Photo container`() {
        // CONFIRMED DEFECT (D-17): SamsungArtifactScanner.parseIsoBmff walks boxes from the
        // embedded ftyp, hits the SEF trailer ('SEFT' read as a box size) after moov, and
        // returns null for the whole candidate - so the tail scanner yields NOTHING for
        // the exact Samsung Motion Photo layout it was built for. Motion Photos are only
        // found by SamsungForensics. (SamsungArtifactScanner is also unreferenced by the UI.)
        val jpeg = Formats.jpeg(entropyBytes = 512)
        val mp4 = Fixtures.bytes("mp4_mdat_first.mp4")
        val container = Formats.samsungContainer(jpeg, listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4)))
        val f = tempFileOf("mp.jpg", container)
        val results = SamsungArtifactScanner(context).scan(Uri.fromFile(f), f.name)
        assertTrue("tail scanner should return results but yields ${results.size} (defect D-17)", results.isEmpty())
    }

    // ------------------------------------------------------------ Section 26: duplicate handling

    @Test
    fun `section26 doc - identical JPEG at four locations yields four separate results, no dedup`() {
        val bytes = Fixtures.bytes("jpeg_baseline.jpg")
        val dir = createTempDir("dup").apply { deleteOnExit() }
        File(dir, "photo1.jpg").writeBytes(bytes)
        File(dir, "DCIM").mkdirs(); File(dir, "DCIM/photo2.jpg").writeBytes(bytes)
        File(dir, "cache").mkdirs(); File(dir, "cache/hash123").writeBytes(bytes)
        File(dir, "trash").mkdirs(); File(dir, "trash/photo3.jpg").writeBytes(bytes)
        val results = runBlocking { StorageScanner(context).scanTree(SAF.install(dir)) { } }
        assertEquals("no content-based dedup exists (Section 26 gap)", 4, results.size)
        assertEquals(setOf("photo1.jpg", "photo2.jpg", "hash123", "photo3.jpg"), results.map { it.sourceName }.toSet())
    }
}
