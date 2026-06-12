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

    // 后台常驻读取线程，将行数据泵入队列，彻底解决 readLine() 阻塞协程的问题
    private var stdoutPumpThread: Thread? = null
    private var stderrPumpThread: Thread? = null
    private val stdoutQueue = LinkedBlockingQueue<String>()
    private val stderrQueue = LinkedBlockingQueue<String>()

    // 哨兵值：表示流已到达 EOF（进程退出）
    private val EOF_SENTINEL = "\u0000EOF\u0000"

    /**
     * 启动 su 进程，并为 stdout/stderr 各启动一个后台泵线程。
     * 泵线程持续 readLine()，将每一行放入对应的 BlockingQueue。
     * 这样协程侧只需 poll()，永远不会被 readLine() 阻塞。
     */
    private fun startShell() {
        closeResources()

        val proc = try {
            ProcessBuilder("su").start()
        } catch (e: Exception) {
            throw IllegalStateException("无法启动 su 进程，设备可能未 Root 或拒绝了权限", e)
        }

        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
        stderrReader = BufferedReader(InputStreamReader(proc.errorStream))

        // 清空可能残留的旧数据
        stdoutQueue.clear()
        stderrQueue.clear()

        // 启动 stdout 泵
        stdoutPumpThread = Thread({
            try {
                val reader = stdoutReader ?: return@Thread
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break // null = EOF
                    stdoutQueue.put(line)
                }
            } catch (_: Exception) {
                // 流关闭时正常退出
            } finally {
                stdoutQueue.put(EOF_SENTINEL)
            }
        }, "su-stdout-pump").also { it.isDaemon = true; it.start() }

        // 启动 stderr 泵
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
        // 中断泵线程（readLine 在流关闭后会自动返回 null，interrupt 只是加速）
        runCatching { stdoutPumpThread?.interrupt() }
        runCatching { stderrPumpThread?.interrupt() }
        stdoutPumpThread = null
        stderrPumpThread = null

        // 关闭流会让泵线程的 readLine() 抛出 IOException，从而退出循环
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
     * 从队列中逐行消费，直到遇到 [boundary] 行或 EOF 哨兵。
     *
     * @param queue       目标队列
     * @param boundary    本次命令专属的结束标记（完整匹配，避免误判）
     * @param timeoutMs   最长等待时间
     * @return Pair(是否正常结束, 收集到的所有行)
     */
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

            // poll() 最多等待 remaining ms，不会永久阻塞
            val line = queue.poll(minOf(remaining, 50L), TimeUnit.MILLISECONDS)
                ?: continue  // 超时但未到 deadline，继续轮询

            if (line == EOF_SENTINEL) {
                // 把哨兵放回，让外层感知到进程已死
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

    /**
     * 执行一条 Shell 命令。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒），默认 10 秒
     */
    suspend fun execute(command: String, timeoutMs: Long = 10_000L): Result = mutex.withLock {
        // 1. 按需重建 Shell
        if (!isProcessAlive()) {
            try {
                startShell()
            } catch (e: Exception) {
                return@withLock Result(-1, "", "", error = e)
            }
        }

        val currentWriter = writer
            ?: return@withLock Result(-1, "", "", error = IllegalStateException("Writer 为空"))

        // 每次执行生成全局唯一的边界标记，完整行匹配，彻底杜绝误判
        val uuid = UUID.randomUUID().toString().replace("-", "").take(12)
        val stdoutBoundary = "__SU_STDOUT_${uuid}__"
        val stderrBoundary = "__SU_STDERR_${uuid}__"
        // 退出码通过独立的已知前缀行传递，不混在边界行里
        val exitCodeBoundary = "__SU_EXIT_${uuid}__"

        // 2. 向 Shell 写入命令序列
        try {
            // 执行用户命令
            currentWriter.write("$command\n")
            // 立即保存退出码（后续 echo 会覆盖 $?）
            currentWriter.write("__ec__=\$?\n")
            // 发出 stdout 边界（含退出码）
            currentWriter.write("echo '$exitCodeBoundary'\" \$__ec__\"\n")
            currentWriter.write("echo '$stdoutBoundary'\n")
            // 发出 stderr 边界（重定向到 stderr）
            currentWriter.write("echo '$stderrBoundary' >&2\n")
            currentWriter.flush()
        } catch (e: Exception) {
            closeResources()
            return@withLock Result(-1, "", "", error = e)
        }

        // 3. 并发读取 stdout / stderr，共享同一个超时窗口
        return@withLock try {
            withTimeout(timeoutMs) {
                // stdout 和 stderr 并发消费，互不阻塞
                val stdoutDeferred = async {
                    // 先消费到 exitCode 行，再消费到 boundary 行
                    // 这样 exitCode 行出现在 boundary 行之前，顺序有保证
                    drainUntilBoundary(stdoutQueue, stdoutBoundary, timeoutMs)
                }
                val stderrDeferred = async {
                    drainUntilBoundary(stderrQueue, stderrBoundary, timeoutMs)
                }

                val (stdoutOk, stdoutLines) = stdoutDeferred.await()
                val (stderrOk, stderrLines) = stderrDeferred.await()

                // 从 stdoutLines 中提取退出码行（它在 boundary 之前、用户输出之后）
                val exitCodeLine = stdoutLines.lastOrNull { it.startsWith(exitCodeBoundary) }
                val exitCode = exitCodeLine
                    ?.removePrefix(exitCodeBoundary)
                    ?.trim()
                    ?.toIntOrNull() ?: -1

                // 过滤掉退出码行，得到纯用户输出
                val stdout = stdoutLines
                    .filter { !it.startsWith(exitCodeBoundary) }
                    .joinToString("\n")
                    .trimEnd('\n')

                val stderr = stderrLines
                    .joinToString("\n")
                    .trimEnd('\n')

                if (!stdoutOk || !stderrOk) {
                    // 边界未找到（进程意外退出），重置 Shell
                    closeResources()
                }

                Result(exitCode, stdout, stderr)
            }
        } catch (e: TimeoutCancellationException) {
            // 超时：杀掉进程（这会让泵线程的 readLine 返回 null 并退出），避免线程泄漏
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
            Result(-1, "", "执行中遇到未预期错误: ${e.message}", error = e)
        }
    }

    /**
     * 主动关闭 Shell，释放所有资源。
     * 通常不需要手动调用；进程死亡时会在下次 execute() 时自动重建。
     */
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