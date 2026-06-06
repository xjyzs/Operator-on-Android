package com.xjyzs.operator.utils

import android.util.Log
import android.view.View
import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xjyzs.operator.FloatingWindowService
import com.xjyzs.operator.Msg
import com.xjyzs.operator.SharedState
import kotlinx.coroutines.delay

suspend fun buildUserJson(str: String = "", mFloatingView: View): Msg {
    val usesVirtualDisplay = SharedState._usesVirtualDisplay.value
    val virtualDisplayId = if (usesVirtualDisplay) SharedState._virtualDisplayId.value else null
    
    return Msg("user", mutableStateOf(JsonArray().apply {
        val currentApp = getCurrentApp(virtualDisplayId)
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
                "current app:${currentApp}\n" + FloatingWindowService.getLayout(virtualDisplayId) + "\n" + str
            )
            add(obj1)
            val obj2 = JsonObject()
            obj2.addProperty("type", "image_url")
            var cnt = 0
            while (true) {
                if (CpuFreq.getScalingCurFreq() / CpuFreq.scalingMaxFreq < 0.8) break
                delay(200)
                cnt++
                if (cnt > 50) break
            }
            val subObj =
                JsonObject().apply { addProperty("url", Screenshot.screenshot(mFloatingView, virtualDisplayId)) }
            obj2.add("image_url", subObj)
            add(obj2)
        }
    }))
}