package com.vtheonly.recoveryscanner.scanner

enum class MediaType { JPEG, PNG, GIF, WEBP, BMP, HEIC, MP4, MOV, THREE_GP, UNKNOWN }

data class ScanResult(
    val sourceName: String,
    val sourceUri: String,
    val detectedType: MediaType,
    val detectedExtension: String,
    val originalExtension: String,
    val sizeBytes: Long,
    val confidence: Int,
    val isExtensionMismatch: Boolean,
    val isEmbeddedCandidate: Boolean = false,
    val offset: Long = 0L,
    val recoveredLength: Long = 0L,
    val quality: RecoveryQuality = RecoveryQuality.SIGNATURE_ONLY
)

enum class RecoveryQuality {
    VALID,
    LIKELY_RECOVERABLE,
    PARTIAL_OR_CORRUPT,
    SIGNATURE_ONLY
}
