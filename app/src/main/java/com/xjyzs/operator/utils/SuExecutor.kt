package com.xjyzs.operator.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

class SuExecutor private constructor() {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isTimeout: Boolean = false,
        val error: Throwable? = null
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && error == null
    }

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null

    // 使用专用的 IO 调度器，避免阻塞主线程
    private val suDispatcher = Dispatchers.IO

    /**
     * 启动并初始化 su 进程
     */
    private fun startShell() {
        try {
            closeResources() // 确保旧资源完全释放

            val proc = ProcessBuilder("su").start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
            stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
        } catch (e: Exception) {
            closeResources()
            throw IllegalStateException("无法启动 su 进程，设备可能未 Root 或拒绝了权限", e)
        }
    }

    /**
     * 判断当前 su 进程是否还活着
     */
    private fun isProcessAlive(): Boolean {
        val proc = process ?: return false
        return try {
            proc.exitValue()
            false // 如果 exitValue() 没有抛出异常，说明进程已退出
        } catch (e: IllegalThreadStateException) {
            true // 仍处于运行状态
        }
    }

    /**
     * 释放所有流和进程资源
     */
    private fun closeResources() {
        runCatching { writer?.close() }
        runCatching { stdoutReader?.close() }
        runCatching { stderrReader?.close() }
        runCatching { process?.destroy() }
        writer = null
        stdoutReader = null
        stderrReader = null
        process = null
    }

    /**
     * 全局唯一的命令执行入口
     *
     * @param command 要执行的 Shell 命令
     * @param timeoutMs 超时时间（毫秒），默认 10 秒
     */
    suspend fun execute(command: String, timeoutMs: Long = 10000L): Result = mutex.withLock {
        withContext(suDispatcher) {
            // 1. 检查并重建进程
            if (!isProcessAlive()) {
                try {
                    startShell()
                } catch (e: Exception) {
                    return@withContext Result(-1, "", "", error = e)
                }
            }

            val currentWriter = writer ?: return@withContext Result(-1, "", "", error = IllegalStateException("Writer 为空"))
            val currentStdout = stdoutReader ?: return@withContext Result(-1, "", "", error = IllegalStateException("Stdout 为空"))
            val currentStderr = stderrReader ?: return@withContext Result(-1, "", "", error = IllegalStateException("Stderr 为空"))

            // 生成本次执行唯一的边界标记，用于区分不同命令的输出
            val uuid = UUID.randomUUID().toString().take(8)
            val stdoutBoundary = "STDOUT_END_$uuid"
            val stderrBoundary = "STDERR_END_$uuid"

            // 2. 写入待执行命令和边界控制命令
            try {
                currentWriter.write("$command\n")
                // 立即捕获命令的退出状态
                currentWriter.write("_exit_val=\$?\n")
                // 强制换行，防止上一个命令输出没有以换行符结尾
                currentWriter.write("echo \"\"\n")
                // 打印标准输出边界标识符和退出状态码
                currentWriter.write("echo $stdoutBoundary \$_exit_val\n")
                // 在标准错误中做同样的处理
                currentWriter.write("echo \"\" >&2\n")
                currentWriter.write("echo $stderrBoundary >&2\n")
                currentWriter.flush()
            } catch (e: Exception) {
                // 如果写入失败，通常说明 Shell 管道意外断开，清理进程
                closeResources()
                return@withContext Result(-1, "", "", error = e)
            }

            // 3. 异步读取标准输出
            val stdoutJob = async {
                val sb = StringBuilder()
                var exitCode = -1
                try {
                    var line: String?
                    while (isActive) {
                        line = currentStdout.readLine() ?: break
                        if (line.startsWith(stdoutBoundary)) {
                            // 匹配到边界，提取紧随其后的 Exit Code
                            exitCode = line.substringAfter(stdoutBoundary).trim().toIntOrNull() ?: -1
                            break
                        }
                        sb.append(line).append("\n")
                    }
                } catch (e: Exception) {
                    // 读取被阻断或异常
                }
                // 清理掉多余添加的换行符
                val output = sb.toString().removeSuffix("\n").removeSuffix("\n")
                Pair(exitCode, output)
            }

            // 4. 异步读取错误输出
            val stderrJob = async {
                val sb = StringBuilder()
                try {
                    var line: String?
                    while (isActive) {
                        line = currentStderr.readLine() ?: break
                        if (line.startsWith(stderrBoundary)) {
                            break
                        }
                        sb.append(line).append("\n")
                    }
                } catch (e: Exception) {
                    // 读取被阻断或异常
                }
                sb.toString().removeSuffix("\n").removeSuffix("\n")
            }

            // 5. 等待执行结果并控制超时
            try {
                withTimeout(timeoutMs) {
                    val (exitCode, stdout) = stdoutJob.await()
                    val stderr = stderrJob.await()
                    Result(exitCode, stdout, stderr)
                }
            } catch (e: TimeoutCancellationException) {
                // 若命令超时/卡死，我们必须彻底杀死当前的进程，
                // 否则卡死的任务仍会占用该 Shell 导致后续命令也全部阻塞。
                closeResources()
                Result(-1, "", "命令超时并已重置后台 Shell 进程 ($timeoutMs ms)", isTimeout = true, error = e)
            } catch (e: Exception) {
                closeResources()
                Result(-1, "", "执行中遇到未预期错误: ${e.message}", error = e)
            } finally {
                // 确保协程作业被彻底清理
                stdoutJob.cancel()
                stderrJob.cancel()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: SuExecutor? = null

        fun getInstance(): SuExecutor {
            return instance ?: synchronized(this) {
                instance ?: SuExecutor().also { instance = it }
            }
        }
    }
}