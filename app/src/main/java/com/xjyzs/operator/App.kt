package com.xjyzs.operator

import android.app.Application
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.SharedState

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SharedState.init(this)
        DisplayMetricsStore.init(this)
    }
}
