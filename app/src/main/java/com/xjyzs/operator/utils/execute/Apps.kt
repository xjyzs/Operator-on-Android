package com.xjyzs.operator.utils.execute

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.net.toUri
import com.xjyzs.operator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

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

private val APP_PACKAGES = linkedMapOf<String, String>()
private val PACKAGES_APP = linkedMapOf<String, String>()
private val PRIORITY_MAP = linkedMapOf<String, String>()
private val appsInitMutex = Mutex()
@Volatile
private var appsInitialized = false

private val APP_PACKAGES_SPECIAL = linkedMapOf(
    "Settings" to "com.android.settings",
    "Android System Settings" to "com.android.settings",
    "系统设置" to "com.android.settings",
    "AudioRecorder" to "com.android.soundrecorder",
    "Google Chrome" to "com.android.chrome",
    "Gmail" to "com.google.android.gm",
    "Google Mail" to "com.google.android.gm",
    "Google Files" to "com.google.android.apps.nbu.files",
    "Files by Google" to "com.google.android.apps.nbu.files",
    "Google Calendar" to "com.google.android.calendar",
    "Google Chat" to "com.google.android.apps.dynamite",
    "Google Clock" to "com.google.android.deskclock",
    "Google Contacts" to "com.google.android.contacts",
    "Google Docs" to "com.google.android.apps.docs.editors.docs",
    "Google Drive" to "com.google.android.apps.docs",
    "Google Fit" to "com.google.android.apps.fitness",
    "Google Keep" to "com.google.android.keep",
    "Google Maps" to "com.google.android.apps.maps",
    "Google Play Books" to "com.google.android.apps.books",
    "Google Play Store" to "com.android.vending",
    "Google Slides" to "com.google.android.apps.docs.editors.slides",
    "Google Tasks" to "com.google.android.apps.tasks",
    "Twitter" to "com.twitter.android",
)

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
    return resolveInfo?.activityInfo?.packageName?.takeIf { it != "android" }
}

fun getPackageName(appName: String): String? {
    return APP_PACKAGES[normalizeKey(appName)]
}

fun getAppName(packageName: String): String {
    return PRIORITY_MAP[packageName] ?: PACKAGES_APP[packageName] ?: packageName
}

fun updatePriorityMapping(packageName: String, appName: String) {
    val normalizedName = normalizeKey(appName)
    PRIORITY_MAP.entries.removeAll { (pkg, name) ->
        pkg != packageName && normalizeKey(name) == normalizedName
    }
    PRIORITY_MAP[packageName] = appName
    registerAppPackage(appName, packageName)
}

suspend fun ensureAppsInit(context: Context) {
    if (appsInitialized) return
    appsInitMutex.withLock {
        if (!appsInitialized) {
            init(context)
            appsInitialized = true
        }
    }
}

suspend fun init(context: Context) {
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val isEnglish = isEnglishLocale(context)

        APP_PACKAGES.clear()
        PACKAGES_APP.clear()

        APP_PACKAGES_SPECIAL.forEach { (appName, packageName) ->
            PACKAGES_APP.putIfAbsent(packageName, appName)
            registerAppPackage(appName, packageName)
        }

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }

        if (installedApps.size < 5) {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            }

            for (resolveInfo in launcherApps) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val packageName = activityInfo.packageName
                val systemLabel = resolveInfo.loadLabel(pm).toString()
                if (systemLabel.isNotBlank()) {
                    PACKAGES_APP[packageName] = systemLabel
                    registerAppPackage(systemLabel, packageName)
                }

                if (!isEnglish) {
                    val labelRes = when {
                        resolveInfo.nonLocalizedLabel != null -> 0
                        resolveInfo.labelRes != 0 -> resolveInfo.labelRes
                        activityInfo.nonLocalizedLabel != null -> 0
                        activityInfo.labelRes != 0 -> activityInfo.labelRes
                        activityInfo.applicationInfo.nonLocalizedLabel != null -> 0
                        else -> activityInfo.applicationInfo.labelRes
                    }
                    getEnglishLabel(context, pm, activityInfo.applicationInfo, labelRes)?.let {
                        registerAppPackage(it, packageName)
                    }
                }
            }
        } else {
            for (appInfo in installedApps) {
                val packageName = appInfo.packageName
                val systemLabel = appInfo.loadLabel(pm).toString()
                if (systemLabel.isNotBlank()) {
                    PACKAGES_APP[packageName] = systemLabel
                    registerAppPackage(systemLabel, packageName)
                }

                if (!isEnglish && appInfo.nonLocalizedLabel == null) {
                    getEnglishLabel(context, pm, appInfo, appInfo.labelRes)?.let {
                        registerAppPackage(it, packageName)
                    }
                }
            }
        }

        PRIORITY_MAP.forEach { (packageName, appName) ->
            registerAppPackage(appName, packageName)
        }

        val browserPkg = getDefaultBrowserPackage(context) ?: "com.android.browser"
        updatePriorityMapping(browserPkg, context.getString(R.string.system_browser))
        updatePriorityMapping(browserPkg, context.getString(R.string.browser))
        appsInitialized = true
    }
}

private fun registerAppPackage(appName: String, packageName: String) {
    val key = normalizeKey(appName)
    if (key.isNotEmpty()) {
        APP_PACKAGES[key] = packageName
    }
}

private fun normalizeKey(appName: String): String {
    return appName.trim()
        .lowercase(Locale.US)
        .replace(Regex("[\\s-]+"), "")
}

private fun isEnglishLocale(context: Context): Boolean {
    val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val locales = context.resources.configuration.locales
        if (!locales.isEmpty) locales[0] else Locale.getDefault()
    } else {
        context.resources.configuration.locale
    }
    return locale.language == Locale.ENGLISH.language
}

private fun getEnglishLabel(
    context: Context,
    pm: PackageManager,
    appInfo: ApplicationInfo,
    labelRes: Int
): String? {
    if (labelRes == 0) return null
    return try {
        val enConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.US)
        }
        val enResources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pm.getResourcesForApplication(appInfo, enConfig)
        } else {
            context.createPackageContext(appInfo.packageName, 0)
                .createConfigurationContext(enConfig)
                .resources
        }
        enResources.getString(labelRes).takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}