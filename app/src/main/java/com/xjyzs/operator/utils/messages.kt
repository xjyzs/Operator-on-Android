package com.xjyzs.operator.utils

import android.util.Log
import android.view.View
import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjyzs.operator.FloatingWindowService
import com.xjyzs.operator.Msg
import kotlinx.coroutines.delay

suspend fun buildUserJson(str: String = "", mFloatingView: View): Msg {
    return Msg("user", mutableStateOf(JsonArray().apply {
        val currentApp = getCurrentApp()
        val obj1 = JsonObject()
        obj1.addProperty("type", "text")
        if (currentApp == "Operator") {
            obj1.addProperty(
                "text", "current app: 系统桌面\n$str"
            )
            add(obj1)
        } else {
            obj1.addProperty(
                "text",
                "current app:${getCurrentApp()}\n" + FloatingWindowService.getLayout() + "\n" + str
            )
            add(obj1)
            val obj2 = JsonObject()
            obj2.addProperty("type", "image_url")
            var cnt = 0
            // 等待完成页面加载
            while (true) {
                Log.d("cpu_freq", CpuFreq.getScalingCurFreq().toString())
                if (CpuFreq.getScalingCurFreq() / CpuFreq.scalingMaxFreq < 0.8) break
                delay(200)
                cnt++
                if (cnt > 50) break
            }
            val subObj =
                JsonObject().apply { addProperty("url", Screenshot.screenshot(mFloatingView)) }
            obj2.add("image_url", subObj)
            add(obj2)
        }
    }))
}