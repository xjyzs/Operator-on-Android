package com.xjyzs.operator.utils

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.DataOutputStream

object InputControlUtils {

    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var writer: DataOutputStream? = null
    private var appContext: Context? = null
    private var thread: Thread? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        startServer()
    }

    private fun startServer() {
        thread = Thread {
            try {
                serverSocket = LocalServerSocket("touch_injector")
                while (true) {
                    val socket = serverSocket!!.accept()
                    clientSocket = socket
                    writer = DataOutputStream(socket.outputStream)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { isDaemon = true; start() }

        Thread {
            Thread.sleep(500)
            try {
                val ctx = appContext ?: return@Thread
                val apkPath = ctx.packageCodePath
                val cmd = "app_process -Djava.class.path=$apkPath /system/bin com.xjyzs.operator.utils.TouchInjector"
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    @Synchronized
    private fun writeCommand(action: Int, x: Int, y: Int, displayId: Int) {
        try {
            writer?.let {
                it.writeByte(action)
                it.writeShort(x)
                it.writeShort(y)
                it.writeShort(displayId)
                it.flush()
            }
        } catch (e: Exception) {
            writer = null
            clientSocket = null
        }
    }

    fun downSync(x: Int, y: Int, displayId: Int) {
        writeCommand(0, x, y, displayId)
    }

    fun moveSync(x: Int, y: Int, displayId: Int) {
        writeCommand(1, x, y, displayId)
    }

    fun upSync(x: Int, y: Int, displayId: Int) {
        writeCommand(2, x, y, displayId)
    }

    fun destroy() {
        try {
            writeCommand(3, 0, 0, 0)
            serverSocket?.close()
            clientSocket?.close()
        } catch (ignored: Exception) {
        } finally {
            serverSocket = null
            clientSocket = null
            writer = null
        }
    }
}
