package com.xjyzs.operator.remote


import android.content.Context
import android.os.IBinder
import com.xjyzs.operator.IInputControl
import com.xjyzs.operator.remote.RemoteServiceLauncher.getServiceBinder

object RemoteServiceLauncher {
    fun launch(context: Context): Boolean {
        if (isServiceAlive()) {
            return true
        }
        return try {
            val apkPath = context.packageCodePath
            Runtime.getRuntime().exec(
                arrayOf(
                    "su",
                    "-c",
                    "CLASSPATH=$apkPath /system/bin/app_process /system/bin com.xjyzs.operator.remote.RemoteMain"
                )
            )
            // 不 waitFor()，进程在后台持续运行
            true
        } catch (e: Exception) {
            false
        }
    }
    fun getServiceBinder(): IBinder? {
        return runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, InputControlService.SERVICE_NAME) as? IBinder
        }.getOrNull()
    }
    fun isServiceAlive(): Boolean {
        return runCatching {
            val binder = getServiceBinder() ?: return false
            if (!binder.isBinderAlive) return false
            IInputControl.Stub.asInterface(binder).ping()
        }.getOrDefault(false)
    }
}