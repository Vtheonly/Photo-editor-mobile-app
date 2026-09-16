package com.vtheonly.recoveryscanner.support

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/**
 * Stub SAF DocumentsProvider serving real files from a temp directory.
 *
 * URI shapes (verified against SDK 34 DocumentsContract bytecode):
 *   content://AUTH/tree/<treeDocId>                          -> the tree root document
 *   content://AUTH/tree/<treeDocId>/document/<docId>         -> a single document
 *   content://AUTH/tree/<treeDocId>/document/<docId>/children-> children of docId
 *
 * createDocument arrives as resolver.call("android:createDocument") with bundle keys
 * uri / mime_type / _display_name and result key "uri" (same as the framework).
 */
@Suppress("DEPRECATION")
class StubDocumentsProvider : ContentProvider() {

    companion object {
        @JvmStatic
        var root: File = File("/")
        const val TREE_DOC_ID = "root"
        const val METHOD_CREATE_DOCUMENT = "android:createDocument"
        const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
    }

    private sealed class Route {
        data class Doc(val docId: String) : Route()
        data class Children(val parentDocId: String) : Route()
    }

    private fun route(uri: Uri): Route? {
        val segs = uri.pathSegments ?: return null
        if (segs.isEmpty() || segs[0] != "tree") return null
        val docIdx = segs.indexOf("document")
        return when {
            segs.lastOrNull() == "children" && docIdx in 0..segs.size - 2 ->
                Route.Children(segs[docIdx + 1])
            docIdx in 0..segs.size - 2 -> Route.Doc(segs[docIdx + 1])
            segs.size >= 2 -> Route.Doc(segs[1])
            else -> null
        }
    }

    private fun resolve(docId: String?): File? {
        if (docId.isNullOrEmpty()) return null
        val decoded = Uri.decode(docId)
        val f = if (decoded == TREE_DOC_ID || decoded == ".") root else File(root, decoded)
        return try {
            if (f.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) f else null
        } catch (_: Throwable) { null }
    }

    private fun idOf(file: File): String {
        val rel = try { file.relativeTo(root).path } catch (_: Throwable) { file.name }
        return Uri.encode(if (rel.isEmpty()) TREE_DOC_ID else rel)
    }

    private fun dirOf(uri: Uri): File? = when (val r = route(uri)) {
        is Route.Doc -> resolve(r.docId)
        is Route.Children -> resolve(r.parentDocId)
        null -> null
    }

    // ---------------------------------------------------------------- provider

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val cols = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        return when (val r = route(uri)) {
            is Route.Children -> {
                val parent = resolve(r.parentDocId) ?: return null
                if (!parent.isDirectory) return null
                val children = parent.listFiles()?.sortedBy { it.name } ?: return null
                MatrixCursor(cols).apply { children.forEach { addRowFor(cols, it) } }
            }
            is Route.Doc -> {
                val f = resolve(r.docId) ?: return null
                if (!f.exists()) return null
                MatrixCursor(cols).apply { addRowFor(cols, f) }
            }
            null -> null
        }
    }

    private fun MatrixCursor.addRowFor(cols: Array<out String>, f: File) {
        val row = MutableList<Any?>(cols.size) { "" }
        for ((i, c) in cols.withIndex()) {
            row[i] = when (c) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> idOf(f)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> f.name
                DocumentsContract.Document.COLUMN_MIME_TYPE ->
                    if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> if (f.isFile) f.length() else 0L
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                DocumentsContract.Document.COLUMN_FLAGS ->
                    (DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                        or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
                else -> ""
            }
        }
        addRow(row.toTypedArray())
    }

    /** Direct resolver.insert() route (kept for completeness; framework uses call()). */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val parent = when (val r = route(uri)) {
            is Route.Children -> resolve(r.parentDocId)
            else -> dirOf(uri)
        } ?: return null
        if (!parent.isDirectory) return null
        val name = values?.getAsString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: values?.getAsString("_display_name") ?: return null
        val target = File(parent, name)
        if (target.exists()) return null          // SAF refuses instead of silently destroying
        if (!target.createNewFile()) return null
        return DocumentsContract.buildDocumentUriUsingTree(uri, idOf(target))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = dirOf(uri) ?: return null
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val f = dirOf(uri) ?: return 0
        return if (f.delete()) 1 else 0
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String? =
        if (dirOf(uri)?.isDirectory == true) DocumentsContract.Document.MIME_TYPE_DIR
        else "application/octet-stream"

    /** The framework route for createDocument/deleteDocument. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            METHOD_CREATE_DOCUMENT -> {
                val parentUri = extras?.getParcelable<Uri>("uri") ?: return null
                val parent = when (val r = route(parentUri)) {
                    is Route.Doc -> resolve(r.docId)
                    is Route.Children -> resolve(r.parentDocId)
                    null -> null
                } ?: return null
                if (!parent.isDirectory) return null
                val name = extras.getString("_display_name") ?: return null
                val target = File(parent, name)
                if (target.exists() || !target.createNewFile()) return null
                val result = DocumentsContract.buildDocumentUriUsingTree(parentUri, idOf(target))
                return Bundle().apply { putParcelable("uri", result) }
            }
            METHOD_DELETE_DOCUMENT -> {
                val targetUri = extras?.getParcelable<Uri>("uri") ?: return null
                val f = dirOf(targetUri) ?: return null
                return if (f.delete()) Bundle.EMPTY else null
            }
            else -> return null
        }
    }
}

/** Installs the stub provider and hands out tree URIs rooted at a real directory. */
object SAF {
    const val AUTHORITY = "com.vtheonly.recoveryscanner.stub.documents"

    fun install(root: File): Uri {
        StubDocumentsProvider.root = root
        try {
            org.robolectric.Robolectric.buildContentProvider(StubDocumentsProvider::class.java)
                .create(AUTHORITY)
        } catch (_: IllegalStateException) {
            // already registered in this class loader - root was updated above
        }
        return Uri.parse("content://$AUTHORITY/tree/${StubDocumentsProvider.TREE_DOC_ID}")
    }
}
