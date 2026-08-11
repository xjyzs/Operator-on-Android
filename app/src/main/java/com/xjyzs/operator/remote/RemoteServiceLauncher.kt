package com.xjyzs.operator.remote


import android.content.Context
import android.os.IBinder
import android.util.Log
import com.xjyzs.operator.IInputControl
import com.xjyzs.operator.remote.RemoteServiceLauncher.getServiceBinder
import com.xjyzs.operator.utils.ShellExecutor
import com.xjyzs.operator.utils.ShellType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RemoteServiceLauncher {
    suspend fun launch(context: Context): Boolean {
        if (isServiceAlive()) {
            return true
        }
        return try {
            val apkPath = context.packageCodePath
            val cmd =
                "CLASSPATH=$apkPath /system/bin/app_process /system/bin com.xjyzs.operator.remote.RemoteMain"
            if (ShellExecutor.getInstance().getShellType() == ShellType.ROOT) {
                withContext(Dispatchers.IO) {
                    Runtime.getRuntime().exec(arrayOf("su", "2000", "-c", cmd))
                    // 不 waitFor()，进程在后台持续运行
                }
            } else {
                ShellExecutor.getInstance().execute("nohup env $cmd </dev/null >/dev/null 2>&1 &")
            }
            true
        } catch (_: Exception) {
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