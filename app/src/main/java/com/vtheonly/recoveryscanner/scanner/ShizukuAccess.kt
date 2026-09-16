package com.vtheonly.recoveryscanner.scanner

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

class ShizukuAccess {
    companion object { const val REQUEST_CODE = 7201 }
    fun available(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun granted(): Boolean = try { available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun requestPermission() { if (available() && !granted()) Shizuku.requestPermission(REQUEST_CODE) }
}
