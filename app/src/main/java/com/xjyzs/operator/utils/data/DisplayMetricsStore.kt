package com.xjyzs.operator.utils.data

import android.content.Context

object DisplayMetricsStore {
    private lateinit var appContext: Context
    var density: Float = 0f
        private set
    var densityDpi: Int = 0
        private set
    var vdWidth = 0
    var vdHeight = 0

    fun init(context: Context) {
        this.appContext = context.applicationContext
        density = appContext.resources.displayMetrics.density
        densityDpi = appContext.resources.displayMetrics.densityDpi
    }

    val width: Int
        get() = if (SharedState._usesVirtualDisplay.value && vdWidth != 0) vdWidth
        else appContext.resources.displayMetrics.widthPixels

    val height: Int
        get() = if (SharedState._usesVirtualDisplay.value && vdHeight != 0) vdHeight
        else appContext.resources.displayMetrics.heightPixels

    val currentWidth: Int get()=appContext.resources.displayMetrics.widthPixels

    val currentHeight: Int get()=appContext.resources.displayMetrics.heightPixels
}
