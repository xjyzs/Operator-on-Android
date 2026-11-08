package com.xjyzs.operator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjyzs.operator.ui.theme.OperatorTheme
import com.xjyzs.operator.utils.AgentController
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.RunningState
import com.xjyzs.operator.utils.clickVibrate
import com.xjyzs.operator.utils.data.ChatMessage
import com.xjyzs.operator.utils.data.ChatRepository
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.data.SharedState.apiPref
import com.xjyzs.operator.utils.execute.ShellExecutor
import com.xjyzs.operator.utils.execute.ShellType
import com.xjyzs.operator.utils.execute.lastX1
import com.xjyzs.operator.utils.execute.lastX2
import com.xjyzs.operator.utils.execute.lastY1
import com.xjyzs.operator.utils.execute.lastY2
import com.xjyzs.operator.utils.ui.LeftDrawer
import com.xjyzs.operator.utils.ui.MainScreenMessageItem
import com.xjyzs.operator.utils.ui.VirtualDisplayViewer
import com.xjyzs.operator.utils.ui.mainButtonAction
import com.xjyzs.operator.utils.ui.parseContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SharedState.init(this)
        setContent {
            OperatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) { MainUI() }
            }
        }
    }
}

data class AppInfo(
    val name: String, val packageName: String, val icon: Drawable
)

data class SessionItem(
    val id: Long, val title: String
)

