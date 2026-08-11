package com.xjyzs.operator.utils

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

enum class ShellType { ROOT, SHIZUKU }

class ShellExecutor private constructor() {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isTimeout: Boolean = false,
        val error: Throwable? = null
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && error == null
    }

    @Volatile
    private var shellType: ShellType = ShellType.ROOT

    fun setShellType(type: ShellType) {
        if (shellType != type) {
            closeResources()
            shellType = type
        }
    }

    fun getShellType(): ShellType = shellType

    private val mutex = Mutex()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutReader: BufferedReader? = null
    private var stderrReader: BufferedReader? = null
    private var stdoutPumpThread: Thread? = null
    private var stderrPumpThread: Thread? = null
    private val stdoutQueue = LinkedBlockingQueue<String>()
    private val stderrQueue = LinkedBlockingQueue<String>()
    private val EOF_SENTINEL = "\u0000EOF\u0000"

    private fun createShellProcess(): Process = when (shellType) {
        ShellType.ROOT -> {
            try {
                ProcessBuilder("su").start()
            } catch (e: Exception) {
                throw IllegalStateException("未授予 Root 权限", e)
            }
        }
        ShellType.SHIZUKU -> createShizukuProcess()
    }

    private fun createShizukuProcess(): Process {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku 服务未运行")
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            throw IllegalStateException("Shizuku 状态检查失败: ${e.message}", e)
        }
        if (!granted) {
            throw IllegalStateException("Shizuku 未授权")
        }
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku Binder 为空")
        val service = IShizukuService.Stub.asInterface(binder)
        val remote: IRemoteProcess = try {
            service.newProcess(arrayOf("sh"), null, null)
        } catch (e: SecurityException) {
            throw IllegalStateException("Shizuku 权限被拒绝", e)
        } catch (e: RemoteException) {
            throw IllegalStateException("Shizuku 通信失败: ${e.message}", e)
        }
        return ShizukuProcess(remote)
    }

    private class ShizukuProcess(private val remote: IRemoteProcess) : Process() {
        private val pfdOut: ParcelFileDescriptor? = runCatching { remote.outputStream }.getOrNull()
        private val pfdIn: ParcelFileDescriptor? = runCatching { remote.inputStream }.getOrNull()
        private val pfdErr: ParcelFileDescriptor? = runCatching { remote.errorStream }.getOrNull()

        private val _outputStream: OutputStream? =
            pfdOut?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        private val _inputStream: InputStream? =
            pfdIn?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        private val _errorStream: InputStream? =
            pfdErr?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

        override fun getOutputStream(): OutputStream =
            _outputStream ?: throw IllegalStateException("Shizuku 输出流不可用")
        override fun getInputStream(): InputStream =
            _inputStream ?: throw IllegalStateException("Shizuku 输入流不可用")
        override fun getErrorStream(): InputStream =
            _errorStream ?: throw IllegalStateException("Shizuku 错误流不可用")

        override fun waitFor(): Int =
            try { remote.waitFor() } catch (e: RemoteException) { throw RuntimeException(e) }

        override fun exitValue(): Int {
            val alive = try { remote.alive() } catch (e: RemoteException) { false }
            if (alive) throw IllegalThreadStateException("process is still alive")
            return try { remote.waitFor() } catch (e: RemoteException) { throw RuntimeException(e) }
        }

        override fun destroy() {
            runCatching { remote.destroy() }
        }
    }

    private fun startShell() {
        closeResources()

        val proc = createShellProcess()

        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
        stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
        stdoutQueue.clear()
        stderrQueue.clear()

        stdoutPumpThread = Thread({
            try {
                val reader = stdoutReader ?: return@Thread
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break // null = EOF
                    stdoutQueue.offer(line)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { stdoutQueue.offer(EOF_SENTINEL) }
            }
        }, "shell-stdout-pump").also { it.isDaemon = true; it.start() }

        stderrPumpThread = Thread({
            try {
                val reader = stderrReader ?: return@Thread
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    stderrQueue.offer(line)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { stderrQueue.offer(EOF_SENTINEL) }
            }
        }, "shell-stderr-pump").also { it.isDaemon = true; it.start() }
    }

    private fun isProcessAlive(): Boolean {
        val proc = process ?: return false
        return try {
            proc.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun closeResources() {
        runCatching { stdoutPumpThread?.interrupt() }
        runCatching { stderrPumpThread?.interrupt() }
        stdoutPumpThread = null
        stderrPumpThread = null
        runCatching { writer?.close() }
        runCatching { stdoutReader?.close() }
        runCatching { stderrReader?.close() }
        runCatching { process?.destroy() }

        writer = null
        stdoutReader = null
        stderrReader = null
        process = null
    }
    private suspend fun drainUntilBoundary(
        queue: LinkedBlockingQueue<String>,
        boundary: String,
        timeoutMs: Long
    ): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        var foundBoundary = false

        while (isActive) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val line = queue.poll(minOf(remaining, 50L), TimeUnit.MILLISECONDS)
                ?: continue
            if (line == EOF_SENTINEL) {
                queue.put(EOF_SENTINEL)
                break
            }
            if (line == boundary) {
                foundBoundary = true
                break
            }
            lines.add(line)
        }
        Pair(foundBoundary, lines)
    }

    suspend fun execute(command: String, timeoutMs: Long = 10_000L): Result = mutex.withLock {
        if (!isProcessAlive()) {
            try {
                startShell()
            } catch (e: Exception) {
                return@withLock Result(-1, "", "", error = e)
            }
        }

        val currentWriter = writer
            ?: return@withLock Result(-1, "", "", error = IllegalStateException("Writer 为空"))
        val uuid = UUID.randomUUID().toString().replace("-", "").take(12)
        val stdoutBoundary = "__SU_STDOUT_${uuid}__"
        val stderrBoundary = "__SU_STDERR_${uuid}__"
        val exitCodeBoundary = "__SU_EXIT_${uuid}__"
        try {
            currentWriter.write("$command\n")
            currentWriter.write("__ec__=\$?\n")
            currentWriter.write("echo '$exitCodeBoundary'\" \$__ec__\"\n")
            currentWriter.write("echo '$stdoutBoundary'\n")
            currentWriter.write("echo '$stderrBoundary' >&2\n")
            currentWriter.flush()
        } catch (e: Exception) {
            closeResources()
            return@withLock Result(-1, "", "", error = e)
        }

        return@withLock try {
            withTimeout(timeoutMs.milliseconds) {
                val stdoutDeferred = async {
                    drainUntilBoundary(stdoutQueue, stdoutBoundary, timeoutMs)
                }
                val stderrDeferred = async {
                    drainUntilBoundary(stderrQueue, stderrBoundary, timeoutMs)
                }
                val (stdoutOk, stdoutLines) = stdoutDeferred.await()
                val (stderrOk, stderrLines) = stderrDeferred.await()
                val exitCodeLine = stdoutLines.lastOrNull { it.startsWith(exitCodeBoundary) }
                val exitCode = exitCodeLine
                    ?.removePrefix(exitCodeBoundary)
                    ?.trim()
                    ?.toIntOrNull() ?: -1
                val stdout = stdoutLines
                    .filter { !it.startsWith(exitCodeBoundary) }
                    .joinToString("\n")
                    .trimEnd('\n')
                val stderr = stderrLines
                    .joinToString("\n")
                    .trimEnd('\n')

                if (!stdoutOk || !stderrOk) {
                    closeResources()
                }

                Result(exitCode, stdout, stderr)
            }
        } catch (e: TimeoutCancellationException) {
            closeResources()
            Result(
                exitCode = -1,
                stdout = "",
                stderr = "命令执行超时，后台 Shell 已重置 (>${timeoutMs}ms)",
                isTimeout = true,
                error = e
            )
        } catch (e: Exception) {
            closeResources()
            Result(-1, "", "未预期错误: ${e.message}", error = e)
        }
    }

    fun close() {
        closeResources()
    }

    companion object {
        @Volatile
        private var instance: ShellExecutor? = null

        fun getInstance(): ShellExecutor =
            instance ?: synchronized(this) {
                instance ?: ShellExecutor().also { instance = it }
            }
    }
}
