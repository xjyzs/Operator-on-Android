package com.xjyzs.operator.remote.utils

import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object UidTrafficMonitor {
    private val ioExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "UidTrafficMonitor-dump").apply { isDaemon = true }
    }

    @Volatile
    private var cachedSource: Pair<String, Array<String>>? = null

    /**
     * 通过直接调用或反射获取系统服务 Binder
     */
    private fun getServiceBinder(name: String): IBinder? {
        return runCatching {
            ServiceManager.getService(name)
        }.getOrNull() ?: runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        }.getOrNull()
    }

    /**
     * 获取候选的流量统计 Binder 服务列表：
     * - Android 13+ (API 33+) 主流版本及 Android 14/15/16 中 mAppUidStatsMap 位于 "netstats" 服务
     * - 部分 Android 13 版本位于 "connectivity" ("trafficcontroller")
     * - Android 10 ~ 12 位于 "netd" ("trafficcontroller")
     */
    private fun getCandidateSources(): List<Pair<String, Array<String>>> {
        cachedSource?.let { return listOf(it) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                "netstats" to emptyArray(),
                "connectivity" to arrayOf("trafficcontroller"),
                "netd" to arrayOf("trafficcontroller")
            )
        } else {
            listOf(
                "netd" to arrayOf("trafficcontroller"),
                "netstats" to emptyArray(),
                "connectivity" to arrayOf("trafficcontroller")
            )
        }
    }

    private data class DumpQueryResult(
        val foundMapSection: Boolean,
        val rxBytes: Long,
        val txBytes: Long
    )

    private fun isAppUidStatsHeader(trimmed: String): Boolean {
        if (!trimmed.startsWith("mAppUidStatsMap:")) return false
        val afterColon = trimmed.substringAfter("mAppUidStatsMap:").trim()
        // 排除 "mAppUidStatsMap: OK"、"mAppUidStatsMap: status: ..." 等状态行
        return afterColon.isEmpty() || afterColon == ":"
    }

    private fun queryUidFromService(
        serviceName: String,
        args: Array<String>,
        targetUid: Int
    ): DumpQueryResult {
        val binder = getServiceBinder(serviceName) ?: return DumpQueryResult(false, 0L, 0L)

        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        val future = ioExecutor.submit<DumpQueryResult> {
            var foundMapSection = false
            var rxBytes = 0L
            var txBytes = 0L
            try {
                BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(readFd))).use { reader ->
                    var inAppUidStats = false
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (!inAppUidStats) {
                            if (isAppUidStatsHeader(trimmed)) {
                                inAppUidStats = true
                                foundMapSection = true
                            }
                            continue
                        }

                        // 跳过表头 "uid rxBytes rxPackets txBytes txPackets"
                        if (trimmed.startsWith("uid")) continue

                        // 遇到空行或下一个 map 段结束解析
                        if (trimmed.isEmpty() || !trimmed[0].isDigit()) {
                            inAppUidStats = false
                            continue
                        }

                        // 数据行格式: <uid> <rxBytes> <rxPackets> <txBytes> <txPackets>
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val uid = parts[0].removeSuffix(":").toIntOrNull()
                            if (uid == targetUid) {
                                rxBytes = parts[1].toLongOrNull() ?: 0L
                                if (parts.size >= 4) {
                                    txBytes = parts[3].toLongOrNull() ?: 0L
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            DumpQueryResult(foundMapSection, rxBytes, txBytes)
        }

        try {
            binder.dump(writeFd.fileDescriptor, args)
        } catch (_: Exception) {
        } finally {
            runCatching { writeFd.close() }
        }

        return try {
            future.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            DumpQueryResult(false, 0L, 0L)
        }
    }

    /**
     * 读取指定 UID 当前的累计收发字节数 [rxBytes, txBytes]
     */
    fun getUidTraffic(targetUid: Int): LongArray {
        for (source in getCandidateSources()) {
            val result = queryUidFromService(source.first, source.second, targetUid)
            if (result.foundMapSection) {
                if (cachedSource == null) {
                    cachedSource = source
                }
                return longArrayOf(result.rxBytes, result.txBytes)
            }
        }

        // 若之前缓存的源未找到段，清除缓存再全量探测一次
        if (cachedSource != null) {
            cachedSource = null
            for (source in getCandidateSources()) {
                val result = queryUidFromService(source.first, source.second, targetUid)
                if (result.foundMapSection) {
                    cachedSource = source
                    return longArrayOf(result.rxBytes, result.txBytes)
                }
            }
        }

        return longArrayOf(0L, 0L)
    }

    /**
     * 读取指定 UID 当前的累计下载字节数
     */
    fun getUidRxBytes(targetUid: Int): Long = getUidTraffic(targetUid)[0]

    /**
     * 读取指定 UID 当前的累计上传字节数
     */
    fun getUidTxBytes(targetUid: Int): Long = getUidTraffic(targetUid)[1]
}
