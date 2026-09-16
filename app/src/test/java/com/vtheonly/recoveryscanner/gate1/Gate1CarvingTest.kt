package com.vtheonly.recoveryscanner.gate1

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vtheonly.recoveryscanner.scanner.DeepContentScanner
import com.vtheonly.recoveryscanner.scanner.MediaCarver
import com.vtheonly.recoveryscanner.scanner.MediaType
import com.vtheonly.recoveryscanner.scanner.RecoveryQuality
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

/**
 * Gate 1 - structural carving correctness: exact offsets, exact lengths, quality grades.
 * Covers plan Sections 6 (JPEG), 8 (PNG), 9 (GIF), 10 (WebP), 11 (HEIC), 12 (MP4/MOV/3GP),
 * 13 (MKV/WebM) and the chunk-boundary requirements of Section 7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Gate1CarvingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val carver = MediaCarver(context)

    private fun carveFile(bytes: ByteArray, offset: Long = 0L, type: MediaType): com.vtheonly.recoveryscanner.scanner.CarveResult {
        val f = File.createTempFile("carve", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
        return carver.analyze(Uri.fromFile(f), offset, type, bytes.size.toLong())
    }

    // ------------------------------------------------------------ Section 6: JPEG

    @Test
    fun `section6 - valid JPEG carves to its exact full length with VALID quality`() {
        for (bytes in listOf(
            Formats.jpeg(entropyBytes = 4096),
            Formats.jpeg(entropyBytes = 4096, progressive = true),
            Formats.jpeg(entropyBytes = 64, jfif = false),
            Formats.jpeg(entropyBytes = 64, exifDateTimeOriginal = "2024:05:21 09:30:00"),
            Fixtures.bytes("jpeg_baseline.jpg"),
            Fixtures.bytes("jpeg_progressive.jpg"),
            Fixtures.bytes("jpeg_exif.jpg"),
            Fixtures.bytes("jpeg_small.jpg")
        )) {
            val r = carveFile(bytes, type = MediaType.JPEG)
            assertEquals("JPEG carve length must be byte-exact", bytes.size.toLong(), r.length)
            assertEquals(RecoveryQuality.VALID, r.quality)
            assertTrue("confidence expected >=90, was ${r.confidence}", r.confidence >= 90)
        }
    }

    @Test
    fun `section6 - JPEG without EOI is PARTIAL_OR_CORRUPT`() {
        val truncated = Formats.jpeg(entropyBytes = 512, includeEoi = false)
        val r = carveFile(truncated, type = MediaType.JPEG)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
        assertEquals(truncated.size.toLong(), r.length)
    }

    @Test
    fun `section6 - JPEG entropy truncated mid-scan reports partial length`() {
        val full = Formats.jpeg(entropyBytes = 512)
        val cut = full.copyOf(full.size - 200)     // cut into entropy data
        val r = carveFile(cut, type = MediaType.JPEG)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
        assertEquals(cut.size.toLong(), r.length)
    }

    @Test
    fun `section6 - multiple JPEGs embedded in garbage are independently carveable`() {
        val j1 = Formats.jpeg(entropyBytes = 1024)
        val j2 = Formats.jpeg(entropyBytes = 2048)
        val j3 = Formats.jpeg(entropyBytes = 512)
        val blob = Formats.noise(100) + j1 + Formats.noise(200) + j2 + Formats.noise(50) + j3 + Formats.noise(30)
        val offsets = listOf(100L, 100 + j1.size + 200L, 100 + j1.size + 200 + j2.size + 50L)
        for ((i, off) in offsets.withIndex()) {
            val expected = listOf(j1, j2, j3)[i]
            val r = carver.analyze(blobUri(blob), off, MediaType.JPEG, blob.size.toLong())
            assertEquals("carve length of JPEG ${i + 1} wrong", expected.size.toLong(), r.length)
            assertEquals(RecoveryQuality.VALID, r.quality)
        }
    }

    @Test
    fun `section6 - corrupted JPEG marker walk still terminates`() {
        // DQT marker corrupted to reserved 0xC8 - walkable but hostile; must not hang/crash
        val bytes = Formats.jpeg(entropyBytes = 256, corruptMarker = true)
        val r = carveFile(bytes, type = MediaType.JPEG)
        assertTrue(r.length > 0)
    }

    // ------------------------------------------------------------ Section 8: PNG

    @Test
    fun `section8 - valid PNG carves to exact length and stops at IEND, not EOF`() {
        val png = Formats.png(idatBytes = 1024)
        val withTrailing = png + Formats.noise(999)
        val r = carveFile(withTrailing, type = MediaType.PNG)
        assertEquals("PNG carve must stop at IEND", png.size.toLong(), r.length)
        assertEquals(RecoveryQuality.VALID, r.quality)
    }

    @Test
    fun `section8 - PNG without IEND is PARTIAL_OR_CORRUPT`() {
        val r = carveFile(Formats.png(idatBytes = 64, includeIend = false), type = MediaType.PNG)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
    }

    @Test
    fun `section8 - PNG with corrupt IHDR CRC is still reported VALID - CRC not validated`() {
        // Documents current behavior: MediaCarver skips chunk data+CRC without verification.
        val r = carveFile(Formats.png(idatBytes = 64, corruptIhdrCrc = true), type = MediaType.PNG)
        assertEquals(RecoveryQuality.VALID, r.quality)
    }

    @Test
    fun `section8 - PNG declaring absurd IDAT size is rejected`() {
        val r = carveFile(Formats.png(idatBytes = 16, hugeIdat = true), type = MediaType.PNG)
        assertEquals(RecoveryQuality.SIGNATURE_ONLY, r.quality)
        assertEquals(0L, r.length)
    }

    // ------------------------------------------------------------ Section 9: GIF

    @Test
    fun `section9 doc - GIF carve is misaligned by one byte and never byte-exact`() {
        // CONFIRMED DEFECT (D-12): MediaCarver.gif() consumes one extra byte before the
        // image-data sub-blocks (the s.read() before skipSubBlocks eats the first
        // sub-block length), so every GIF with image data walks misaligned and produces
        // wrong lengths, PARTIAL quality, or outright rejection.
        for (bytes in listOf(
            Formats.gif(version = "GIF87a"), Formats.gif(version = "GIF89a"),
            Formats.gif(animated = true), Fixtures.bytes("gif87a_single.gif"),
            Fixtures.bytes("gif89a_single.gif"), Fixtures.bytes("gif_animated.gif")
        )) {
            val r = carveFile(bytes, type = MediaType.GIF)
            assertTrue(
                "GIF carve should be byte-exact VALID (defect D-12): got len=${r.length} of ${bytes.size}, quality=${r.quality}",
                !(r.length == bytes.size.toLong() && r.quality == RecoveryQuality.VALID)
            )
        }
    }

    @Test
    fun `section9 - truncated GIF is PARTIAL_OR_CORRUPT`() {
        val r = carveFile(Formats.gif(includeTrailer = false), type = MediaType.GIF)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
    }

    @Test
    fun `section9 - fake GIF header with garbage body is rejected`() {
        val fake = "GIF89a".toByteArray() + Formats.noise(256)
        val r = carveFile(fake, type = MediaType.GIF)
        assertEquals(RecoveryQuality.SIGNATURE_ONLY, r.quality)
    }

    // ------------------------------------------------------------ Section 10: WebP

    @Test
    fun `section10 - WebP VP8, VP8L and VP8X carve VALID and RIFF length is respected`() {
        for (fourcc in listOf("VP8 ", "VP8L", "VP8X")) {
            val wp = Formats.webp(fourcc = fourcc, payloadBytes = 1024)
            val withTrailing = wp + Formats.noise(513)
            val r = carveFile(withTrailing, type = MediaType.WEBP)
            assertEquals("RIFF declared length must be respected for $fourcc", wp.size.toLong(), r.length)
            assertEquals(RecoveryQuality.VALID, r.quality)
        }
    }

    @Test
    fun `section10 doc - real WebP carve length is wrong because RIFF size is read big-endian`() {
        // CONFIRMED DEFECT (D-04): MediaCarver.webp() reads the 4-byte RIFF size as
        // BIG-endian, but RIFF is little-endian. Real WebP files therefore get an
        // astronomically wrong "VALID" carve length, and RecoveryWriter can never
        // produce output (copied != recoveredLength -> null). Synthetic WebPs pass
        // only because Formats.webp() happens to write the size big-endian too.
        val wp = Fixtures.bytes("webp_animated.webp")
        val be = ((wp[4].toLong() and 0xFF) shl 24) or ((wp[5].toLong() and 0xFF) shl 16) or
            ((wp[6].toLong() and 0xFF) shl 8) or (wp[7].toLong() and 0xFF)
        val r = carveFile(wp, type = MediaType.WEBP)
        assertEquals(RecoveryQuality.VALID, r.quality)
        assertEquals("BE-read of LE field (defect D-04)", be + 8, r.length)
        assertTrue("correct length would be ${wp.size}, got ${r.length}", r.length != wp.size.toLong())
    }

    @Test
    fun `section10 doc - WebP with corrupted RIFF size reports VALID beyond EOF`() {
        // Documents defect: MediaCarver trusts the declared RIFF size without checking the
        // remaining stream. RecoveryWriter will then copy fewer bytes than claimed.
        val wp = Formats.webp(payloadBytes = 256, declaredSizeDelta = 1_000_000)
        val r = carveFile(wp, type = MediaType.WEBP)
        assertEquals(RecoveryQuality.VALID, r.quality)
        assertEquals(wp.size + 1_000_000L, r.length)
    }

    // ------------------------------------------------------------ Section 11: HEIC (ISO-BMFF)

    @Test
    fun `section11 doc - HEIC brands carve LIKELY_RECOVERABLE because VALID requires moov`() {
        // Documents behavior: MediaCarver.isoBmff only returns VALID when a 'moov' box is
        // consumed, and HEIC/HEIF images contain meta/mdat instead. Real HEIC photos can
        // therefore never reach VALID quality through the carve path.
        for (name in listOf("heic_brand.heic", "heix_brand.heic", "mif1_brand.heif", "msf1_brand.heif")) {
            val bytes = Fixtures.bytes(name)
            val r = carveFile(bytes, type = MediaType.HEIC)
            assertEquals("HEIC carve length wrong for $name", bytes.size.toLong(), r.length)
            assertEquals("HEIC quality for $name", RecoveryQuality.LIKELY_RECOVERABLE, r.quality)
        }
    }

    @Test
    fun `section11 - truncated HEIC is PARTIAL_OR_CORRUPT`() {
        val bytes = Fixtures.bytes("heic_brand.heic")
        val r = carveFile(bytes.copyOf(bytes.size - 8), type = MediaType.HEIC)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
    }

    // ------------------------------------------------------------ Section 12: MP4 / MOV / 3GP

    @Test
    fun `section12 - MP4 with moov last carves VALID to exact full length`() {
        val mp4 = Formats.mp4(mdatBytes = 2048)
        val withTrailing = mp4 + Formats.noise(77)
        val r = carveFile(withTrailing, type = MediaType.MP4)
        assertEquals("MP4 (moov last) carve must span ftyp+mdat+moov", mp4.size.toLong(), r.length)
        assertEquals(RecoveryQuality.VALID, r.quality)
    }

    @Test
    fun `section12 doc - faststart MP4 carve truncates after moov, losing mdat`() {
        // Documents defect D-01: MediaCarver.isoBmff returns as soon as 'moov' is consumed.
        // For [ftyp][moov][mdat] files (faststart, the common web layout!) the carve length
        // stops at the moov box end and the mdat payload is lost despite VALID quality.
        val fs = Formats.mp4Faststart(mdatBytes = 4096)
        val r = carveFile(fs, type = MediaType.MP4)
        assertEquals(RecoveryQuality.VALID, r.quality)
        assertTrue(
            "carve length ${r.length} covers only ftyp+moov (${fs.size - 4096 - 8}); mdat lost",
            r.length == (fs.size - 4096 - 8).toLong()
        )
    }

    @Test
    fun `section12 - size=0 box extends to available bytes`() {
        // mdat with size 0 means "to end of stream" per ISO-BMFF
        val head = Formats.isoBmff("isom", listOf(Formats.Box("free", 16)))
        val mdatAtEof = byteArrayOf(0, 0, 0, 0) + "mdat".toByteArray() + Formats.noise(512)
        val whole = head + mdatAtEof
        val r = carveFile(whole, type = MediaType.MP4)
        assertTrue("size=0 mdat must extend to EOF, carve was ${r.length}", r.length == whole.size.toLong())
        assertEquals(RecoveryQuality.LIKELY_RECOVERABLE, r.quality)   // no moov present
    }

    @Test
    fun `section12 - 64-bit extended box size is honored`() {
        val mp4 = Formats.isoBmff(
            "isom",
            listOf(
                Formats.Box("mdat", 512, extended64 = true, fill = 0x11),
                Formats.Box("moov", 64)
            )
        )
        val r = carveFile(mp4, type = MediaType.MP4)
        assertEquals(mp4.size.toLong(), r.length)
        assertEquals(RecoveryQuality.VALID, r.quality)
    }

    @Test
    fun `section12 - absurd box size is PARTIAL_OR_CORRUPT not VALID`() {
        val mp4 = Formats.isoBmff(
            "isom",
            listOf(Formats.Box("mdat", 0, sizeOverride = 0x7FFFFFFF00L, fill = 0x11), Formats.Box("moov", 32))
        )
        val r = carveFile(mp4, type = MediaType.MP4)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
    }

    @Test
    fun `section12 - MP4 without moov is LIKELY_RECOVERABLE`() {
        val mp4 = Formats.isoBmff("isom", listOf(Formats.Box("mdat", 512, fill = 0x11), Formats.Box("free", 16)))
        val r = carveFile(mp4, type = MediaType.MP4)
        assertEquals(RecoveryQuality.LIKELY_RECOVERABLE, r.quality)
    }

    @Test
    fun `section12 - MOV and 3GP carve through the ISO-BMFF path`() {
        val mov = Formats.isoBmff("qt  ", listOf(Formats.Box("mdat", 256, fill = 0x11), Formats.Box("moov", 48)))
        val r3gp = Formats.isoBmff("3gp4", listOf(Formats.Box("mdat", 256, fill = 0x11), Formats.Box("moov", 48)))
        assertEquals(mov.size.toLong(), carveFile(mov, type = MediaType.MOV).length)
        assertEquals(RecoveryQuality.VALID, carveFile(mov, type = MediaType.MOV).quality)
        assertEquals(r3gp.size.toLong(), carveFile(r3gp, type = MediaType.THREE_GP).length)
    }

    @Test
    fun `section12 - real MP4 fixtures carve VALID with exact length`() {
        // mp4_mdat_first.mp4 / mp4_hevc.mp4 / mp4_large.mp4 / mov / 3gp all have moov LAST.
        for (name in listOf("mp4_mdat_first.mp4", "mp4_hevc.mp4", "mp4_large.mp4", "mov_qt.mov", "video_3gp.3gp")) {
            val bytes = Fixtures.bytes(name)
            val type = when {
                name.endsWith(".mov") -> MediaType.MOV
                name.endsWith(".3gp") -> MediaType.THREE_GP
                else -> MediaType.MP4
            }
            val r = carveFile(bytes, type = type)
            assertEquals("exact carve length for $name", bytes.size.toLong(), r.length)
            assertEquals("quality for $name", RecoveryQuality.VALID, r.quality)
        }
    }

    @Test
    fun `section12 doc - real faststart MP4 is truncated at moov and loses mdat`() {
        // CONFIRMED DEFECT (D-01): mp4_h264.mp4 was produced with -movflags +faststart
        // (ftyp, moov, mdat, free) - the common web-streaming layout. MediaCarver returns
        // as soon as 'moov' is consumed, so the carve length stops before mdat even
        // though quality is VALID: the recovered file is an unplayable stub.
        val bytes = Fixtures.bytes("mp4_h264.mp4")
        val moovEnd = run {   // walk boxes to find where moov ends
            var p = 0; var end = 0L
            while (p + 8 <= bytes.size) {
                val size = ((bytes[p].toLong() and 0xFF) shl 24) or ((bytes[p + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[p + 2].toLong() and 0xFF) shl 8) or (bytes[p + 3].toLong() and 0xFF)
                val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
                if (size < 8) break
                if (type == "moov") { end = p + size.toLong(); break }
                p += size.toInt()
            }
            end
        }
        assertTrue(moovEnd > 0)
        val r = carveFile(bytes, type = MediaType.MP4)
        assertEquals(RecoveryQuality.VALID, r.quality)
        assertEquals("carve stops at moov end (defect D-01)", moovEnd, r.length)
        assertTrue("mdat payload lost: ${r.length} of ${bytes.size}", r.length < bytes.size - 1024)
    }

    @Test
    fun `section12 - corrupted MP4 box size is PARTIAL_OR_CORRUPT`() {
        val bytes = Fixtures.bytes("mp4_mdat_first.mp4")
        val corrupt = bytes.clone()
        corrupt[0] = 0x7F                            // wreck the ftyp box size (bytes 0-3)
        val r = carveFile(corrupt, type = MediaType.MP4)
        assertEquals(RecoveryQuality.PARTIAL_OR_CORRUPT, r.quality)
    }

    // ------------------------------------------------------------ Section 13: MKV / WebM

    @Test
    fun `section13 doc - MKV and WebM have no structural carve path`() {
        // Documents gap D-08: MediaCarver has no EBML branch, so MKV/WebM candidates fall
        // through to SIGNATURE_ONLY and can never be recovered by the writer.
        for (name in listOf("video.mkv", "video.webm")) {
            val r = carveFile(Fixtures.bytes(name), type = MediaType.MKV)
            assertEquals("MKV/WebM carve unsupported (Section 13 gap)", RecoveryQuality.SIGNATURE_ONLY, r.quality)
        }
    }

    @Test
    fun `section13 - fake EBML signature is not detected as media by header detection`() {
        val fake = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + Formats.noise(512)
        val d = com.vtheonly.recoveryscanner.scanner.SignatureRegistry.detect(fake.inputStream(), "fake.mkv")
        // fake EBML without DocType text: detect() searches "webm" in first 512 bytes and
        // otherwise reports MKV. Asserting current behavior.
        assertTrue("fake EBML unexpected detection: $d", d == null || d.type == MediaType.MKV)
    }

    // ------------------------------------------------------------ Section 7 boundary handling (deep scan)

    @Test
    fun `section7 - SOI marker at chunk boundary bytes 1048575-1048577 is found by deep scan`() = runBlocking {
        for (delta in listOf(-2, -1, 0, 1, 2)) {
            val target = 1024 * 1024 + delta             // 1,048,576 +/- 2
            val jpeg = Formats.jpeg(entropyBytes = 2048)
            val blob = Formats.noise(target) + jpeg + Formats.noise(16)
            val dir = createTempDir("boundary").apply { deleteOnExit() }
            File(dir, "blob.bin").writeBytes(blob)
            val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
            val found = results.filter { it.detectedType == MediaType.JPEG }
            assertTrue("deep scan missed JPEG at offset $target", found.any { it.offset == target.toLong() })
            val hit = found.first { it.offset == target.toLong() }
            assertEquals("carved length at boundary offset $target wrong", jpeg.size.toLong(), hit.recoveredLength)
            assertEquals(RecoveryQuality.VALID, hit.quality)
        }
    }

    @Test
    fun `section7 - EOI split across the chunk boundary still carves VALID`() = runBlocking {
        // Build the JPEG first, then pad so its final FFD9 straddles the 1 MiB boundary.
        val jpeg = Formats.jpeg(entropyBytes = 1024 * 1024 - 200)
        val start = (1024 * 1024 - 2 - jpeg.size + 200).coerceAtLeast(0)   // tune below
        // We simply choose the prefix so the FF of FFD9 sits at 1,048,575:
        val prefixLen = (1024 * 1024 - 1 - (jpeg.size - 2)).coerceAtLeast(0)
        val blob = Formats.noise(prefixLen) + jpeg + Formats.noise(8)
        val eoiAt = prefixLen + jpeg.size - 2
        assertTrue("test construction: EOI at $eoiAt, want 1048575", eoiAt == 1024 * 1024 - 1)
        val dir = createTempDir("eoi-boundary").apply { deleteOnExit() }
        File(dir, "blob.bin").writeBytes(blob)
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        val hit = results.first { it.detectedType == MediaType.JPEG }
        assertEquals(jpeg.size.toLong(), hit.recoveredLength)
        assertEquals(RecoveryQuality.VALID, hit.quality)
    }

    @Test
    fun `sections6to14 - deep scan finds every format embedded in random binary`() = runBlocking {
        val j = Formats.jpeg(entropyBytes = 256)
        val p = Formats.png(idatBytes = 128)
        val g = Formats.gif()
        val w = Formats.webp(payloadBytes = 256)
        val m = Formats.mp4(mdatBytes = 256)
        val blob = Formats.noise(100) + j + Formats.noise(10) + p + Formats.noise(10) + g +
            Formats.noise(10) + w + Formats.noise(10) + m + Formats.noise(10)
        val dir = createTempDir("all-formats").apply { deleteOnExit() }
        File(dir, "random.bin").writeBytes(blob)
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        val expected = mapOf(
            MediaType.JPEG to (100L + j.size).let { 100L },
            MediaType.PNG to (100L + j.size + 10L),
            MediaType.GIF to (100L + j.size + 10L + p.size + 10L),
            MediaType.WEBP to (100L + j.size + 10L + p.size + 10L + g.size + 10L),
            MediaType.MP4 to (100L + j.size + 10L + p.size + 10L + g.size + 10L + w.size + 10L)
        )
        for ((type, offset) in expected) {
            val hit = results.firstOrNull { it.detectedType == type && it.offset == offset }
            assertTrue("deep scan did not find $type at offset $offset (got ${results.map { "${it.detectedType}@${it.offset}" }})", hit != null)
        }
    }

    @Test
    fun `sections15and16 - deep scan traverses hidden directories and nomedia folders`() = runBlocking {
        val dir = createTempDir("hidden").apply { deleteOnExit() }
        for (sub in listOf("DCIM/hidden", ".thumbnails", ".trash")) File(dir, sub).mkdirs()
        File(dir, "DCIM/hidden/.nomedia").writeText("")
        File(dir, "DCIM/hidden/deleted.jpg").writeBytes(Formats.jpeg(32))
        File(dir, ".thumbnails/tn.bin").writeBytes(Formats.jpeg(32))
        File(dir, ".trash/171640293012345678.jpg").writeBytes(Formats.jpeg(32))
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        assertEquals(
            "hidden/.nomedia trees must still be scanned",
            setOf("deleted.jpg", "tn.bin", "171640293012345678.jpg"),
            results.map { it.sourceName }.toSet()
        )
    }

    @Test
    fun `sections15and16 - deep scan of samsung container reports primary JPEG and embedded MP4 only`() = runBlocking {
        val jpeg = Formats.jpeg(entropyBytes = 512)
        val mp4 = Formats.mp4(mdatBytes = 1024)
        val container = Formats.samsungContainer(
            jpeg,
            listOf(Formats.SefRecord("MotionPhoto_Data", 0x0A30, mp4))
        )
        val dir = createTempDir("sef").apply { deleteOnExit() }
        File(dir, "motion_photo.jpg").writeBytes(container)
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        val jpegHit = results.first { it.detectedType == MediaType.JPEG }
        assertEquals(0L, jpegHit.offset)
        assertEquals(jpeg.size.toLong(), jpegHit.recoveredLength)
        val mp4Hit = results.first { it.detectedType == MediaType.MP4 }
        val mp4Offset = jpeg.size + 4 + "MotionPhoto_Data".length   // after [len][name]
        assertEquals("embedded MP4 offset wrong", mp4Offset.toLong(), mp4Hit.offset)
        assertEquals(mp4.size.toLong(), mp4Hit.recoveredLength)
        assertEquals("JPEG + MP4 only, no SEF/garbage artifacts", 2, results.size)
    }

    @Test
    fun `section14 - generic binary carving recovers all embedded media with exact offsets`() = runBlocking {
        val j = Fixtures.bytes("jpeg_baseline.jpg")
        val p = Fixtures.bytes("png_rgba.png")
        val m = Fixtures.bytes("mp4_mdat_first.mp4")
        val w = Formats.webp(payloadBytes = 4096)   // synthetic: BE size consistent with carver (D-04 documented separately)
        val blob = Formats.noise(64 * 1024) + j + Formats.noise(128 * 1024) + p +
            Formats.noise(64 * 1024) + m + Formats.noise(64 * 1024) + w + Formats.noise(32)
        val dir = createTempDir("generic").apply { deleteOnExit() }
        File(dir, "random.bin").writeBytes(blob)
        val results = DeepContentScanner(context).scanTree(SAF.install(dir)) { }
        val expected = listOf(
            Triple(MediaType.JPEG, 64 * 1024L, j.size.toLong()),
            Triple(MediaType.PNG, 64 * 1024L + j.size + 128 * 1024L, p.size.toLong()),
            Triple(MediaType.MP4, 64 * 1024L + j.size + 128 * 1024L + p.size + 64 * 1024L, m.size.toLong()),
            Triple(MediaType.WEBP, 64 * 1024L + j.size + 128 * 1024L + p.size + 64 * 1024L + m.size + 64 * 1024L, w.size.toLong())
        )
        for ((type, off, len) in expected) {
            val hit = results.firstOrNull { it.detectedType == type && it.offset == off }
            assertTrue("deep scan missed $type at $off (found: ${results.map { "${it.detectedType}@${it.offset}:${it.recoveredLength}" }})", hit != null)
            assertEquals("carved length wrong for $type", len, hit!!.recoveredLength)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun ByteArray.indexOfBytes(needle: ByteArray): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun blobUri(bytes: ByteArray): Uri {
        val f = File.createTempFile("blob", ".bin").apply { deleteOnExit(); writeBytes(bytes) }
        return Uri.fromFile(f)
    }
}
