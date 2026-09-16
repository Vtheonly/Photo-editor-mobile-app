package com.vtheonly.recoveryscanner.scanner

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Direct shared-storage traversal for devices where the user explicitly granted
 * MANAGE_EXTERNAL_STORAGE. This is still user-space access: it does not expose
 * deleted UFS/eMMC blocks or another app's private internal directory.
 */
class DirectStorageScanner(private val context: Context) {
    fun isAvailable(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun scan(onProgress: (String) -> Unit): List<File> {
        if (!isAvailable()) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        val results = mutableListOf<File>()
        walk(root, results, onProgress, HashSet())
        return results
    }

    private fun walk(file: File, results: MutableList<File>, progress: (String) -> Unit, visited: MutableSet<String>) {
        val canonical = try { file.canonicalPath } catch (_: Throwable) { return }
        if (!visited.add(canonical)) return
        val children = try { file.listFiles() } catch (_: Throwable) { null } ?: return
        for (child in children) {
            if (child.isDirectory) {
                // Never let .nomedia or dot directories suppress a forensic-style scan.
                walk(child, results, progress, visited)
            } else if (child.isFile && child.canRead()) {
                progress(child.path)
                results += child
            }
        }
    }
}
