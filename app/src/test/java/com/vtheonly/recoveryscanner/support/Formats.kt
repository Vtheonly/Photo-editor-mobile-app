package com.vtheonly.recoveryscanner.support

import java.io.ByteArrayOutputStream

/**
 * Deterministic synthetic media builders.
 *
 * These produce structurally walkable (not necessarily decodable) byte streams with
 * exactly known offsets and lengths, so carving assertions can be byte-exact.
 * Decodable media comes from the real fixtures (resources/fixtures).
 */
object Formats {

    // ---------------------------------------------------------------- helpers

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
    private fun le32(v: Long) = u32(v).reversedArray()
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Deterministic pseudo-random bytes that never contain 0xFF (JPEG-entropy-safe). */
    fun noise(count: Int, seed: Long = 0x5EED): ByteArray {
        var state = seed or 1L
        return ByteArray(count) {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 33).toInt() and 0xFE).toByte()
        }
    }

    // ---------------------------------------------------------------- JPEG

    /**
     * Synthetic baseline/progressive JPEG with exactly known total length.
     * Walkable by MediaCarver.jpeg(): SOI, [APP0 JFIF], [APP1 EXIF], DQT, DHT, SOF, SOS,
     * entropy (no 0xFF), EOI.
     */
    fun jpeg(
        entropyBytes: Int = 4096,
        jfif: Boolean = true,
        exifDateTimeOriginal: String? = null,
        progressive: Boolean = false,
        includeEoi: Boolean = true,
        corruptMarker: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))                       // SOI
        if (jfif) {
            out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte())); out.write(u16(16))
            out.write(ascii("JFIF")); out.write(byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        }
        if (exifDateTimeOriginal != null) {
            val tiff = exifTiff(exifDateTimeOriginal)
            out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
            out.write(u16(2 + 6 + tiff.size))
            out.write(ascii("Exif")); out.write(byteArrayOf(0, 0)); out.write(tiff)
        }
        out.write(byteArrayOf(0xFF.toByte(), if (corruptMarker) 0xC8.toByte() else 0xDB.toByte())) // DQT
        out.write(u16(67)); out.write(ByteArray(65) { (it * 3 + 1).toByte() })
        out.write(byteArrayOf(0xFF.toByte(), 0xC4.toByte()))                       // DHT
        out.write(u16(6 + 16)); out.write(byteArrayOf(0x00)); out.write(ByteArray(16) { 1 })
        out.write(byteArrayOf(0xFF.toByte(), if (progressive) 0xC2.toByte() else 0xC0.toByte())) // SOF
        out.write(u16(17)); out.write(byteArrayOf(8, 0x01, 0xE0.toByte(), 0x02, 0x80.toByte(), 3))
        out.write(byteArrayOf(1, 0x11, 1, 0, 0x02, 0x11, 1, 1, 0x03, 0x11, 1, 1))
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))                       // SOS
        out.write(u16(12)); out.write(byteArrayOf(1, 0x01, 0x00, 0x00, 3, 1, 0x11, 1, 0x00, 0x3F, 0x00))
        out.write(noise(entropyBytes))
        if (includeEoi) out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    /** Minimal TIFF/EXIF with IFD0 -> ExifIFD -> DateTimeOriginal. */
    private fun exifTiff(dateTimeOriginal: String): ByteArray {
        val dt = (dateTimeOriginal + "\u0000").toByteArray(Charsets.US_ASCII)
        val dtPad = dt.size + (dt.size % 2)                                   // ASCII counts are odd-friendly; keep as-is
        val entryExif = byteArrayOf(0x87.toByte(), 0x69.toByte(), 0x00, 0x04, 1, 0, 0, 0) + u32(26) // ExifIFD ptr -> offset 26
        val ifd0 = u16(1) + entryExif + u32(0)
        val entryDto = byteArrayOf(0x90.toByte(), 0x03.toByte(), 0x00, 0x02) +
            le32(dt.size.toLong()) + le32((26 + ifd0.size + 2 + 12 + 4).toLong())  // count, value-offset (patched below)
        val exifIfd = u16(1) + entryDto + u32(0)
        val dataStart = 8 + ifd0.size + exifIfd.size
        val out = ByteArrayOutputStream()
        out.write(ascii("II")); out.write(byteArrayOf(0x2A, 0x00)); out.write(le32(8))
        out.write(ifd0); out.write(exifIfd)
        out.write(dt); if (dtPad > dt.size) out.write(ByteArray(dtPad - dt.size))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- PNG

    fun png(
        idatBytes: Int = 512,
        includeIend: Boolean = true,
        corruptIhdrCrc: Boolean = false,
        hugeIdat: Boolean = false,
        extraChunks: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)           // 1x1 RGB
        out.write(chunk("IHDR", ihdr, corruptIhdrCrc))
        for ((type, data) in extraChunks) out.write(chunk(type, data))
        if (hugeIdat) {
            out.write(u32(300L * 1024 * 1024)); out.write(ascii("IDAT")); out.write(ByteArray(16))
            out.write(u32(0))
        } else {
            out.write(chunk("IDAT", noise(idatBytes)))
        }
        if (includeIend) out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray, corruptCrc: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(data.size.toLong())); out.write(ascii(type)); out.write(data)
        val crc = java.util.zip.CRC32().apply {
            update(ascii(type)); update(data)
        }.value
        out.write(u32(if (corruptCrc) crc xor 0xDEADBEEF else crc))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- GIF

    fun gif(
        version: String = "GIF89a",
        gct: Boolean = true,
        animated: Boolean = false,
        includeTrailer: Boolean = true,
        bogusExtension: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ascii(version))
        out.write(u16(200)); out.write(u16(150))                                 // logical screen 200x150
        out.write(byteArrayOf((if (gct) 0x80 or 0x07 else 0x07).toByte(), 0, 0))  // packed, bg, aspect
        if (gct) out.write(ByteArray(2 * 3 * 8) { (it * 5 and 0xFF).toByte() })
        if (bogusExtension) {
            out.write(byteArrayOf(0x21, 0xFE.toByte()))                          // comment ext with bad sub-block
            out.write(byteArrayOf(5)); out.write(ascii("hello"))
            // missing terminator on purpose when includeTrailer false path used
            out.write(byteArrayOf(0))
        }
        if (animated) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x00, 0x00, 0x64, 0x00, 0))  // GCE
        }
        repeat(if (animated) 2 else 1) {
            out.write(byteArrayOf(0x2C))                                          // image separator
            out.write(u16(0)); out.write(u16(0)); out.write(u16(200)); out.write(u16(150))
            out.write(byteArrayOf(0x00))                                          // no LCT
            out.write(byteArrayOf(10))                                             // LZW min code size
            out.write(byteArrayOf(6) + noise(6) + byteArrayOf(0))                  // one sub-block + terminator
        }
        if (includeTrailer) out.write(byteArrayOf(0x3B))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- WebP

    fun webp(
        fourcc: String = "VP8 ",
        payloadBytes: Int = 512,
        declaredSizeDelta: Long = 0
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val chunkBody = 8 + payloadBytes
        out.write(ascii("RIFF"))
        out.write(u32(4 + chunkBody + declaredSizeDelta))
        out.write(ascii("WEBP"))
        out.write(ascii(fourcc)); out.write(u32(payloadBytes.toLong())); out.write(noise(payloadBytes))
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- BMP

    fun bmp(
        declaredSizeDelta: Long = 0,
        dibSize: Long = 40,
        pixelOffset: Long = 54
    ): ByteArray {
        val payload = 128
        val out = ByteArrayOutputStream()
        out.write(ascii("BM"))
        out.write(u32(54 + payload + declaredSizeDelta))
        out.write(u32(0))
        out.write(u32(pixelOffset))
        out.write(u32(dibSize))
        out.write(ByteArray(payload) { (it * 7 and 0xFF).toByte() })
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- ISO-BMFF (MP4/MOV/3GP/HEIC)

    data class Box(
        val type: String,
        val payloadSize: Long,
        val extended64: Boolean = false,
        val sizeOverride: Long? = null,          // declared size (wins over computed)
        val fill: Byte = 0x42
    )

    /** Builds ftyp + boxes. [Box] list is everything after ftyp. */
    fun isoBmff(brand: String = "isom", boxes: List<Box>): ByteArray {
        val out = ByteArrayOutputStream()
        val ftypPayload = ascii(brand) + u32(0) + ascii("isomiso2mp41")
        out.write(u32((8 + ftypPayload.size).toLong())); out.write(ascii("ftyp")); out.write(ftypPayload)
        for (b in boxes) out.write(box(b))
        return out.toByteArray()
    }

    private fun box(b: Box): ByteArray {
        val out = ByteArrayOutputStream()
        val header = if (b.extended64) 16L else 8L
        val size = b.sizeOverride ?: (header + b.payloadSize)
        out.write(u32(if (b.extended64) 1L else size))
        out.write(ascii(b.type))
        if (b.extended64) {
            val big = java.io.ByteArrayOutputStream(8)
            for (shift in 56 downTo 0 step 8) big.write(((size ushr shift) and 0xFF).toInt())
            out.write(big.toByteArray())
        }
        val n = b.payloadSize.coerceAtMost(64L * 1024 * 1024).toInt()
        out.write(ByteArray(n) { b.fill })
        return out.toByteArray()
    }

    /** Canonical small MP4: ftyp + mdat + moov (moov LAST, like Samsung Motion Photo payloads). */
    fun mp4(mdatBytes: Int = 1024): ByteArray =
        isoBmff("isom", listOf(Box("mdat", mdatBytes.toLong(), fill = 0x11), Box("moov", 64)))

    /** Faststart MP4: ftyp + moov + mdat (moov FIRST). */
    fun mp4Faststart(mdatBytes: Int = 1024): ByteArray =
        isoBmff("isom", listOf(Box("moov", 64), Box("mdat", mdatBytes.toLong(), fill = 0x11)))

    // ---------------------------------------------------------------- EBML (MKV/WebM)

    fun ebml(docType: String = "matroska"): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))          // EBML magic
        val body = ByteArrayOutputStream()
        body.write(ascii(docType))
        out.write(0x80 or (8 + body.size()))                                      // master-size vint (small enough)
        out.write(0x42); out.write(0x82); out.write(body.size()); out.write(body.toByteArray())
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- Samsung SEF trailer

    data class SefRecord(
        val name: String,                          // e.g. MotionPhoto_Data, DualShot_ExtraImage
        val marker: Int,                           // e.g. 0x0A30 for MotionPhoto_Data
        val payload: ByteArray,
        /** layout=true -> [len][name][payload] (parser string-scan layout); false -> [payload] only (dir-record layout) */
        val embedNameString: Boolean = true
    )

    /**
     * Samsung Motion Photo / Dual Shot container:
     *   [primary image bytes] then for each record [u32 nameLen][name][payload],
     *   then [SEFT][SEFH][version u32][count u32][dir records x count][u32 0].
     */
    fun samsungContainer(
        primary: ByteArray,
        records: List<SefRecord>,
        seft: Boolean = true,
        sefhVersion: Long = 101,
        sefhCountOverride: Long? = null,
        corruptDirRecord: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(primary)
        val payloadSpans = mutableListOf<Triple<Int, Int, String>>()              // absStart, len, name
        for (r in records) {
            if (r.embedNameString) {
                out.write(le32(r.name.length.toLong())); out.write(ascii(r.name))
            }
            val start = out.size()
            out.write(r.payload)
            payloadSpans.add(Triple(start, r.payload.size, r.name))
        }
        val sefhPos = out.size() + (if (seft) 4 else 0)
        out.write(if (seft) ascii("SEFT") else ByteArray(0))
        out.write(ascii("SEFH"))
        out.write(le32(sefhVersion))
        out.write(le32(sefhCountOverride ?: records.size.toLong()))
        for ((index, r) in records.withIndex()) {
            val span = payloadSpans[index]
            val offsetBack = if (corruptDirRecord) 0L else (sefhPos - span.first).toLong()
            val length = if (corruptDirRecord) 0x7FFFFFFFL else span.second.toLong()
            out.write(le16(0))
            out.write(le16(r.marker))
            out.write(le32(offsetBack))
            out.write(le32(length))
        }
        out.write(le32(0))
        return out.toByteArray()
    }
}
