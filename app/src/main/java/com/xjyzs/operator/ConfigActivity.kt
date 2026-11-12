package com.xjyzs.operator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonParser
import com.xjyzs.operator.ui.theme.OperatorTheme
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.getInstalledApps
import com.xjyzs.operator.utils.ui.AppSelectorDialog
import com.xjyzs.operator.utils.ui.CardEntry
import com.xjyzs.operator.utils.ui.SmallTitle
import com.xjyzs.operator.utils.ui.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OperatorTheme {
                ConfigUI()
            }
        }
    }
}

@Composable
fun ConfigUI() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var apiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var showAppSelector by remember { mutableStateOf(false) }
    val models = remember { mutableStateListOf<String>() }
    val apiPref = context.getSharedPreferences("api", Context.MODE_PRIVATE)
    val usesVirtualDisplay by SharedState.usesVirtualDisplay.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        apiUrl = apiPref.getString("apiUrl", "")!!
        apiKey = apiPref.getString("apiKey", "")!!
        model = apiPref.getString("model", "")!!
    }
    fun getModels() {
        isLoading = true
        val url = if (apiUrl.endsWith("chat/completions")) {
            apiUrl.dropLast(16) + "models"
        } else if (apiUrl.endsWith("chat/completions/")) {
            apiUrl.dropLast(17) + "models"
        } else if (apiUrl.endsWith("/")) {
            "${apiUrl}models"
        } else {
            "$apiUrl/models"
        }
        scope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request =
                    Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey").build()
                val response: Response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JsonParser.parseString(response.body.string()).asJsonObject
                    val data = json.getAsJsonArray("data")
                    for (i in data) {
                        models.add(i.asJsonObject.get("id").asString)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
            }
        }
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            (context as ComponentActivity).finish()
                        }, colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
                .padding(innerPadding)
                .padding(30.dp)
                .verticalScroll(scrollState)
        ) {
            SmallTitle("通用")
            SwitchRow(
                stringResource(R.string.virtual_screen),
                usesVirtualDisplay,
                {
                    SharedState._usesVirtualDisplay.value = it
                    apiPref.edit { putBoolean("usesVirtualDisplay", it) }
                })
            AnimatedVisibility(
                visible = !usesVirtualDisplay && !SharedState.imeLst.contains("com.android.adbkeyboard/.AdbIME"),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.size(8.dp))
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text("需要 ADB Keyboard")
                        Text(
                            "AI 需要 ADB Keyboard 才能输入文字，如果你需要此功能，请前往 https://github.com/senzhk/ADBKeyBoard/releases/tag/v2.5-dev 下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            Spacer(Modifier.weight(1f))
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
                            }) { Text("前往下载") }
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            CardEntry("不尝试获取布局的应用", "也许可以降低被目标应用风控的可能性") { showAppSelector = true }
            if (showAppSelector) {
                val installedApps = remember { getInstalledApps(context) }
                AppSelectorDialog(
                    apps = installedApps,
                    onDismiss = { showAppSelector = false },
                    onAppSelected = { _ -> },
                    showSwitch = true
                )
            }
            SmallTitle("AI API")
            TextField(
                label = { Text(stringResource(R.string.api_base_url_label)) },
                value = apiUrl,
                onValueChange = {
                    apiUrl = it
                    SharedState.updateApiUrl(apiUrl)
                    apiPref.edit {
                        putString("apiUrl", apiUrl)
                    }
                },
                placeholder = { Text(stringResource(R.string.api_base_url_placeholder)) },
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                label = { Text(stringResource(R.string.api_key_label)) },
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    SharedState.apiKey = apiKey
                    apiPref.edit {
                        putString("apiKey", apiKey)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                label = { Text(stringResource(R.string.model_name_label)) },
                value = model,
                onValueChange = {
                    model = it
                    SharedState.model = model
                    apiPref.edit {
                        putString("model", model)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (!isLoading) {
                        IconButton(onClick = {
                            modelsExpanded = !modelsExpanded
                            if (models.isEmpty()) {
                                getModels()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown, contentDescription = ""
                            )
                        }
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                })
            DropdownMenu(
                expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                for (i in models) {
                    if (model.lowercase() in i.lowercase()) {
                        DropdownMenuItem(text = { Text(i) }, onClick = {
                            model = i
                            modelsExpanded = false
                            SharedState.model = model
                            apiPref.edit {
                                putString("model", model)
                            }
                        })
                    }
                }
            }
        }
    }
}