@SuppressLint("BatteryLife")
@Composable
fun MainUI() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val executor = remember { ShellExecutor.getInstance() }
    val sessionTitlesPref =
        remember { context.getSharedPreferences("session_titles", Context.MODE_PRIVATE) }

    var accessibilityDialog by remember { mutableStateOf(false) }
    var clearTokensDialog by remember { mutableStateOf(false) }
    var imeDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showVirtualScreenPreview by remember { mutableStateOf(false) }

    val imeLst = remember { mutableStateListOf<String>() }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    val leftDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val rightDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val windowSize = adaptiveInfo.windowSizeClass
    val usePermanentDrawer = windowSize.isWidthAtLeastBreakpoint(500)

    val messages by ChatRepository.messages.collectAsStateWithLifecycle()
    val toolResults by ChatRepository.toolResults.collectAsStateWithLifecycle()
    val runningState by AgentController.runningState.collectAsStateWithLifecycle()
    val currentSessionId by ChatRepository.currentSessionIdState.collectAsStateWithLifecycle()

    var inputMsg by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()

    var sessionsList by remember { mutableStateOf<List<SessionItem>>(emptyList()) }
    var openRenameDialog by remember { mutableStateOf(false) }
    var openDelDialog by remember { mutableStateOf(false) }
    var editingSessionId by remember { mutableLongStateOf(1L) }
    var renameText by remember { mutableStateOf(TextFieldValue("")) }

    BackHandler(enabled = rightDrawerState.isOpen) {
        scope.launch { rightDrawerState.close() }
    }
    BackHandler(enabled = !rightDrawerState.isOpen && leftDrawerState.isOpen) {
        scope.launch { leftDrawerState.close() }
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
        val useShizuku = apiPref.getBoolean("useShizuku", false)
        val targetType = if (useShizuku) ShellType.SHIZUKU else ShellType.ROOT
        executor.close()
        executor.setShellType(targetType)
        var hasShell = false

        try {
            if (targetType == ShellType.SHIZUKU) {
                var retries = 0
                while (!Shizuku.pingBinder() && retries < 10) {
                    delay(200.milliseconds)
                    retries++
                }
            }
            val result = executor.execute("id")
            if (result.isSuccess && (result.stdout.contains("uid=0") || result.stdout.contains("uid=2000") || result.stdout.contains(
                    "shell"
                ) || result.stdout.contains("root"))
            ) hasShell = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val hasOverlay = Settings.canDrawOverlays(context)
        if (!hasShell || !hasOverlay) {
            context.startActivity(Intent(context, WelcomeActivity::class.java))
            (context as ComponentActivity).finish()
            return@LaunchedEffect
        }

        try {
            val list = executor.execute("ime list -a -s").stdout.split("\n").map { it.trim() }
                .filter { it.isNotBlank() }
            imeLst.clear()
            imeLst.addAll(list)
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
        withContext(Dispatchers.IO) {
            InputControlUtils.init(context)
        }
        while (true) {
            delay(1000.milliseconds)
            if (FloatingWindowService.isRunning) {
                accessibilityDialog = false
                break
            }
            accessibilityDialog = true
        }
    }

    val refreshSessions: suspend () -> Unit = {
        val ids = withContext(Dispatchers.IO) {
            ChatRepository.getAllSessionIds()
        }
        val defaultNew = context.getString(R.string.new_conversation)
        val items = withContext(Dispatchers.IO) {
            ids.map { id ->
                val custom = sessionTitlesPref.getString("title_$id", null)
                val firstMsg = ChatRepository.getSessionFirstUserMessage(id)
                val title = if (!custom.isNullOrBlank()) {
                    custom
                } else if (!firstMsg.isNullOrBlank()) {
                    val parsed = parseContent(firstMsg).userTask
                    if (parsed.isNotBlank()) parsed
                    else {
                        val clean = firstMsg.replace("\n", " ").trim()
                        if (clean.length > 20) clean.take(20) + "..." else clean
                    }
                } else {
                    "$defaultNew $id"
                }
                SessionItem(id, title)
            }
        }
        sessionsList = items
    }

    LaunchedEffect(leftDrawerState.isOpen, currentSessionId, messages.size) {
        refreshSessions()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            lazyListState.scrollToItem(messages.lastIndex)
        }
    }

    val lastMsgLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(lastMsgLength) {
        if (messages.isNotEmpty() && !lazyListState.isScrollInProgress) {
            lazyListState.scrollToItem(messages.lastIndex)
        }
    }

    val currentSessionTitle by remember(currentSessionId, sessionsList) {
        derivedStateOf {
            sessionsList.find { it.id == currentSessionId }?.title ?: ""
        }
    }

    val handleMainButton: (String) -> Unit = { textToSend ->
        scope.launch {
            mainButtonAction(textToSend, executor, context, { inputMsg = "" }, {
                keyboardController?.hide()
                focusManager.clearFocus()
            })
        }
    }

    if (accessibilityDialog) {
        AlertDialog(onDismissRequest = {}, confirmButton = {
            TextButton({
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }) { Text(stringResource(R.string.confirm)) }
        }, title = {
            Text(
                stringResource(
                    R.string.accessibility_permission_request, stringResource(R.string.app_name)
                )
            )
        }, text = { Text(stringResource(R.string.accessibility_permission_text)) })
    }

    if (clearTokensDialog) {
        AlertDialog(
            onDismissRequest = { clearTokensDialog = false },
            confirmButton = {
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
                TextButton({ clearTokensDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.clear_tokens_title)) },
            text = { Text(stringResource(R.string.clear_tokens_text)) })
    }

    if (imeDialog) {
        AlertDialog(
            onDismissRequest = { imeDialog = false },
            confirmButton = {
                TextButton({
                    try {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/senzhk/ADBKeyBoard/releases/tag/v2.5-dev".toUri()
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        context.sendBroadcast(Intent("ACTION_KILL_SELF"))
                    } catch (_: Exception) {
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            title = { Text("需要 ADB Keyboard") },
            text = { Text("AI 需要 ADB Keyboard 才能输入文字，如果你需要此功能，请前往 https://github.com/senzhk/ADBKeyBoard/releases/tag/v2.5-dev 下载") })
    }

    if (showUrlDialog) {
        AlertDialog(onDismissRequest = { showUrlDialog = false }, confirmButton = {
            TextButton({ showUrlDialog = false }) { Text(stringResource(R.string.confirm)) }
        }, title = { Text("访问地址") }, text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                WebRemoteServer.getAllUrls().forEach { url ->
                    SelectionContainer {
                        Text(
                            url, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        })
    }

    if (openRenameDialog) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        AlertDialog(
            onDismissRequest = { openRenameDialog = false },
            title = { Text(stringResource(R.string.rename_conversation)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(fontSize = 18.sp),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openRenameDialog = false
                    val newName = renameText.text.trim()
                    if (newName.isNotEmpty()) {
                        sessionTitlesPref.edit {
                            putString("title_$editingSessionId", newName)
                        }
                        scope.launch { refreshSessions() }
                    }
                }) {
                    Text(stringResource(R.string.done))
                }
            },
            dismissButton = {
                TextButton(onClick = { openRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            })
    }

    if (openDelDialog) {
        AlertDialog(
            onDismissRequest = { openDelDialog = false },
            title = { Text(stringResource(R.string.delete_conversation)) },
            text = { Text(stringResource(R.string.delete_conversation_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    openDelDialog = false
                    scope.launch {
                        sessionTitlesPref.edit { remove("title_$editingSessionId") }
                        ChatRepository.deleteSession(editingSessionId)
                        refreshSessions()
                    }
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { openDelDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            })
    }

    if (showDialog) {
        AppSelectorDialog(
            apps = installedApps,
            onDismiss = { showDialog = false },
            onAppSelected = { app ->
                showDialog = false
                if (InputControlUtils.displayId == -1) {
                    InputControlUtils.createVirtualDisplay(
                        DisplayMetricsStore.width,
                        DisplayMetricsStore.height,
                        DisplayMetricsStore.densityDpi
                    )
                }
                InputControlUtils.moveAppToDisplay(
                    app.packageName, InputControlUtils.displayId
                )
            })
    }

    if (showVirtualScreenPreview) {
        FullscreenVirtualScreen(onClose = {
            showVirtualScreenPreview = false
        })
    } else {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ModalNavigationDrawer(
                drawerState = rightDrawerState,
                gesturesEnabled = !leftDrawerState.isOpen,
                drawerContent = {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        ModalDrawerSheet(
                            modifier = Modifier.width(260.dp)
                        ) {
                            RightDrawerContent(
                                usesVirtualScreen = SharedState.usesVirtualDisplay.collectAsStateWithLifecycle().value,
                                onVirtualScreenChange = {
                                    SharedState._usesVirtualDisplay.value = it
                                    apiPref.edit { putBoolean("usesVirtualDisplay", it) }
                                    if (!it && !imeLst.contains("com.android.adbkeyboard/.AdbIME")) {
                                        imeDialog = true
                                    }
                                },
                                onViewVirtualScreen = {
                                    scope.launch { rightDrawerState.close() }
                                    showVirtualScreenPreview = true
                                },
                                onLaunchApp = {
                                    scope.launch { rightDrawerState.close() }
                                    showDialog = true
                                },
                                imeLst = imeLst,
                                onSelectIme = { ime ->
                                    scope.launch { executor.execute("ime set $ime") }
                                },
                                onClearTokens = { clearTokensDialog = true },
                                onShowUrlDialog = { showUrlDialog = true },
                                onToggleServer = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            if (RemoteBridge.serverRunning.value) WebRemoteServer.stop()
                                            else WebRemoteServer.start()
                                        }
                                    }
                                },
                                onOpenSettings = {
                                    val intent = Intent(context, ConfigActivity::class.java)
                                    context.startActivity(intent)
                                })
                        }
                    }
                }) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LeftDrawer(
                        usePermanentDrawer,
                        drawerState = leftDrawerState,
                        gesturesEnabled = !rightDrawerState.isOpen,
                        drawerContent = {
                            ModalDrawerSheet(Modifier.width(300.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) {
                                LeftDrawerContent(
                                    sessions = sessionsList,
                                    currentSessionId = currentSessionId,
                                    onSelectSession = { sid ->
                                        scope.launch {
                                            leftDrawerState.close()
                                            if (sid != currentSessionId) {
                                                if (AgentController.runningState.value != RunningState.STOP) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.ai_answering),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    ChatRepository.switchSession(sid)
                                                    delay(50.milliseconds)
                                                    if (messages.isNotEmpty()) {
                                                        lazyListState.scrollToItem(messages.lastIndex)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onNewSession = {
                                        if (AgentController.runningState.value != RunningState.STOP) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.ai_answering),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            scope.launch {
                                                leftDrawerState.close()
                                                ChatRepository.newSession()
                                                lastX1 = -1f
                                                lastY1 = -1f
                                                lastX2 = -1f
                                                lastY2 = -1f
                                                refreshSessions()
                                            }
                                        }
                                    },
                                    onRenameSession = { sid, currentTitle ->
                                        editingSessionId = sid
                                        renameText = TextFieldValue(
                                            currentTitle,
                                            selection = TextRange(0, currentTitle.length)
                                        )
                                        openRenameDialog = true
                                    },
                                    onDeleteSession = { sid ->
                                        editingSessionId = sid
                                        openDelDialog = true
                                    },
                                    vibrator = vibrator
                                )
                            }
                        }) {
                        MainScaffold(
                            currentSessionTitle = currentSessionTitle,
                            onOpenLeftDrawer = { scope.launch { leftDrawerState.open() } },
                            onOpenRightDrawer = { scope.launch { rightDrawerState.open() } },
                            usePermanentDrawer=usePermanentDrawer,
                            messages = messages,
                            toolResults = toolResults,
                            runningState = runningState,
                            inputMsg = inputMsg,
                            onInputMsgChange = { inputMsg = it },
                            onSend = handleMainButton,
                            lazyListState = lazyListState,
                            vibrator = vibrator,
                            keyboardController = keyboardController,
                            focusManager = focusManager
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LeftDrawerContent(
    sessions: List<SessionItem>,
    currentSessionId: Long,
    onSelectSession: (Long) -> Unit,
    onNewSession: () -> Unit,
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    vibrator: Vibrator
) {
    var showMenuForId by remember { mutableLongStateOf(-1L) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.conversation_history),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        clickVibrate(vibrator)
                        onNewSession()
                    }, contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_conversation),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                val isCurrent = session.id == currentSessionId
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .combinedClickable(onClick = {
                            clickVibrate(vibrator)
                            onSelectSession(session.id)
                        }, onLongClick = {
                            clickVibrate(vibrator)
                            showMenuForId = session.id
                        })
                        .background(if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                ) {
                    Text(
                        text = session.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 17.sp,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    DropdownMenu(
                        expanded = showMenuForId == session.id,
                        onDismissRequest = { showMenuForId = -1L }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = {
                                val sid = session.id
                                val title = session.title
                                showMenuForId = -1L
                                onRenameSession(sid, title)
                            })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                val sid = session.id
                                showMenuForId = -1L
                                onDeleteSession(sid)
                            })
                    }
                }
            }
        }
    }
}

@Composable
fun RightDrawerContent(
    usesVirtualScreen: Boolean,
    onVirtualScreenChange: (Boolean) -> Unit,
    onViewVirtualScreen: () -> Unit,
    onLaunchApp: () -> Unit,
    imeLst: List<String>,
    onSelectIme: (String) -> Unit,
    onClearTokens: () -> Unit,
    onShowUrlDialog: () -> Unit,
    onToggleServer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 控制
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(10.dp)
            ) {
                Text(
                    stringResource(R.string.control_section),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.virtual_screen))
                    Spacer(Modifier.weight(1f))
                    Switch(checked = usesVirtualScreen, onCheckedChange = onVirtualScreenChange)
                }
                if (usesVirtualScreen) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onViewVirtualScreen) {
                            Text(stringResource(R.string.view_virtual_screen), fontSize = 15.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onLaunchApp) {
                            Text(stringResource(R.string.launch_app), fontSize = 15.sp)
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.input_method_section),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
                    )
                    Column {
                        imeLst.forEach { ime ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectIme(ime) }
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(10.dp, 8.dp)) {
                                Text(ime.substringAfterLast("."), fontSize = 15.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Tokens
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.tokens_label),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearTokens) {
                        Text(stringResource(R.string.clear), fontSize = 15.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.completion_label),
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        val completionTokens by SharedState.completionTokens.collectAsStateWithLifecycle()
                        Text(
                            completionTokens.toString(),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.input_label),
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        val promptTokens by SharedState.promptTokens.collectAsStateWithLifecycle()
                        Text(
                            promptTokens.toString(),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.image_label),
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        val imageTokens by SharedState.imageTokens.collectAsStateWithLifecycle()
                        Text(
                            imageTokens.toString(),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.cache_label),
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        val cachedTokens by SharedState.cachedTokens.collectAsStateWithLifecycle()
                        Text(
                            cachedTokens.toString(),
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 远程管理
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "远程管理",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    val port by RemoteBridge.port
                    Badge(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onShowUrlDialog() },
                        MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(":$port", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    val serverRunning by RemoteBridge.serverRunning
                    TextButton(onClick = onToggleServer) {
                        Text(if (serverRunning) "停止" else "启动", fontSize = 15.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 底部靠左有“设置”图标，点击后保存当前点“设置”的逻辑
        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = onOpenSettings, colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.1f
                    )
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        }
    }
}

@Composable
fun MainScaffold(
    currentSessionTitle: String,
    onOpenLeftDrawer: () -> Unit,
    onOpenRightDrawer: () -> Unit,
    usePermanentDrawer: Boolean,
    messages: List<ChatMessage>,
    toolResults: Map<String, String>,
    runningState: RunningState,
    inputMsg: String,
    onInputMsgChange: (String) -> Unit,
    onSend: (String) -> Unit,
    lazyListState: LazyListState,
    vibrator: Vibrator,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager
) {
    Scaffold(topBar = {
        TopAppBar(title = {
            Text(
                text = currentSessionTitle.ifEmpty { stringResource(R.string.app_name) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }, navigationIcon = {
            if (!usePermanentDrawer) {
                IconButton(
                    onClick = {
                        clickVibrate(vibrator)
                        onOpenLeftDrawer()
                    }, colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.1f
                        )
                    )
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = stringResource(R.string.conversation_history)
                    )
                }
            }
        }, actions = {
            IconButton(
                onClick = {
                    clickVibrate(vibrator)
                    onOpenRightDrawer()
                }, colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = 0.1f
                    )
                )
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings)
                )
            }
        })
    }, bottomBar = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp, 0.dp, 8.dp, 8.dp)
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 1.dp, shape = RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = inputMsg,
                        onValueChange = onInputMsgChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 36.dp),
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.padding(vertical = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputMsg.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.input_command_hint),
                                        style = LocalTextStyle.current.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 15.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        })
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                clickVibrate(vibrator)
                                onSend(inputMsg)
                            }, contentAlignment = Alignment.Center
                    ) {
                        val icon = when (runningState) {
                            RunningState.STOP -> Icons.Default.ArrowUpward
                            RunningState.RUNNING -> ImageVector.vectorResource(R.drawable.ic_rectangle)
                            else -> Icons.AutoMirrored.Filled.ArrowForward
                        }
                        if (runningState != RunningState.CONNECTING) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(4.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }) { innerPadding ->
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }, state = lazyListState
            ) {
                items(items = messages, key = { it.id }) { msg ->
                    MainScreenMessageItem(msg, toolResults)
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
            .background(Color.Black),
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
            var filter by remember { mutableStateOf("") }
            Column {
                TextField(
                    filter,
                    { filter = it },
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) })
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        apps.filter { it.name.contains(filter, ignoreCase = true) },
                        key = { it.packageName }) { app ->
                        AppListItem(app = app, onClick = { onAppSelected(app) })
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItem(app: AppInfo, onClick: () -> Unit) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable { onClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = app.icon.toComposeImageBitmap(),
            contentDescription = app.name,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
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

/**
 * 将 Android 原生 Drawable 转换为 Compose 可用的 ImageBitmap
 */
fun Drawable.toComposeImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable && this.bitmap != null) {
        return this.bitmap.asImageBitmap()
    }
    val width = if (intrinsicWidth > 0) intrinsicWidth else 100
    val height = if (intrinsicHeight > 0) intrinsicHeight else 100
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}
