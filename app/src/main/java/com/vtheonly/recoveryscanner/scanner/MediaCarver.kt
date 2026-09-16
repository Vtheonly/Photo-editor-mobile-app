package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Parses a candidate from a fresh stream positioned at its signature.
 * This is deliberately format-aware: recovery never assumes that an embedded
 * candidate extends to EOF when a container exposes a real boundary.
 */
class MediaCarver(private val context: Context) {
    fun analyze(uri: Uri, offset: Long, type: MediaType, sourceSize: Long): CarveResult {
        val input = context.contentResolver.openInputStream(uri) ?: return CarveResult(0, RecoveryQuality.SIGNATURE_ONLY, 0)
        input.use { raw ->
            val stream = BufferedInputStream(raw, 128 * 1024)
            if (!skipFully(stream, offset)) return CarveResult(0, RecoveryQuality.SIGNATURE_ONLY, 0)
            return when (type) {
                MediaType.JPEG -> jpeg(stream)
                MediaType.PNG -> png(stream)
                MediaType.GIF -> gif(stream)
                MediaType.WEBP -> webp(stream)
                MediaType.BMP -> bmp(stream)
                MediaType.MP4, MediaType.MOV, MediaType.THREE_GP, MediaType.HEIC -> isoBmff(stream, sourceSize - offset)
                else -> CarveResult(0, RecoveryQuality.SIGNATURE_ONLY, 0)
            }
        }
    }

    private fun jpeg(s: InputStream): CarveResult {
        if (readByte(s) != 0xFF || readByte(s) != 0xD8) return bad()
        var sawScan = false
        while (true) {
            var b = readByte(s)
            while (b == 0xFF) b = readByte(s)
            if (b < 0) return partial(s)
            if (b == 0xD9) return valid(2 + 0, 98)
            if (b == 0xDA) sawScan = true
            if (b == 0xD8 || b in 0xD0..0xD7 || b == 0x01) continue
            val hi = readByte(s); val lo = readByte(s)
            if (hi < 0 || lo < 0) return partial(s)
            val length = (hi shl 8) or lo
            if (length < 2) return bad()
            if (b == 0xDA) {
                var prev = -1
                while (true) {
                    val x = readByte(s)
                    if (x < 0) return partial(s)
                    if (prev == 0xFF) {
                        when {
                            x == 0x00 -> prev = -1
                            x == 0xD9 -> return valid(0, if (sawScan) 99 else 94)
                            x in 0xD0..0xD7 -> prev = -1
                            x == 0xFF -> prev = 0xFF
                            else -> prev = x
                        }
                    } else if (x == 0xFF) prev = 0xFF
                }
            }
            if (!skipFully(s, length - 2L)) return partial(s)
        }
    }

    private fun png(s: InputStream): CarveResult {
        val sig = ByteArray(8); if (!readExact(s, sig) || !sig.contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) return bad()
        var chunks = 0
        while (true) {
            val len = readU32(s) ?: return partial(s)
            val type = ByteArray(4); if (!readExact(s, type)) return partial(s)
            if (len > 128L * 1024L * 1024L) return bad()
            if (!skipFully(s, len + 4L)) return partial(s)
            chunks++
            if (type.contentEquals("IEND".toByteArray())) return valid(0, if (chunks >= 3) 99 else 94)
        }
    }

    private fun gif(s: InputStream): CarveResult {
        val h = ByteArray(13); if (!readExact(s, h)) return partial(s)
        if (!String(h, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }) return bad()
        val packed = h[10].toInt() and 0xFF
        if ((packed and 0x80) != 0 && !skipFully(s, 3L * (1L shl ((packed and 7) + 1)))) return partial(s)
        while (true) {
            when (val block = readByte(s)) {
                0x3B -> return valid(0, 98)
                0x21 -> {
                    if (readByte(s) < 0) return partial(s)
                    if (!skipSubBlocks(s)) return partial(s)
                }
                0x2C -> {
                    if (!skipFully(s, 9)) return partial(s)
                    val p = readByte(s); if (p < 0) return partial(s)
                    if ((p and 0x80) != 0 && !skipFully(s, 3L * (1L shl ((p and 7) + 1)))) return partial(s)
                    if (readByte(s) < 0 || !skipSubBlocks(s)) return partial(s)
                }
                else -> return bad()
            }
        }
    }

