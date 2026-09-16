package com.vtheonly.recoveryscanner

import java.io.BufferedReader
import java.io.InputStreamReader

/** Runs as Shizuku's shell-identity UserService when the user explicitly authorizes it. */
class ShizukuCacheUserService : IShizukuScanner.Stub() {
    override fun scanAndStage(): Array<String> {
        val output = "/sdcard/Download/RecoveryScanner/Shizuku"
        val roots = listOf(
            "/storage/emulated/0/Android/data/com.sec.android.gallery3d/cache",
            "/storage/emulated/0/Android/data/com.sec.android.gallery3d/files",
            "/storage/emulated/0/Android/data/com.sec.android.app.camera/cache",
            "/storage/emulated/0/Android/data/com.samsung.android.app.notes/cache"
        )
        val quotedRoots = roots.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val command = "mkdir -p '$output'; find $quotedRoots -type f -size +10k 2>/dev/null | head -n 1000"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val lines = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }
            process.waitFor()
            val staged = mutableListOf<String>()
            for ((index, source) in lines.withIndex()) {
                if (index >= 1000) break
                val safe = source.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = "$output/${index}_$safe"
                val copy = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cp -- '$source' '$target' 2>/dev/null"))
                copy.waitFor()
                if (copy.exitValue() == 0) staged += target
            }
            staged.toTypedArray()
        } catch (_: Throwable) { emptyArray() }
    }
}
