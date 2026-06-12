// src/main/java/com/xjyzs/operator/remote/VirtualDisplayController.kt
package com.xjyzs.operator.remote

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ServiceManager
import android.view.Surface
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class VirtualDisplayController {

    companion object {
        private val counter = AtomicInteger(0)
    }

    // ─── 维持全局唯一虚拟屏状态 ───────────────────────────────────────────────
    private var currentDisplayId: Int = -1
    // 【核心修复】：改为 Any? 以便存放你生成的 Proxy 代理对象
    private var currentToken: Any? = null
    private var currentW: Int = 0
    private var currentH: Int = 0
    private var currentDpi: Int = 0

    // ─── Flags 常量 ────────────────────────────────────────────────────────
    private val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    @Synchronized
    fun create(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        try {
            // 1. 如果分辨率一致且已存在，直接热更新 Surface，保持 displayId 不变
            if (currentDisplayId != -1 && currentW == width && currentH == height && currentDpi == dpi) {
                setSurface(currentDisplayId, surface)
                return currentDisplayId
            }

            // 2. 如果参数变了，先释放旧的
            if (currentDisplayId != -1) release(currentDisplayId)

            val idm = requireIDisplayManager()
            val token = Binder()
            val name = "OperatorVirtualDisplay"

            // 动态代理 IVirtualDisplayCallback 接口
            val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val callbackProxy = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> token
                    "toString" -> "IVirtualDisplayCallbackProxy{token=$token}"
                    "hashCode" -> token.hashCode()
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> {
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> false
                            Int::class.javaPrimitiveType -> 0
                            Long::class.javaPrimitiveType -> 0L
                            Float::class.javaPrimitiveType -> 0f
                            Double::class.javaPrimitiveType -> 0.0
                            else -> null
                        }
                    }
                }
            }

            var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
                }
            }

            val displayId = if (Build.VERSION.SDK_INT >= 31) {
                createApi31(idm, callbackProxy, surface, width, height, dpi, flags, name)
            } else {
                createLegacy(idm, callbackProxy, surface, width, height, dpi, flags, name)
            }

            if (displayId > 0) {
                // 【核心修复】：在这里保存状态！！这样后续 setSurface 就能拿到 token 了
                currentDisplayId = displayId
                currentToken = callbackProxy
                currentW = width
                currentH = height
                currentDpi = dpi
            }
            return displayId
        } catch (e: Exception) {
            return -1
        }
    }

    @Synchronized
    fun setSurface(displayId: Int, surface: Surface) {
        if (displayId != currentDisplayId || currentToken == null) {
            return
        }
        try {
            val idm = requireIDisplayManager()
            val method = idm.javaClass.methods.firstOrNull { it.name == "setVirtualDisplaySurface" }

            if (method != null) {
                method.isAccessible = true
                // 此时 currentToken 就是你造的 Proxy，直接传进去完美符合系统要求！
                method.invoke(idm, currentToken, surface)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun release(displayId: Int) {
        if (displayId != currentDisplayId) return
        val token = currentToken ?: return
        try {
            val idm = requireIDisplayManager()
            val method = idm.javaClass.methods.firstOrNull { it.name == "releaseVirtualDisplay" }
            method?.isAccessible = true
            method?.invoke(idm, token)
        } catch (_: Exception) {
        } finally {
            currentDisplayId = -1
            currentToken = null
        }
    }

    @Synchronized
    fun releaseAll() {
        if (currentDisplayId != -1) release(currentDisplayId)
    }

    private fun requireIDisplayManager(): Any {
        val binder = ServiceManager.getService("display")
            ?: throw IllegalStateException("display 服务不可用")
        return Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: throw IllegalStateException("IDisplayManager.asInterface 返回 null")
    }

    private fun createLegacy(
        idm: Any, callbackProxy: Any, surface: Surface,
        w: Int, h: Int, dpi: Int, flags: Int, name: String
    ): Int {
        val method = idm.javaClass.methods
            .filter { it.name == "createVirtualDisplay" }
            .maxByOrNull { it.parameterCount }
            ?: throw NoSuchMethodException("createVirtualDisplay not found")

        method.isAccessible = true
        return when (method.parameterCount) {
            10 -> method.invoke(idm, callbackProxy, null, "com.android.shell", name, w, h, dpi, surface, flags, null)
            9 -> method.invoke(idm, callbackProxy, null, "com.android.shell", name, w, h, dpi, surface, flags)
            else -> throw Exception("未知参数数量: ${method.parameterCount}")
        } as Int
    }

    private fun createApi31(
        idm: Any, callbackProxy: Any, surface: Surface,
        w: Int, h: Int, dpi: Int, flags: Int, name: String
    ): Int {
        val config = buildVirtualDisplayConfig(name, w, h, dpi, surface, flags)
        val method = idm.javaClass.methods
            .firstOrNull { m -> m.name == "createVirtualDisplay" && m.parameterTypes.firstOrNull()?.simpleName == "VirtualDisplayConfig" }
            ?: throw NoSuchMethodException("createVirtualDisplay(VirtualDisplayConfig) 未找到")

        method.isAccessible = true
        return method.invoke(idm, config, callbackProxy, null, "com.android.shell") as Int
    }

    @SuppressLint("BlockedPrivateApi")
    private fun buildVirtualDisplayConfig(
        name: String, w: Int, h: Int, dpi: Int, surface: Surface, flags: Int
    ): Any {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass.getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(name, w, h, dpi)

        runCatching {
            builderClass.getDeclaredMethod("setSurface", Surface::class.java)
                .apply { isAccessible = true }.invoke(builder, surface)
        }
        runCatching {
            builderClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(builder, flags)
        }
        return builderClass.getMethod("build").invoke(builder)
    }
}