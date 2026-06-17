package com.xjyzs.operator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xjyzs.operator.ui.theme.OperatorTheme
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.SuExecutor
import com.xjyzs.operator.utils.VirtualDisplayViewer
import com.xjyzs.operator.utils.lastX1
import com.xjyzs.operator.utils.lastX2
import com.xjyzs.operator.utils.lastY1
import com.xjyzs.operator.utils.lastY2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OperatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) { MainUI() }
            }
        }
    }
}

data class AppInfo(
    val name: String, val packageName: String, val icon: Drawable
)

@SuppressLint("BatteryLife")
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun MainUI() {
    val context = LocalContext.current
    var accessibilityDialog by remember { mutableStateOf(false) }
    val apiPref = context.getSharedPreferences("api", Context.MODE_PRIVATE)
    val imeLst = remember { mutableStateListOf<String>() }
    val pref = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    val historyLst = remember { mutableStateListOf<String>() }
    val saveHistory = {
        pref.edit { putString("history", Gson().toJson(historyLst)) }
    }
    val newMsg by SharedState.newMsg.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val executor = SuExecutor.getInstance()


    LaunchedEffect(newMsg) {
        if (newMsg.isEmpty()) {
            if (historyLst.isEmpty()) {
                val historyStr = pref.getString("history", "[]")!!
                for (i in JsonParser.parseString(historyStr).asJsonArray) {
                    historyLst.add(i.asString)
                }
            }
        } else {
            historyLst.remove(newMsg)
            historyLst.add(newMsg)
            saveHistory()
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val apps = getInstalledApps(context)
            withContext(Dispatchers.Main) {
                installedApps = apps
            }
        }
    }
    LaunchedEffect(Unit) {
        var hasRoot = false
        try {
            val executor = SuExecutor.getInstance()
            val result = executor.execute("")
            if (result.isSuccess) hasRoot = true
        } catch (_: Exception) {
        }
        val hasOverlay = Settings.canDrawOverlays(context)
        val hasBattery = isIgnoringBatteryOptimizations(context)

        val apiUrl = apiPref.getString("apiUrl", "") ?: ""

        if (!hasRoot || !hasOverlay || !hasBattery || apiUrl.isEmpty()) {
            context.startActivity(Intent(context, WelcomeActivity::class.java))
            (context as ComponentActivity).finish()
            return@LaunchedEffect
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ime list -a -s"))
            process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                val list = output.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                imeLst.clear()
                imeLst.addAll(list)
            }
            process.waitFor()
            executor.execute("ime enable com.android.adbkeyboard/.AdbIME")
            executor.execute("pm grant com.xjyzs.operator android.permission.GRANT_RUNTIME_PERMISSIONS")
            executor.execute("pm grant com.xjyzs.operator android.permission.CAPTURE_VIDEO_OUTPUT")
            executor.execute("pm grant com.xjyzs.operator android.permission.CAPTURE_SECURE_VIDEO_OUTPUT")
            executor.execute("pm grant com.xjyzs.operator android.permission.ADD_TRUSTED_DISPLAY")
            executor.execute("pm grant com.xjyzs.operator android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY")
        } catch (_: Exception) {
        }
        try {
            val targetService = "com.xjyzs.operator/.FloatingWindowService"
            val currentServices = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val servicesList = currentServices.split(":").filter { it.isNotEmpty() }.toMutableList()
            if (!servicesList.contains(targetService)) {
                servicesList.add(targetService)
                val newServices = servicesList.joinToString(":")
                try {
                    executor.execute("settings put secure enabled_accessibility_services \"$newServices\"")
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        withContext(Dispatchers.IO){
        InputControlUtils.init(context)}
        while (true) {
            delay(1000)
            if (FloatingWindowService.isRunning) {
                accessibilityDialog = false
                break
            }
            accessibilityDialog = true
        }
    }
    if (accessibilityDialog) {
        AlertDialog(
            {},
            confirmButton = {
                TextButton({
                    val intent =
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.confirm)) }
            },
            title = { Text(stringResource(R.string.accessibility_permission_request, stringResource(R.string.app_name))) },
            text = { Text(stringResource(R.string.accessibility_permission_text)) })
    }
    var cleanConfirmDialog by remember { mutableStateOf(false) }
    if (cleanConfirmDialog) {
        AlertDialog(
            { cleanConfirmDialog = false },
            {
                TextButton({
                    cleanConfirmDialog = false
                    historyLst.clear()
                    saveHistory()
                    lastX1=0f
                    lastY1=0f
                    lastX2=0f
                    lastY2=0f
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton({
                    cleanConfirmDialog = false
                }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = { Text(stringResource(R.string.clear_history_text)) })
    }
    var clearTokensDialog by remember { mutableStateOf(false) }
    if (clearTokensDialog) {
        AlertDialog(
            { clearTokensDialog = false },
            {
                TextButton({
                    clearTokensDialog = false
                    SharedState.clearTokens()
                    apiPref.edit {
                        putLong("promptTokens", 0)
                        putLong("cachedTokens", 0)
                        putLong("imageTokens", 0)
                        putLong("completionTokens", 0)
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton({
                    clearTokensDialog = false
                }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.clear_tokens_title)) },
            text = { Text(stringResource(R.string.clear_tokens_text)) })
    }

    if (showDialog) {
        AppSelectorDialog(
            apps = installedApps, onDismiss = { showDialog = false }, // 点击外部弹窗关闭
            onAppSelected = { app ->
                showDialog = false // 点击应用关闭弹窗
                InputControlUtils.moveAppToDisplay(
                    app.packageName, InputControlUtils.displayId
                )
            })
    }
    var showVirtualScreenPreview by remember { mutableStateOf(false) }
    if (showVirtualScreenPreview) {
        FullscreenVirtualScreen(onClose = {
            showVirtualScreenPreview = false
        })
    } else {
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(
                            {
                                val intent = Intent(context, ConfigActivity::class.java)
                                context.startActivity(intent)
                                FloatingWindowService.disableService()
                                (context as ComponentActivity).finish()
                            }, colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = 0.1f
                                )
                            )
                        ) { Icon(Icons.Default.Settings, null) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    scrollBehavior = scrollBehavior
                )
            },
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                Row {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(6.dp, 10.dp)
                            .weight(0.618f)
                    ) {
                        Text(
                            stringResource(R.string.control_section), fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp)
                        )
                        val usesVirtualScreen by SharedState.usesVirtualDisplay.collectAsStateWithLifecycle()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.virtual_screen))
                            Spacer(Modifier.weight(1f))
                            Switch(checked = usesVirtualScreen, onCheckedChange = {
                                SharedState._usesVirtualDisplay.value = it
                                apiPref.edit {
                                    putBoolean("usesVirtualDisplay", it)
                                }
                            })
                        }
                        if (usesVirtualScreen) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    {
                                        showVirtualScreenPreview = true
                                    }) {
                                    Text(stringResource(R.string.view_virtual_screen), fontSize = 16.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    { showDialog = true }) {
                                    Text(stringResource(R.string.launch_app), fontSize = 16.sp)
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.input_method_section),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            LazyColumn(Modifier.height(100.dp)) {
                                itemsIndexed(imeLst) { _, ime ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                Runtime.getRuntime()
                                                    .exec(arrayOf("su", "-c", "ime set $ime"))
                                            }
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                            .padding(12.dp, 10.dp)) {
                                        Text(ime.substringAfterLast("."), fontSize = 16.sp)
                                    }
                                    Spacer(Modifier.size(6.dp))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                {
                                    val msgs = SharedState.msgs
                                    if (msgs.size > 1) msgs.removeRange(1, msgs.size)
                                }) {
                                Text(stringResource(R.string.clear_current_session), fontSize = 16.sp)
                            }
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(12.dp, 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.tokens_label),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                { clearTokensDialog = true },
                                modifier = Modifier.padding(end = 10.dp)
                            ) {
                                Text(stringResource(R.string.clear), fontSize = 16.sp)
                            }
                        }
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.completion_label),
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                                val completionTokens by SharedState.completionTokens.collectAsStateWithLifecycle()
                                Text(
                                    completionTokens.toString(),
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.input_label),
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                                val promptTokens by SharedState.promptTokens.collectAsStateWithLifecycle()
                                Text(
                                    promptTokens.toString(),
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                        }
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.image_label),
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                                val imageTokens by SharedState.imageTokens.collectAsStateWithLifecycle()
                                Text(
                                    imageTokens.toString(),
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.cache_label),
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                                val cachedTokens by SharedState.cachedTokens.collectAsStateWithLifecycle()
                                Text(
                                    cachedTokens.toString(),
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(start = 10.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.history_section), fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
                    TextButton(
                        { cleanConfirmDialog = true }, modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Text(stringResource(R.string.clear), fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.size(6.dp))
                for (i in 0..<historyLst.size) {
                    val historyIndex = historyLst.size - 1 - i
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = {
                                SharedState.update(historyLst[historyIndex])
                            }, onLongClick = {
                                historyLst.removeAt(historyIndex)
                                saveHistory()
                            })
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(12.dp, 10.dp)
                    ) {
                        Text(historyLst[historyIndex], fontSize = 16.sp)
                    }
                    Spacer(Modifier.size(6.dp))
                }
            }
        }
    }
}

