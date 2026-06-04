package com.xjyzs.operator

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xjyzs.operator.ui.theme.OperatorTheme
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets


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
        lateinit var mediaPlayer: MediaPlayer
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.kpalv) // keep alive
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        mediaPlayer.start()
                    }
                }
            }
            audioManager.requestAudioFocus(
                focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
            mediaPlayer.apply {
                isLooping = true
                setVolume(0.01f, 0.01f)
                start()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@SuppressLint("BatteryLife")
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun MainUI() {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }
    var accessibilityDialog by remember { mutableStateOf(false) }
    val apiPref = context.getSharedPreferences("api", Context.MODE_PRIVATE)
    val imeLst = remember { mutableStateListOf<String>() }
    val pref = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    val historyLst = remember { mutableStateListOf<String>() }
    val saveHistory = {
        pref.edit { putString("history", Gson().toJson(historyLst)) }
    }
    val newMsg by SharedState.newMsg.collectAsStateWithLifecycle()


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
        var hasRoot = false
        val targetService = "com.xjyzs.operator/.FloatingWindowService"
        val currentServices = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val servicesList =
            currentServices.split(":").filter { it.isNotEmpty() }.toMutableList()
        if (!servicesList.contains(targetService)) {
            servicesList.add(targetService)
            val newServices = servicesList.joinToString(":")
            hasRoot = try {
                Runtime.getRuntime().exec(
                    arrayOf(
                        "su",
                        "-c",
                        "settings put secure enabled_accessibility_services \"$newServices\""
                    )
                )
                true
            } catch (e: Exception) {
                false
            }
        }
        val hasOverlay = Settings.canDrawOverlays(context)
        val hasBattery = isIgnoringBatteryOptimizations(context)

        val apiUrl = apiPref.getString("apiUrl", "") ?: ""

        if (!hasRoot || !hasOverlay || !hasBattery || apiUrl.isEmpty()) {
            context.startActivity(Intent(context, WelcomeActivity::class.java))
            (context as ComponentActivity).finish()
            return@LaunchedEffect
        }
        permissionsGranted = true

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ime list -a -s"))
            val reader =
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            val outputBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputBuilder.append(line).append("\n")
            }
            val output = outputBuilder.toString()
            val list = output.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            imeLst.clear()
            imeLst.addAll(list)
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "ime enable com.android.adbkeyboard/.AdbIME"))
        } catch (_: Exception) {
        }
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
                }) { Text("确定") }
            },
            title = { Text("${stringResource(R.string.app_name)} 申请获取无障碍权限") },
            text = { Text("允许后，AI 才能获取详细屏幕布局信息。\n如果按钮为灰色，请前往本应用的详情设置，点击右上角相应菜单来解除限制。") })
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
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton({
                    cleanConfirmDialog = false
                }) { Text("取消") }
            },
            title = { Text("清空历史记录") },
            text = { Text("清空后，历史记录将不可恢复，确认要清空吗?") })
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
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton({
                    clearTokensDialog = false
                }) { Text("取消") }
            },
            title = { Text("清空 Tokens 记录") },
            text = { Text("清空后，记录将不可恢复，确认要清空吗?") })
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = {
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
        }, modifier = Modifier.padding(horizontal = 8.dp).nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
        ) {
            Row {
                Column(Modifier.weight(0.618f)) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(6.dp, 10.dp)
                    ) {
                        Text(
                            "控制",
                            fontSize = 22.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                {
                                    val msgs = SharedState.msgs
                                    if (msgs.size > 1) msgs.removeRange(1, msgs.size)
                                }
                            ) {
                                Text("清空当前会话", fontSize = 16.sp)
                            }
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(12.dp, 10.dp)
                            .height(126.dp)
                    ) {
                        Text(
                            "输入法",
                            fontSize = 22.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                        LazyColumn {
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
                            "Tokens",
                            fontSize = 22.sp,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            { clearTokensDialog = true },
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Text("清空", fontSize = 16.sp)
                        }
                    }
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "生成",
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            val completionTokens by SharedState.completionTokens.collectAsStateWithLifecycle()
                            Text(
                                completionTokens.toString(),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "输入",
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            val promptTokens by SharedState.promptTokens.collectAsStateWithLifecycle()
                            Text(
                                promptTokens.toString(),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "缓存",
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            val cachedTokens by SharedState.cachedTokens.collectAsStateWithLifecycle()
                            Text(
                                cachedTokens.toString(),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "图片",
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                            val imageTokens by SharedState.imageTokens.collectAsStateWithLifecycle()
                            Text(
                                imageTokens.toString(),
                                fontSize = 22.sp,
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
                Text("历史记录", fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
                TextButton(
                    { cleanConfirmDialog = true }, modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text("清空", fontSize = 16.sp)
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

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
