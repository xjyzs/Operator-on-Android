package com.xjyzs.operator.remote


import android.content.Context
import android.os.IBinder
import com.xjyzs.operator.IInputControl
import com.xjyzs.operator.remote.RemoteServiceLauncher.getServiceBinder

/**
 * App 侧工具类，负责通过 su + app_process 启动远程进程，
 * 并提供 ServiceManager 查找服务的统一入口。
 */
object RemoteServiceLauncher {

    /**
     * 启动远程进程（如果尚未运行）。
     * 进程在后台作为守护进程持续运行，不会阻塞调用者。
     *
     * @return 命令是否成功发出（不代表服务立刻可用，需用 [getServiceBinder] 轮询）
     */
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

    /**
     * 从 ServiceManager 获取远程服务的 Binder。
     * ServiceManager 是隐藏 API，通过反射访问；App 侧无法直接 import。
     */
    fun getServiceBinder(): IBinder? {
        return runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, InputControlService.SERVICE_NAME) as? IBinder
        }.getOrNull()
    }

    /**
     * 检查远程服务是否存活（Binder 在线 + ping 成功）。
     */
    fun isServiceAlive(): Boolean {
        return runCatching {
            val binder = getServiceBinder() ?: return false
            if (!binder.isBinderAlive) return false
            IInputControl.Stub.asInterface(binder).ping()
        }.getOrDefault(false)
    }
}