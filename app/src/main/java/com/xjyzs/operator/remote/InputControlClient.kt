package com.xjyzs.operator.remote

import android.os.IBinder
import android.view.Surface
import com.xjyzs.operator.IInputControl  // 已有的 AIDL

class InputControlClient private constructor(
    private val service: IInputControl, binder: IBinder
) {
    companion object {
        fun connect(retries: Int = 50, delayMs: Long = 200): InputControlClient? {
            repeat(retries) { attempt ->
                val binder = BinderProvider.remoteBinder
                if (binder?.isBinderAlive == true) {
                    return try {
                        val service = IInputControl.Stub.asInterface(binder)
                        InputControlClient(service, binder)
                    } catch (e: Exception) {
                        null
                    }
                }
                Thread.sleep(delayMs)
            }
            return null
        }
    }

    @Volatile
    var isAlive = true
        private set

    private val deathRecipient = IBinder.DeathRecipient {
        isAlive = false
    }

    init {
        binder.linkToDeath(deathRecipient, 0)
    }

    fun downSync(x: Int, y: Int, displayId: Int) =
        runCatching { service.downSync(x, y, displayId) }

    fun moveSync(x: Int, y: Int, displayId: Int) =
        runCatching { service.moveSync(x, y, displayId) }

    fun upSync(x: Int, y: Int, displayId: Int) =
        runCatching { service.upSync(x, y, displayId) }

    /**
     * 创建虚拟屏，成功返回 displayId（≥1），失败返回 -1。
     * Surface 通过 Binder 跨进程传递，remote 进程负责以此创建 VirtualDisplay。
     */
    fun createVirtualDisplay(
        surface: Surface, width: Int, height: Int, densityDpi: Int
    ): Int = runCatching {
        service.createVirtualDisplay(surface, width, height, densityDpi)
    }.getOrDefault(-1)

    fun setVirtualDisplaySurface(displayId: Int, surface: Surface) =
        runCatching { service.setVirtualDisplaySurface(displayId, surface) }

    fun releaseVirtualDisplay(displayId: Int) =
        runCatching { service.releaseVirtualDisplay(displayId) }

    fun moveAppToDisplay(packageName: String, displayId: Int) =
        runCatching { service.moveAppToDisplay(packageName, displayId) }

    fun setSize(width: Int, height: Int) {
        service.setSize(width, height)
    }

    fun captureScreen(
        displayId: Int, x1: Float = -1f, y1: Float = -1f, x2: Float = -1f, y2: Float = -1f
    ) = runCatching { service.captureScreen(displayId,x1,y1,x2,y2) }.getOrNull()

    fun destroy() {
        runCatching { service.exit() }
        isAlive = false
    }
}