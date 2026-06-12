package com.xjyzs.operator.utils

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
    val virtualDisplayId =
        if (usesVirtualDisplay && InputControlUtils.displayId != -1) InputControlUtils.displayId else null

    return Msg("user", mutableStateOf(JsonArray().apply {
        val currentAppPackageName = getCurrentPkg(virtualDisplayId)
        val currentHomePackage = getDefaultLauncherPackage(context = mFloatingView.context)
        val currentApp = getAppName(currentAppPackageName)
        val obj1 = JsonObject()
        obj1.addProperty("type", "text")
        if (currentAppPackageName == "com.xjyzs.operator" || currentAppPackageName == currentHomePackage) {
            obj1.addProperty(
                "text",
                (if (str.isNotEmpty()) "<user_task>\n$str\n</user_task>\n" else "") + "<screen_layout>\ncurrent app:系统桌面\n</screen_layout>"
            )
            add(obj1)
        } else {
            obj1.addProperty(
                "text",
                (if (str.isNotEmpty()) "<user_task>\n$str\n</user_task>\n" else "") + "<screen_layout>\ncurrent app:${currentApp}\n" + FloatingWindowService.getLayout(
                    virtualDisplayId
                ) + "</screen_layout>"
            )
            add(obj1)
            val obj2 = JsonObject()
            obj2.addProperty("type", "image_url")
            var cnt = 0
            while (true) {
                val curFreq = CpuFreq.getScalingCurFreq()
                val maxFreq = CpuFreq.scalingMaxFreq
                if (curFreq <= 0 || maxFreq <= 0) break
                if (curFreq.toFloat() / maxFreq < 0.8f) break
                delay(200)
                cnt++
                if (cnt > 50) break
            }
            val subObj = JsonObject().apply {
                addProperty(
                    "url", screenshot(mFloatingView, virtualDisplayId)
                )
            }
            obj2.add("image_url", subObj)
            add(obj2)
        }
    }))
}