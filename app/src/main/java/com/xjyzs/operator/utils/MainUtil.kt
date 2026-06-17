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

    fun init() {
        initSuProcess()
        initCpuFreq()
    }

    fun initSuProcess() {
        synchronized(suLock) {
            if (suProcess == null) {
                suProcess = Runtime.getRuntime().exec("su")
                suOs = suProcess!!.outputStream
                suBis = BufferedInputStream(suProcess!!.inputStream)
            }
        }
    }

    fun executeSuCommand(command: String): String {
        synchronized(suLock) {
            val os = suOs ?: throw RuntimeException("su process not initialized")
            val bis = suBis ?: throw RuntimeException("su process not initialized")

            while (bis.available() > 0) {
                bis.read()
            }

            val marker = "EOF_MARKER_${System.nanoTime()}"
            os.write("$command; echo $marker\n".toByteArray())
            os.flush()

            val result = StringBuilder()
            val startTime = System.currentTimeMillis()

            while (true) {
                if (bis.available() > 0) {
                    val byte = bis.read()
                    if (byte == -1) break
                    result.append(byte.toChar())
                    if (result.contains(marker)) {
                        break
                    }
                } else {
                    if (System.currentTimeMillis() - startTime > 5000) {
                        throw RuntimeException("su command timeout: $command")
                    }
                    Thread.sleep(10)
                }
            }
            return result.toString().replace(marker, "").trim()
        }
    }

    private fun initCpuFreq() {
        val dirList = executeSuCommand("ls /sys/devices/system/cpu/cpufreq/")
            .split("\n")
            .filter { it.isNotBlank() }
        maxCpuDir =
            dirList.maxByOrNull { it } ?: throw RuntimeException("No cpufreq directories found")
        scalingMaxFreq =
            executeSuCommand("cat /sys/devices/system/cpu/cpufreq/$maxCpuDir/scaling_max_freq")
                .toLong()
    }
    fun getScalingCurFreq(): Long {
        try {
            if (maxCpuDir.isEmpty()) {
                initCpuFreq()
            }
            return executeSuCommand("cat /sys/devices/system/cpu/cpufreq/$maxCpuDir/scaling_cur_freq").toLong()
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