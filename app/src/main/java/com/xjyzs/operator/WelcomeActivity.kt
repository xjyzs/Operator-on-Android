package com.xjyzs.operator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xjyzs.operator.ui.theme.OperatorTheme
import kotlinx.coroutines.delay

class WelcomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OperatorTheme {
                Surface {
                    WelcomeUI()
                }
            }
        }
    }
}

@Composable
fun WelcomeUI() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var rootPermission by remember { mutableStateOf(false) }
    var overlayPermission by remember { mutableStateOf(false) }
    var batteryPermission by remember { mutableStateOf(false) }
    var accessibilityPermission by remember { mutableStateOf(false) }
    var notificationPermission by remember { mutableStateOf(false) }
    var detectTrigger by remember { mutableStateOf(false) }

    val checkPermissions = {
        try {
            Runtime.getRuntime().exec("su")
            rootPermission = true
        } catch (_: Exception) {
            rootPermission = false
        }
        overlayPermission = Settings.canDrawOverlays(context)
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryPermission = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        
        accessibilityPermission = FloatingWindowService.isRunning
        
        notificationPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(detectTrigger) {
        while (true) {
            checkPermissions()
            delay(1000)
        }
    }

    val allGranted = rootPermission && overlayPermission

    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp), contentAlignment = Alignment.Center
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(80.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_launcher), null, Modifier.clip(CircleShape))
                Text(stringResource(R.string.welcome_title), fontSize = 32.sp)
                Text(stringResource(R.string.welcome_subtitle), color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.size(40.dp))
            }
            Column {
                Text(stringResource(R.string.required_section), fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                
                PermissionItem(
                    granted = rootPermission,
                    icon = Icons.Default.Tag,
                    title = stringResource(R.string.root_permission_title),
                    desc = stringResource(R.string.root_permission_desc),
                    onClick = {
                        try {
                            Runtime.getRuntime().exec("su")
                        } catch (_: Exception) {}
                        detectTrigger = !detectTrigger
                    }
                )

                PermissionItem(
                    granted = overlayPermission,
                    icon = Icons.Default.Layers,
                    title = stringResource(R.string.overlay_permission_title),
                    desc = stringResource(R.string.overlay_permission_desc),
                    onClick = {
                        if (!overlayPermission) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.optional_section), fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))

                if (Build.VERSION.SDK_INT >= 33) {
                    PermissionItem(
                        granted = notificationPermission,
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.notification_permission_title),
                        desc = stringResource(R.string.notification_permission_desc),
                        onClick = {
                            if (!notificationPermission) {
                                (context as ComponentActivity).requestPermissions(
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                                )
                            }
                        }
                    )
                }
                PermissionItem(
                    granted = batteryPermission,
                    icon = Icons.Default.BatterySaver,
                    title = stringResource(R.string.battery_optimization_title),
                    desc = stringResource(R.string.battery_optimization_desc),
                    onClick = {
                        if (!batteryPermission) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = "package:${context.packageName}".toUri()
                            context.startActivity(intent)
                        }
                    }
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button({
                        val apiPref = context.getSharedPreferences("api", Context.MODE_PRIVATE)
                        if (apiPref.getString("apiUrl", "")!!.isEmpty()) {
                            context.startActivity(Intent(context, ConfigActivity::class.java))
                        } else {
                            context.startActivity(Intent(context, MainActivity::class.java))
                        }
                        (context as ComponentActivity).finish()
                    }, enabled = allGranted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.start_using))
                            Icon(Icons.Default.ArrowUpward, null, Modifier.rotate(90f))
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
fun PermissionItem(
    granted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .background(if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else icon, null,
            Modifier.size(36.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                desc,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
