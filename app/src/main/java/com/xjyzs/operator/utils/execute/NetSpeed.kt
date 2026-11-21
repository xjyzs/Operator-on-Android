package com.xjyzs.operator.utils.execute

import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.data.SharedState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 读取目标 App 网速，以获取应用是否加载完成
 */
object NetSpeed {
    fun resolveUid(packageName: String): Int {
        return runCatching {
            val context = SharedState.appContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                context.packageManager.getPackageUid(packageName, 0)
            }
        }.getOrDefault(-1)
    }

    /** 累计下载 Bytes */
    fun getRxBytes(packageName: String): Long {
        val uid = resolveUid(packageName)
        if (uid >= 0) {
            return InputControlUtils.getUidRxBytes(uid)
        }
        return 0L
    }

    /** 获取下载网速 (Bytes/s) */
    suspend fun sampleRxSpeed(packageName: String, sampleIntervalMs: Long = 1000L): Double {
        val t1 = SystemClock.elapsedRealtime()
        val rx1 = getRxBytes(packageName)
        delay(sampleIntervalMs.coerceAtLeast(100L).milliseconds)
        val t2 = SystemClock.elapsedRealtime()
        val rx2 = getRxBytes(packageName)

        val deltaBytes = (rx2 - rx1).coerceAtLeast(0L)
        val deltaMs = (t2 - t1).coerceAtLeast(1L)
        return deltaBytes * 1000.0 / deltaMs
    }
}
