package com.xjyzs.operator.utils.execute

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import com.xjyzs.operator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

fun getDefaultBrowserPackage(context: Context): String? {
    val intent = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
    val packageManager = context.packageManager

    val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.resolveActivity(
            intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return resolveInfo?.activityInfo?.packageName
}

fun getDefaultLauncherPackage(context: Context): String? {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }

    val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.resolveActivity(
            intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

    return resolveInfo?.activityInfo?.packageName
}

val APP_PACKAGES = linkedMapOf<String, String>()
val PACKAGES_APP = linkedMapOf<String, String>()
val PRIORITY_MAP = linkedMapOf<String, String>()
val APP_PACKAGES_SPECIAL = linkedMapOf(
    "系统设置" to "com.android.settings",
    "AndroidSystemSettings" to "com.android.settings",
    "Android System Settings" to "com.android.settings",
    "Android  System Settings" to "com.android.settings",
    "Android-System-Settings" to "com.android.settings",
    "Settings" to "com.android.settings",
    "AudioRecorder" to "com.android.soundrecorder",
    "audiorecorder" to "com.android.soundrecorder",
    "Chrome" to "com.android.chrome",
    "chrome" to "com.android.chrome",
    "Google Chrome" to "com.android.chrome",
    "Files" to "com.android.fileexplorer",
    "files" to "com.android.fileexplorer",
    "File Manager" to "com.android.fileexplorer",
    "file manager" to "com.android.fileexplorer",
    "gmail" to "com.google.android.gm",
    "Gmail" to "com.google.android.gm",
    "GoogleMail" to "com.google.android.gm",
    "Google Mail" to "com.google.android.gm",
    "GoogleFiles" to "com.google.android.apps.nbu.files",
    "googlefiles" to "com.google.android.apps.nbu.files",
    "FilesbyGoogle" to "com.google.android.apps.nbu.files",
    "GoogleCalendar" to "com.google.android.calendar",
    "Google-Calendar" to "com.google.android.calendar",
    "Google Calendar" to "com.google.android.calendar",
    "google-calendar" to "com.google.android.calendar",
    "google calendar" to "com.google.android.calendar",
    "GoogleChat" to "com.google.android.apps.dynamite",
    "Google Chat" to "com.google.android.apps.dynamite",
    "Google-Chat" to "com.google.android.apps.dynamite",
    "GoogleClock" to "com.google.android.deskclock",
    "Google Clock" to "com.google.android.deskclock",
    "Google-Clock" to "com.google.android.deskclock",
    "GoogleContacts" to "com.google.android.contacts",
    "Google-Contacts" to "com.google.android.contacts",
    "Google Contacts" to "com.google.android.contacts",
    "google-contacts" to "com.google.android.contacts",
    "google contacts" to "com.google.android.contacts",
    "GoogleDocs" to "com.google.android.apps.docs.editors.docs",
    "Google Docs" to "com.google.android.apps.docs.editors.docs",
    "googledocs" to "com.google.android.apps.docs.editors.docs",
    "google docs" to "com.google.android.apps.docs.editors.docs",
    "Google Drive" to "com.google.android.apps.docs",
    "Google-Drive" to "com.google.android.apps.docs",
    "google drive" to "com.google.android.apps.docs",
    "google-drive" to "com.google.android.apps.docs",
    "GoogleDrive" to "com.google.android.apps.docs",
    "Googledrive" to "com.google.android.apps.docs",
    "googledrive" to "com.google.android.apps.docs",
    "GoogleFit" to "com.google.android.apps.fitness",
    "googlefit" to "com.google.android.apps.fitness",
    "GoogleKeep" to "com.google.android.keep",
    "googlekeep" to "com.google.android.keep",
    "GoogleMaps" to "com.google.android.apps.maps",
    "Google Maps" to "com.google.android.apps.maps",
    "googlemaps" to "com.google.android.apps.maps",
    "google maps" to "com.google.android.apps.maps",
    "Google Play Books" to "com.google.android.apps.books",
    "Google-Play-Books" to "com.google.android.apps.books",
    "google play books" to "com.google.android.apps.books",
    "google-play-books" to "com.google.android.apps.books",
    "GooglePlayBooks" to "com.google.android.apps.books",
    "googleplaybooks" to "com.google.android.apps.books",
    "GooglePlayStore" to "com.android.vending",
    "Google Play Store" to "com.android.vending",
    "Google-Play-Store" to "com.android.vending",
    "GoogleSlides" to "com.google.android.apps.docs.editors.slides",
    "Google Slides" to "com.google.android.apps.docs.editors.slides",
    "Google-Slides" to "com.google.android.apps.docs.editors.slides",
    "GoogleTasks" to "com.google.android.apps.tasks",
    "Google Tasks" to "com.google.android.apps.tasks",
    "Google-Tasks" to "com.google.android.apps.tasks",
    "Twitter" to "com.twitter.android",
    "twitter" to "com.twitter.android",
    "X" to "com.twitter.android",
    "WeChat" to "com.tencent.mm",
)

fun getPackageName(appName: String): String? {
    return APP_PACKAGES[appName.lowercase(Locale.US)]
}

fun getAppName(packageName: String): String {
    return PRIORITY_MAP[packageName] ?: PACKAGES_APP[packageName] ?: packageName
}

fun updatePriorityMapping(packageName: String, appName: String) {
    PRIORITY_MAP[packageName] = appName
    injectPriorityIntoAppPackages(packageName, appName)
}

private fun injectPriorityIntoAppPackages(packageName: String, priorityName: String) {
    APP_PACKAGES[priorityName] = packageName
    val lowercasedLabel = priorityName.lowercase(Locale.US)
    if (lowercasedLabel != priorityName) {
        APP_PACKAGES[lowercasedLabel] = packageName
    }
}

suspend fun init(context: Context){
    val pm = context.packageManager
    val apps = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(
                    0L
                )
            )
        } else {
            pm.getInstalledApplications(0)
        }
    }
    APP_PACKAGES_SPECIAL.forEach { (appName, packageName) ->
        PACKAGES_APP[packageName] = appName
        APP_PACKAGES[appName] = packageName
        val lowercased = appName.lowercase(Locale.US)
        if (lowercased != appName) {
            APP_PACKAGES[lowercased] = packageName
        }
    }
    if (apps.size < 5) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_ALL
        )
        for (i in apps) {
            val label = i.loadLabel(context.packageManager).toString()
            APP_PACKAGES[label] = i.activityInfo.packageName
            val lowercasedLabel = label.lowercase()
            if (lowercasedLabel != label) {
                APP_PACKAGES[lowercasedLabel] = i.activityInfo.packageName
            }
            for (i in APP_PACKAGES) {
                PACKAGES_APP[i.value.lowercase(Locale.US)] = i.key
            }
        }
    } else {
        for (appInfo in apps) {
            val packageName = appInfo.packageName
            val systemLabel = appInfo.loadLabel(pm).toString()
            PACKAGES_APP[packageName] = systemLabel
            APP_PACKAGES[systemLabel] = packageName
            val lowercasedLabel = systemLabel.lowercase(Locale.US)
            if (lowercasedLabel != systemLabel) {
                APP_PACKAGES[lowercasedLabel] = packageName
            }
        }
    }
    val browserPkg = getDefaultBrowserPackage(context) ?: "com.android.browser"
    updatePriorityMapping(browserPkg, context.getString(R.string.system_browser))
    updatePriorityMapping(browserPkg, context.getString(R.string.browser))
}