@Composable
fun FullscreenVirtualScreen(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val metrics = remember {
        val dm = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display.getRealMetrics(dm)
        else wm.defaultDisplay.getRealMetrics(dm)
        dm
    }
    val physicalWidth = metrics.widthPixels
    val physicalHeight = metrics.heightPixels
    val physicalDpi = metrics.densityDpi

    DisposableEffect(Unit) {
        val window = (context as ComponentActivity).window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = Alignment.Center
    ) {
        VirtualDisplayViewer(
            modifier = Modifier.fillMaxSize(),
            width = physicalWidth,
            height = physicalHeight,
            densityDpi = physicalDpi,
        )
    }
}

@Composable
fun AppSelectorDialog(
    apps: List<AppInfo>, onDismiss: () -> Unit, onAppSelected: (AppInfo) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
        ) {
            Column {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { app ->
                        AppListItem(app = app, onClick = { onAppSelected(app) })
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItem(
    app: AppInfo, onClick: () -> Unit
) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable { onClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // 每行左边：应用图标
        Image(
            bitmap = app.icon.toComposeImageBitmap(),
            contentDescription = app.name,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // 每行右边：垂直排列的名称和包名
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --- 以下为工具函数 ---

/**
 * 获取设备上可通过桌面图标启动的应用列表
 */
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
    }.distinctBy { it.packageName } // 去重
        .sortedBy { it.name }          // 按应用名称字母排序
}

/**
 * 将 Android 原生 Drawable 转换为 Compose 可用的 ImageBitmap
 */
fun Drawable.toComposeImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable && this.bitmap != null) {
        return this.bitmap.asImageBitmap()
    }
    // 处理自适应图标 (AdaptiveIconDrawable) 或其他不自带 Bitmap 的 Drawable
    val width = if (intrinsicWidth > 0) intrinsicWidth else 100
    val height = if (intrinsicHeight > 0) intrinsicHeight else 100
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
