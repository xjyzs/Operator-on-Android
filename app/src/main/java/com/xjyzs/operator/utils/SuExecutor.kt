package com.xjyzs.operator.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

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
    private var stdoutPumpThread: Thread? = null
    private var stderrPumpThread: Thread? = null
    private val stdoutQueue = LinkedBlockingQueue<String>()
    private val stderrQueue = LinkedBlockingQueue<String>()
    private val EOF_SENTINEL = "\u0000EOF\u0000"
    private fun startShell() {
        closeResources()

        val proc = try {
            ProcessBuilder("su").start()
        } catch (e: Exception) {
            throw IllegalStateException("未授予 Root 权限", e)
        }

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
                    stdoutQueue.put(line)
                }
            } catch (_: Exception) {
            } finally {
                stdoutQueue.put(EOF_SENTINEL)
            }
        }, "su-stdout-pump").also { it.isDaemon = true; it.start() }
        stderrPumpThread = Thread({
            try {
                val reader = stderrReader ?: return@Thread
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    stderrQueue.put(line)
                }
            } catch (_: Exception) {
            } finally {
                stderrQueue.put(EOF_SENTINEL)
            }
        }, "su-stderr-pump").also { it.isDaemon = true; it.start() }
    }

    private fun isProcessAlive(): Boolean {
        val proc = process ?: return false
        return try { proc.exitValue(); false } catch (_: IllegalThreadStateException) { true }
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
        private var instance: SuExecutor? = null

        fun getInstance(): SuExecutor =
            instance ?: synchronized(this) {
                instance ?: SuExecutor().also { instance = it }
            }
    }
}