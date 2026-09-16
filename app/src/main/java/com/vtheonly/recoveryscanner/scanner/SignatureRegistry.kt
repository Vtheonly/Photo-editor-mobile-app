package com.vtheonly.recoveryscanner.scanner

import java.io.InputStream

data class Signature(val type: MediaType, val extension: String, val mime: String, val magic: ByteArray, val confidence: Int = 95)

object SignatureRegistry {
    val all = listOf(
        Signature(MediaType.JPEG, "jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 99),
        Signature(MediaType.PNG, "png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), 99),
        Signature(MediaType.GIF, "gif", "image/gif", "GIF8".toByteArray(), 99),
        Signature(MediaType.WEBP, "webp", "image/webp", "RIFF".toByteArray(), 90),
        Signature(MediaType.BMP, "bmp", "image/bmp", byteArrayOf(0x42, 0x4D), 99),
        Signature(MediaType.HEIC, "heic", "image/heic", byteArrayOf(), 85),
        Signature(MediaType.MP4, "mp4", "video/mp4", byteArrayOf(), 85),
        Signature(MediaType.MOV, "mov", "video/quicktime", byteArrayOf(), 85),
        Signature(MediaType.THREE_GP, "3gp", "video/3gpp", byteArrayOf(), 85),
        Signature(MediaType.MKV, "mkv", "video/x-matroska", byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), 99),
        Signature(MediaType.WEBM, "webm", "video/webm", byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), 96)
    )

    fun detect(input: InputStream, name: String): Detection? {
        val header = ByteArray(64 * 1024); val read = input.read(header); if (read <= 0) return null
        fun startsWith(bytes: ByteArray) = bytes.isNotEmpty() && read >= bytes.size && header.copyOf(bytes.size).contentEquals(bytes)
        if (startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return Detection(MediaType.JPEG, "jpg", 99)
        if (startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) return Detection(MediaType.PNG, "png", 99)
        if (startsWith("GIF87a".toByteArray()) || startsWith("GIF89a".toByteArray())) return Detection(MediaType.GIF, "gif", 99)
        if (read >= 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())) return Detection(MediaType.WEBP, "webp", 99)
        if (startsWith(byteArrayOf(0x42, 0x4D))) return Detection(MediaType.BMP, "bmp", 98)
        if (startsWith(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) {
            val text = String(header, 0, minOf(read, 512), Charsets.ISO_8859_1).lowercase()
            return if (text.contains("webm")) Detection(MediaType.WEBM, "webm", 97) else Detection(MediaType.MKV, "mkv", 97)
        }
        if (read >= 12 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
            val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
            val type = when {
                brand == "qt  " -> MediaType.MOV
                brand.startsWith("3gp") || brand.startsWith("3g2") -> MediaType.THREE_GP
                brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heis", "hevm") -> MediaType.HEIC
                else -> MediaType.MP4
            }
            return Detection(type, type.name.lowercase().replace("three_gp", "3gp"), 95)
        }
        val jpeg = findMagic(header, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())); if (jpeg > 0) return Detection(MediaType.JPEG, "jpg", 88, jpeg.toLong())
        val png = findMagic(header, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)); if (png > 0) return Detection(MediaType.PNG, "png", 88, png.toLong())
        return null
    }

    private fun findMagic(buffer: ByteArray, magic: ByteArray): Int { outer@ for (i in 1..buffer.size - magic.size) { for (j in magic.indices) if (buffer[i + j] != magic[j]) continue@outer; return i }; return -1 }
}

data class Detection(val type: MediaType, val extension: String, val confidence: Int, val offset: Long = 0L)
