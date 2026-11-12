package com.xjyzs.operator.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String, val packageName: String, val icon: Drawable
)

fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)

    return resolveInfos.map { resolveInfo ->
        AppInfo(
            name = resolveInfo.loadLabel(pm).toString(),
            packageName = resolveInfo.activityInfo.packageName,
            icon = resolveInfo.loadIcon(pm)
        )
    }.distinctBy { it.packageName }.sortedBy { it.name }
}
