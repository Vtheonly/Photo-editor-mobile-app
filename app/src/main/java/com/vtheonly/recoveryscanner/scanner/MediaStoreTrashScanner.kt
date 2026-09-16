package com.vtheonly.recoveryscanner.scanner

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

class MediaStoreTrashScanner(private val context: Context) {
    fun scan(): List<ScanResult> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val resolver = context.contentResolver
        val out = mutableListOf<ScanResult>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        for (collection in collections) {
            val bundle = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }
            try {
                resolver.query(collection, projection, bundle, null)?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    while (c.moveToNext()) {
                        val itemId = c.getLong(id)
                        val uri = Uri.withAppendedPath(collection, itemId.toString())
                        val display = if (name >= 0) c.getString(name) ?: "trashed-$itemId" else "trashed-$itemId"
                        val bytes = if (size >= 0) c.getLong(size) else 0L
                        val type = if (mime >= 0 && c.getString(mime)?.startsWith("video/") == true) MediaType.MP4 else MediaType.JPEG
                        out += ScanResult(display, uri.toString(), type, if (type == MediaType.MP4) "mp4" else "jpg", display.substringAfterLast('.', ""), bytes, 90, false, false, 0, bytes, RecoveryQuality.LIKELY_RECOVERABLE)
                    }
                }
            } catch (_: Throwable) {
                // Some providers reject one or both query flags. The ordinary MediaStore scanner remains available.
            }
        }
        return out
    }
}
