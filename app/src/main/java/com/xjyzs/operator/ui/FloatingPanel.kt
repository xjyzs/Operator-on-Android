package com.xjyzs.operator.ui

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjyzs.operator.FloatingWindowService
import com.xjyzs.operator.R
import com.xjyzs.operator.utils.AgentController
import com.xjyzs.operator.utils.RunningState
import com.xjyzs.operator.utils.clickVibrate
import com.xjyzs.operator.utils.data.ChatRepository
import com.xjyzs.operator.utils.execute.ShellExecutor
import com.xjyzs.operator.utils.execute.lastX1
import com.xjyzs.operator.utils.execute.lastX2
import com.xjyzs.operator.utils.execute.lastY1
import com.xjyzs.operator.utils.execute.lastY2
import com.xjyzs.operator.utils.ui.OverlayMessageItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import com.xjyzs.operator.RemoteBridge
import com.xjyzs.operator.RemoteCommand
import com.xjyzs.operator.utils.ui.mainButtonAction

@Composable
fun FloatingPanel(
    mFloatingView: View, layoutParams: WindowManager.LayoutParams, mWindowManager: WindowManager
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    val isMinimized = remember { mutableStateOf(true) }
    val offsetX = remember { Animatable(layoutParams.x.toFloat()) }
    val offsetY = remember { Animatable(layoutParams.y.toFloat()) }
    val density = LocalDensity.current
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val resources = context.resources
    val suInstance = ShellExecutor.getInstance()
    val messages by ChatRepository.messages.collectAsState()
    val toolResults by ChatRepository.toolResults.collectAsState()
    val runningState by AgentController.runningState.collectAsState()
    var textValue by remember { mutableStateOf(TextFieldValue()) }
    val statusBarHeight = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets =
                windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()
                )
            insets.top
        } else {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        }
    }

    val checkBoundsAndSnap: suspend () -> Unit = {
        val sw = DisplayMetricsStore.currentWidth
        val sh = DisplayMetricsStore.currentHeight - statusBarHeight
        val ww =
            if (isMinimized.value) with(density) { 56.dp.toPx() } else mFloatingView.width.toFloat()
        val hh =
            if (isMinimized.value) with(density) { 56.dp.toPx() } else mFloatingView.height.toFloat()
        val boundX = ((sw - ww) / 2f).coerceAtLeast(0f)

        val targetX = if (isMinimized.value) {
            if (offsetX.value > 0) boundX + ww / 3f else -(boundX + ww / 3f)
        } else {
            offsetX.value.coerceIn(-boundX, boundX)
        }
        val targetY = offsetY.value.coerceIn(0f, (sh - hh).coerceAtLeast(0f))

        if (targetX != offsetX.value || targetY != offsetY.value) {
            coroutineScope.launch {
                offsetX.animateTo(targetX, spring(stiffness = Spring.StiffnessMediumLow)) {
                    layoutParams.x = value.toInt()
                    mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                }
            }
            coroutineScope.launch {
                offsetY.animateTo(targetY, spring(stiffness = Spring.StiffnessMediumLow)) {
                    layoutParams.y = value.toInt()
                    mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                }
            }
        }
    }

    LaunchedEffect(configuration) {
        val sw = DisplayMetricsStore.currentWidth
        if (!isMinimized.value) {
            val cardMaxTotalWidth = with(density) { (560.dp + 12.dp).toPx() }.toInt()
            layoutParams.width = if (sw > cardMaxTotalWidth) cardMaxTotalWidth else MATCH_PARENT
            try {
                mWindowManager.updateViewLayout(mFloatingView, layoutParams)
            } catch (_: Exception) {
            }
        }
        delay(100.milliseconds)
        checkBoundsAndSnap()
    }
    val lazyListState = rememberLazyListState()

    val focusManager = LocalFocusManager.current
    suspend fun clearInputFocusAndAwait() {
        val service = FloatingWindowService.instance ?: return
        focusManager.clearFocus()
        repeat(20) {
            if (!service.isWindowFocusable) {
                return
            }
            delay(16.milliseconds)
        }
    }

    fun handleMainButton(text: String = textValue.text) {
        coroutineScope.launch {
            mainButtonAction(
                text,
                suInstance,
                context,
                { textValue = TextFieldValue() },
                { clearInputFocusAndAwait() })
        }
    }

    LaunchedEffect(Unit) {
        RemoteBridge.commands.collect { cmd ->
            when (cmd) {
                is RemoteCommand.Send -> if (AgentController.runningState.value.let { it == RunningState.STOP || it == RunningState.TAKE_OVER }) handleMainButton(
                    cmd.text
                )

                RemoteCommand.Stop -> if (AgentController.runningState.value != RunningState.STOP) AgentController.stop()
                RemoteCommand.Clear -> {
                    ChatRepository.newSession()
                    lastX1 = -1f; lastY1 = -1f; lastX2 = -1f; lastY2 = -1f
                }
            }
        }
    }

    Box(contentAlignment = Alignment.Center) {
        if (isMinimized.value) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { coroutineScope.launch { checkBoundsAndSnap() } },
                            onDragCancel = { coroutineScope.launch { checkBoundsAndSnap() } }) { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount.x)
                                val boundMaxY =
                                    (DisplayMetricsStore.currentHeight - mFloatingView.height).coerceAtLeast(
                                        0
                                    ).toFloat()
                                val newY = (offsetY.value - dragAmount.y).coerceIn(0f, boundMaxY)
                                offsetY.snapTo(newY)
                                layoutParams.x = offsetX.value.toInt()
                                layoutParams.y = offsetY.value.toInt()
                                mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                            }
                        }
                    }
                    .clickable {
                        coroutineScope.launch {
                            val sw = DisplayMetricsStore.currentWidth
                            val panelMaxWidthPx = with(density) { 560.dp.toPx() }
                            val targetX = if (sw < panelMaxWidthPx) 0f else {
                                val cardMaxTotalWidth = with(density) { (560.dp + 12.dp).toPx() }
                                val cardWidth = sw.toFloat().coerceAtMost(cardMaxTotalWidth)
                                val boundMaxX = ((sw - cardWidth) / 2f).coerceAtLeast(0f)
                                offsetX.value.coerceIn(-boundMaxX, boundMaxX)
                            }
                            offsetX.snapTo(targetX)
                            layoutParams.x = targetX.toInt()
                            val cardMaxTotalWidthInt =
                                with(density) { (560.dp + 12.dp).toPx() }.toInt()
                            layoutParams.width =
                                if (sw > cardMaxTotalWidthInt) cardMaxTotalWidthInt else MATCH_PARENT
                            mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                            isMinimized.value = false
                            delay(100.milliseconds)
                            checkBoundsAndSnap()
                        }
                    }, contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        } else {
            var isDraggingCard by remember { mutableStateOf(false) }
            val cardScale by animateFloatAsState(
                targetValue = if (isDraggingCard) 1.02f else 1f, animationSpec = tween(
                    durationMillis = 350, easing = FastOutSlowInEasing
                ), label = "cardScale"
            )
            Card(modifier = Modifier
                .padding(6.dp)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                }
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .height(170.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(24.dp)) {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            state = lazyListState,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(items = messages, key = { it.id }) { msg ->
                                OverlayMessageItem(msg, toolResults)
                            }
                        }
                        LaunchedEffect(messages.size) {
                            if (messages.isNotEmpty()) lazyListState.scrollToItem(messages.lastIndex)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 3.dp, end = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val onSurface = MaterialTheme.colorScheme.onSurface
                            val view = LocalView.current
                            BasicTextField(
                                value = textValue,
                                onValueChange = { textValue = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, Color.Gray, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .onFocusChanged { state ->
                                        val service = FloatingWindowService.instance
                                        if (state.isFocused) {
                                            service?.isWindowFocusable = true
                                            layoutParams.flags =
                                                (layoutParams.flags and FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                            layoutParams.softInputMode =
                                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                            try {
                                                mWindowManager.updateViewLayout(
                                                    mFloatingView, layoutParams
                                                )
                                            } catch (_: Exception) {
                                            }
                                            view.postDelayed({
                                                val imm =
                                                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                                imm.showSoftInput(
                                                    view, InputMethodManager.SHOW_IMPLICIT
                                                )
                                            }, 100)
                                        } else {
                                            service?.isWindowFocusable = false
                                            val imm =
                                                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                            imm.hideSoftInputFromWindow(
                                                view.windowToken, 0
                                            )
                                            layoutParams.flags =
                                                layoutParams.flags or FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                            layoutParams.softInputMode =
                                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                            try {
                                                mWindowManager.updateViewLayout(
                                                    mFloatingView, layoutParams
                                                )
                                            } catch (_: Exception) {
                                            }
                                        }
                                    },
                                textStyle = TextStyle(
                                    color = onSurface, fontSize = 14.sp, lineHeight = 20.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 3,
                                decorationBox = { innerTextField ->
                                    Box(
                                        contentAlignment = Alignment.CenterStart,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (textValue.text.isEmpty()) {
                                            Text(
                                                text = context.getString(R.string.input_command_hint),
                                                style = TextStyle(
                                                    color = Color.Gray,
                                                    fontSize = 14.sp,
                                                    lineHeight = 20.sp
                                                ),
                                                maxLines = 1
                                            )
                                        }
                                        innerTextField()
                                    }
                                })
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable {
                                        clickVibrate(vibrator)
                                        handleMainButton()
                                    }, contentAlignment = Alignment.Center
                            ) {
                                val icon = when (runningState) {
                                    RunningState.STOP -> Icons.Default.ArrowUpward
                                    RunningState.RUNNING -> ImageVector.vectorResource(R.drawable.ic_rectangle)
                                    else -> Icons.AutoMirrored.Filled.ArrowForward
                                }
                                if (runningState != RunningState.CONNECTING) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        Modifier.padding(4.dp), color = Color.White
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(onDragStart = {
                                    isDraggingCard = true
                                }, onDragEnd = {
                                    isDraggingCard = false
                                    val sw = DisplayMetricsStore.currentWidth
                                    val threshold = with(density) { 60.dp.toPx() }
                                    val distToEdge = (sw / 2f) - abs(offsetX.value)
                                    if (distToEdge < threshold) {
                                        isMinimized.value = true
                                        coroutineScope.launch {
                                            val ww = with(density) { 56.dp.toPx() }
                                            val boundMaxX =
                                                ((sw - ww) / 2f).coerceAtLeast(0f) + (ww / 3f)
                                            val targetX =
                                                if (offsetX.value > 0) boundMaxX else -boundMaxX
                                            offsetX.snapTo(targetX)
                                            layoutParams.x = targetX.toInt()
                                            layoutParams.width =
                                                with(density) { 56.dp.toPx() }.toInt()
                                            mWindowManager.updateViewLayout(
                                                mFloatingView, layoutParams
                                            )
                                            checkBoundsAndSnap()
                                        }
                                    } else {
                                        coroutineScope.launch { checkBoundsAndSnap() }
                                    }
                                }, onDragCancel = {
                                    isDraggingCard = false
                                    coroutineScope.launch { checkBoundsAndSnap() }
                                }) { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        offsetX.snapTo(offsetX.value + dragAmount.x)
                                        val boundMaxY =
                                            (DisplayMetricsStore.currentHeight - mFloatingView.height).coerceAtLeast(
                                                0
                                            ).toFloat()
                                        val newY =
                                            (offsetY.value - dragAmount.y).coerceIn(0f, boundMaxY)
                                        offsetY.snapTo(newY)
                                        layoutParams.x = offsetX.value.toInt()
                                        layoutParams.y = offsetY.value.toInt()
                                        mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                                    }
                                }
                            }, contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                .width(80.dp)
                                .height(4.dp)
                        )
                    }
                }
            }
        }
    }
}

fun WindowManager.LayoutParams.applyFloatingPanelPosition(context: Context) {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val sw = metrics.widthPixels.toFloat()
    val sh = metrics.heightPixels.toFloat()
    val iconSize = 56f * density
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    val statusBarHeight = if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId).toFloat() else 0f
    val boundX = ((sw - iconSize) / 2f).coerceAtLeast(0f)
    this.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    this.x = -(boundX + iconSize / 3f).toInt()
    this.y = (((sh - statusBarHeight) - iconSize) / 4f).toInt()
}