    private fun webp(s: InputStream): CarveResult {
        val h = ByteArray(12); if (!readExact(s, h)) return partial(s)
        if (!h.copyOfRange(0,4).contentEquals("RIFF".toByteArray()) || !h.copyOfRange(8,12).contentEquals("WEBP".toByteArray())) return bad()
        val size = u32(h, 4) + 8L
        if (size < 20 || size > 2L * 1024L * 1024L * 1024L) return bad()
        return valid(size, 97)
    }

    private fun bmp(s: InputStream): CarveResult {
        val h = ByteArray(54); if (!readExact(s, h)) return partial(s)
        if (h[0].toInt() != 0x42 || h[1].toInt() != 0x4D) return bad()
        val size = u32(h, 2)
        val dib = u32(h, 14)
        val pixelOffset = u32(h, 10)
        if (size < 54 || dib < 12 || pixelOffset >= size) return bad()
        return valid(size, 97)
    }

    private fun isoBmff(s: InputStream, available: Long): CarveResult {
        var consumed = 0L
        var boxes = 0
        var sawFtyp = false
        var sawMdat = false
        var sawMoov = false
        while (consumed + 8 <= available) {
            val header = ByteArray(8); if (!readExact(s, header)) break
            var boxSize = u32(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            if (boxSize == 1L) {
                val ext = readU64(s) ?: return partial(s)
                boxSize = ext; headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = available - consumed
            }
            if (boxSize < headerSize || boxSize > available - consumed) return partial(s)
            if (type == "ftyp") sawFtyp = true
            if (type == "mdat") sawMdat = true
            if (type == "moov" || type == "meta") sawMoov = true
            val payload = boxSize - headerSize
            if (!skipFully(s, payload)) return partial(s)
            consumed += boxSize
            boxes++
            if (type == "moov" && consumed >= 16) return valid(consumed, 98)
        }
        if (sawFtyp && (sawMoov || sawMdat) && consumed > 0) return valid(consumed, 90)
        return if (boxes > 0) partial(s, consumed) else bad()
    }

    private fun valid(length: Long, confidence: Int): CarveResult = CarveResult(length, RecoveryQuality.VALID, confidence)
    private fun partial(s: InputStream, length: Long = 0): CarveResult = CarveResult(length, RecoveryQuality.PARTIAL_OR_CORRUPT, 55)
    private fun bad(): CarveResult = CarveResult(0, RecoveryQuality.PARTIAL_OR_CORRUPT, 20)

    private fun skipSubBlocks(s: InputStream): Boolean {
        while (true) {
            val n = readByte(s); if (n < 0) return false
            if (n == 0) return true
            if (!skipFully(s, n.toLong())) return false
        }
    }

    private fun readU32(s: InputStream): Long? { val b = ByteArray(4); return if (readExact(s,b)) u32(b,0) else null }
    private fun readU64(s: InputStream): Long? { val b = ByteArray(8); if (!readExact(s,b)) return null; var v=0L; for(x in b) v=(v shl 8) or (x.toLong() and 255); return v }
    private fun u32(b: ByteArray, o: Int): Long = ((b[o].toLong() and 255) shl 24) or ((b[o+1].toLong() and 255) shl 16) or ((b[o+2].toLong() and 255) shl 8) or (b[o+3].toLong() and 255)
    private fun readByte(s: InputStream): Int = s.read()
    private fun readExact(s: InputStream, b: ByteArray): Boolean { var p=0; while(p<b.size){val n=s.read(b,p,b.size-p);if(n<0)return false;p+=n};return true }
    private fun skipFully(s: InputStream, n: Long): Boolean { var left=n; while(left>0){val k=s.skip(left);if(k>0){left-=k;continue};if(s.read()<0)return false;left--};return true }
}

data class CarveResult(val length: Long, val quality: RecoveryQuality, val confidence: Int)
