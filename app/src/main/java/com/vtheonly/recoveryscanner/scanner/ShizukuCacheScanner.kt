package com.vtheonly.recoveryscanner.scanner

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.vtheonly.recoveryscanner.IShizukuScanner
import com.vtheonly.recoveryscanner.ShizukuCacheUserService
import rikka.shizuku.Shizuku

/** Optional bridge for shell-readable Samsung/app cache locations. */
class ShizukuCacheScanner(private val onFinished: (List<String>) -> Unit) {
    private var connection: ServiceConnection? = null

    fun start() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) { onFinished(emptyList()); return }
            val args = Shizuku.UserServiceArgs(ComponentName("com.vtheonly.recoveryscanner", ShizukuCacheUserService::class.java.name))
                .daemon(false).processNameSuffix("scanner").version(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    try { onFinished(IShizukuScanner.Stub.asInterface(service).scanAndStage().toList()) }
                    catch (_: Throwable) { onFinished(emptyList()) }
                    finally { Shizuku.unbindUserService(args, this, true); connection = null }
                }
                override fun onServiceDisconnected(name: ComponentName) { onFinished(emptyList()); connection = null }
            }
            connection = conn
            Shizuku.bindUserService(args, conn)
        } catch (_: Throwable) { onFinished(emptyList()) }
    }
}
