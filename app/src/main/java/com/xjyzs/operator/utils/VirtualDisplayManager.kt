package com.xjyzs.operator.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import com.xjyzs.operator.SharedState

object VirtualDisplayManager {
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var displayId: Int? = null

    // 虚拟屏尺寸信息，供外部模块（如无障碍布局坐标计算）使用
    private var vdWidth: Int = 0
    private var vdHeight: Int = 0
    private var vdDensityDpi: Int = 0

    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT: Int = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    fun getDisplayId(): Int? = displayId
    fun getWidth(): Int = vdWidth
    fun getHeight(): Int = vdHeight
    fun getDensityDpi(): Int = vdDensityDpi

    fun getVirtualDisplay(): VirtualDisplay? = virtualDisplay

    fun setVirtualDisplay(vd: VirtualDisplay) {
        virtualDisplay = vd
        displayId = vd.display.displayId
        SharedState._virtualDisplayId.value = displayId
    }

    fun isCreated(): Boolean = virtualDisplay != null

    fun create(context: Context, width: Int, height: Int, densityDpi: Int, surface: Surface): Int {
        release()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // 保存虚拟屏尺寸信息
        vdWidth = width
        vdHeight = height
        vdDensityDpi = densityDpi

        // ==================== 1. 组合标志位，使其支持独立焦点 ====================
        var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                or VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                        or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
            }
        }
        val vd = displayManager.createVirtualDisplay(
            "OperatorVirtualDisplay",
            width,
            height,
            densityDpi,
            surface,
            // 组合说明：
            // 1: PUBLIC
            // 256: SUPPORTS_TOUCH (支持触控输入)
            // 512: SHOULD_SHOW_SYSTEM_DECORATIONS (支持系统装饰/键盘)
            // 1024: TRUSTED (受信屏幕，独立焦点的前提条件)
            flags,
        )
        virtualDisplay = vd
        this.surface = surface

        val id = vd.display.displayId
        displayId = id
        SharedState._virtualDisplayId.value = displayId
        return displayId!!
    }

    fun setSurface(surface: Surface) {
        this.surface = surface
        virtualDisplay?.surface = surface
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        surface?.release()
        surface = null
        displayId = null
        vdWidth = 0
        vdHeight = 0
        vdDensityDpi = 0
        SharedState._virtualDisplayId.value = null
    }
}
