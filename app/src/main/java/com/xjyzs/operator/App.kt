package com.xjyzs.operator

import android.app.Application
import com.xjyzs.operator.utils.data.ChatRepository
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.SharedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SharedState.init(this)
        DisplayMetricsStore.init(this)
        CoroutineScope(Dispatchers.IO).launch { ChatRepository.init(this@App) }
    }
}
