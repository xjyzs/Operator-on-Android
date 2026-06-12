package com.xjyzs.operator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.google.gson.JsonParser
import com.xjyzs.operator.ui.theme.OperatorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfigUI() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var apiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    val models = remember { mutableStateListOf<String>() }
    val apiPref = context.getSharedPreferences("api", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        scope.launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
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
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            (context as ComponentActivity).finish()
                        }, colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
                .padding(innerPadding)
                .padding(30.dp)
                .verticalScroll(scrollState)
        ) {
            TextField(
                label = { Text("API Base URL") },
                value = apiUrl,
                onValueChange = { apiUrl = it },
                placeholder = { Text("示例: https://api.closeai.com/v1") },
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                label = { Text("API Key") },
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                label = { Text("模型名称") },
                value = model,
                onValueChange = { model = it },
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
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = ""
                            )
                        }
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                })
            DropdownMenu(
                expanded = modelsExpanded,
                onDismissRequest = { modelsExpanded = false }
            ) {
                for (i in models) {
                    if (model.lowercase() in i.lowercase()) {
                        DropdownMenuItem(
                            text = { Text(i) },
                            onClick = {
                                model = i
                                modelsExpanded = false
                            }
                        )
                    }
                }
            }
            Button({
                apiPref.edit {
                    putString("apiUrl", apiUrl)
                    putString("apiKey", apiKey)
                    putString("model", model)
                }
                val intent = Intent(context, MainActivity::class.java)
                context.startActivity(intent)
                (context as ComponentActivity).finish()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("确认")
            }
        }
    }
}