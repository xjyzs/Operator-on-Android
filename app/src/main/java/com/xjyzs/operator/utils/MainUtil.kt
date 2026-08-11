package com.xjyzs.operator.utils

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.BufferedInputStream
import java.io.OutputStream

fun clickVibrate(vibrator: Vibrator) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val attributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
        vibrator.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
            attributes
        )
    }
}

object CpuFreq {
    private var suProcess: Process? = null
    private var suOs: OutputStream? = null
    private var suBis: BufferedInputStream? = null
    private val suLock = Object()
    var scalingMaxFreq: Long = 0L
        private set
    private var maxCpuDir: String = ""
    private lateinit var executor: ShellExecutor

    suspend fun init() {
        executor=ShellExecutor.getInstance()
        initCpuFreq()
    }

    private suspend fun initCpuFreq() {
        val dirList = executor.execute("ls /sys/devices/system/cpu/cpufreq/").stdout
            .split("\n")
            .filter { it.isNotBlank() }
        maxCpuDir =
            dirList.maxByOrNull { it } ?: throw RuntimeException("No cpufreq directories found")
        scalingMaxFreq =
            executor.execute("cat /sys/devices/system/cpu/cpufreq/$maxCpuDir/scaling_max_freq").stdout
                .toLong()
    }
    suspend fun getScalingCurFreq(): Long {
        try {
            if (maxCpuDir.isEmpty()) {
                initCpuFreq()
            }
            return executor.execute("cat /sys/devices/system/cpu/cpufreq/$maxCpuDir/scaling_cur_freq").stdout.toLong()
        } catch (e: Exception) {
            return -1L
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

fun String.unescapeJava(): String {
    val builder = StringBuilder()
    var i = 0
    while (i < this.length) {
        val c = this[i]
        if (c == '\\' && i + 1 < this.length) {
            val next = this[i + 1]
            when (next) {
                'n' -> { builder.append('\n'); i += 2 }
                't' -> { builder.append('\t'); i += 2 }
                'r' -> { builder.append('\r'); i += 2 }
                'b' -> { builder.append('\b'); i += 2 }
                'f' -> { builder.append('\u000C'); i += 2 }
                '\"' -> { builder.append('\"'); i += 2 }
                '\'' -> { builder.append('\''); i += 2 }
                '\\' -> { builder.append('\\'); i += 2 }
                'u' -> {
                    if (i + 5 < this.length) {
                        try {
                            val code = this.substring(i + 2, i + 6).toInt(16)
                            builder.append(code.toChar())
                            i += 6
                        } catch (e: NumberFormatException) {
                            builder.append(c)
                            i++
                        }
                    } else {
                        builder.append(c)
                        i++
                    }
                }
                else -> {
                    builder.append(c)
                    i++
                }
            }
        } else {
            builder.append(c)
            i++
        }
    }
    return builder.toString()
}