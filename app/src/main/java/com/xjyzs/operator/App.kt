package com.xjyzs.operator

import android.app.Application
import android.content.Context
import com.xjyzs.operator.utils.CpuFreq
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.ChatRepository
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.execute.init
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SharedState.init(this)
        DisplayMetricsStore.init(this)
        CoroutineScope(Dispatchers.IO).launch { ChatRepository.init(this@App) }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        applicationScope.launch {
            init(this@App)
            CpuFreq.init()
        }
    }
}
