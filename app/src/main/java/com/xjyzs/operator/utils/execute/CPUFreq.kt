package com.xjyzs.operator.utils.execute

import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.io.OutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * 读取 CPU 频率，以获取应用是否加载完成
 */
object CPUFreq {
    private var suProcess: Process? = null
    private var suOs: OutputStream? = null
    private var suBis: BufferedInputStream? = null
    private val suLock = Object()
    var scalingMaxFreq: Long = -1L
        private set
    private var maxCpuDir: String = ""
    private lateinit var executor: ShellExecutor

    suspend fun init() {
        executor = ShellExecutor.getInstance()
        initCpuFreq()
    }

    private suspend fun initCpuFreq() {
        val dirList = executor.execute("ls /sys/devices/system/cpu/cpufreq/").stdout.split("\n")
            .filter { it.isNotBlank() }
        maxCpuDir =
            dirList.maxByOrNull { it } ?: throw RuntimeException("No cpufreq directories found")
        scalingMaxFreq =
            executor.execute("cat /sys/devices/system/cpu/cpufreq/$maxCpuDir/scaling_max_freq").stdout.toLong()
    }

    suspend fun getScalingCurFreq(): Long {
        try {
            if (maxCpuDir.isEmpty()) {
                initCpuFreq()
            }
            return executor.execute("cat /sys/devices/system/cpu/cpufreq/$maxCpuDir/scaling_cur_freq").stdout.toLong()
        } catch (_: Exception) {
            return -1L
        }
    }

    suspend fun isCpuIdle(): Boolean {
        val cur = getScalingCurFreq()
        val max = scalingMaxFreq
        return cur <= 0 || max <= 0 || cur.toFloat() / max < 0.8f  // 读不到就不参与判断
    }

    suspend fun waitForPageStable(
        timeout: Duration,
        stableCount: Int = 3,
    ) {
        val start = TimeSource.Monotonic.markNow()
        var stableCnt = 0
        while (start.elapsedNow() < timeout) {
            delay(200.milliseconds)
            if (isCpuIdle()) {
                if (++stableCnt >= stableCount) return
            } else {
                stableCnt = 0
            }
        }
    }

    fun destroy() {
        synchronized(suLock) {
            suOs?.close()
            suBis?.close()
            suProcess?.destroy()
            suProcess = null
            suOs = null
            suBis = null
        }
    }
}
