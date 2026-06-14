package com.xjyzs.operator.remote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.ImageReader
import android.os.Build
import android.os.IBinder
import android.os.Looper.getMainLooper
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.os.SharedMemory
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.core.graphics.createBitmap
import com.xjyzs.operator.IInputControl
import com.xjyzs.operator.remote.utils.BitmapEditor
import java.io.ByteArrayOutputStream
import kotlin.system.exitProcess

class InputControlService : IInputControl.Stub() {
    companion object {
        const val SERVICE_NAME = "com.xjyzs.operator.input_control"
        private const val INJECT_MODE_WAIT_FOR_RESULT = 2
    }

    private val inputManagerInstance: Any = try {
        try {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            globalClass.getMethod("getInstance").invoke(null)
        } catch (e: ClassNotFoundException) {
            InputManager::class.java.getMethod("getInstance").invoke(null)
        }
    } catch (e: Exception) {
        throw RuntimeException("Failed to get InputManager instance via reflection", e)
    }

    private val vdController = VirtualDisplayController()

    @Volatile
    private var gestureDownTime = 0L

    @Volatile
    private var keepAliveThread: Thread? = null

    @Volatile
    private var keepAliveRunning = false
    private var width = 1080
    private var height = 1920
    private var densityDpi = 480
    private var userId = -1
    override fun ping() = true

    @Synchronized
    override fun downSync(x: Int, y: Int, displayId: Int) {
        gestureDownTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_DOWN, x, y, displayId, gestureDownTime)
    }

    @Synchronized
    override fun moveSync(x: Int, y: Int, displayId: Int) {
        if (gestureDownTime == 0L) gestureDownTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_MOVE, x, y, displayId, gestureDownTime)
    }

    @Synchronized
    override fun upSync(x: Int, y: Int, displayId: Int) {
        if (gestureDownTime == 0L) gestureDownTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_UP, x, y, displayId, gestureDownTime)
        gestureDownTime = 0L
    }

    override fun createVirtualDisplay(
        surface: Surface, width: Int, height: Int, densityDpi: Int
    ): Int {
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        val displayId = vdController.create(surface, width, height, densityDpi)
        if (displayId > 0) {
            startKeepAlive(displayId)
        }
        return displayId
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface) {
        vdController.setSurface(displayId, surface)
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        stopKeepAlive()
        vdController.release(displayId)
    }

    override fun exit() {
        stopKeepAlive()
        vdController.releaseAll()
        android.os.Handler(getMainLooper()).postDelayed({ exitProcess(0) }, 200)
    }

    private fun startKeepAlive(displayId: Int) {
        stopKeepAlive()
        keepAliveRunning = true
        keepAliveThread = Thread {
            while (keepAliveRunning) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) pokePowerManager(
                        displayId
                    )
                    else injectHoverZero(displayId)
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopKeepAlive() {
        keepAliveRunning = false
        keepAliveThread?.interrupt()
        keepAliveThread = null
    }

    private val powerService: Any? by lazy {
        try {
            val binder = ServiceManager.getService("power") ?: return@lazy null
            Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (e: Exception) {
            null
        }
    }
    private val systemContext: android.content.Context by lazy {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = activityThreadClass.getMethod("systemMain").invoke(null)
        activityThreadClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
    }

    private fun pokePowerManager(displayId: Int) {
        try {
            val pm = powerService ?: return
            val userActivityMethod =
                pm.javaClass.methods.firstOrNull { it.name == "userActivity" } ?: return
            userActivityMethod.isAccessible = true

            val now = SystemClock.uptimeMillis()
            if (userActivityMethod.parameterCount == 4) {
                // Android 14+：userActivity(displayId, eventTime, event, flags)
                // event = 0 (WINDOW_TOUCH), flags = 0
                userActivityMethod.invoke(pm, displayId, now, 0, 0)
            } else {
                // Android 13-：userActivity(eventTime, event, flags)
                userActivityMethod.invoke(pm, now, 0, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun injectHoverZero(displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_HOVER_MOVE,
            1,
            arrayOf(
                MotionEvent.PointerProperties()
                    .apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { x = 0f; y = 0f; pressure = 0f; size = 0f }),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
        try {
            doInject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    private fun injectMotion(action: Int, x: Int, y: Int, displayId: Int, downTime: Long) {
        val event = buildMotionEvent(action, x.toFloat(), y.toFloat(), downTime)
        try {
            doInject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    private fun buildMotionEvent(action: Int, x: Float, y: Float, downTime: Long): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            downTime,
            now,
            action,
            1,
            arrayOf(
                MotionEvent.PointerProperties()
                    .apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(
                MotionEvent.PointerCoords()
                    .apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private fun doInject(event: MotionEvent, displayId: Int) {
        trySetDisplayId(event, displayId)
        runCatching {
            val targetInstance = if (android.os.Build.VERSION.SDK_INT >= 34) {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance")
                    .invoke(null)
            } else {
                inputManagerInstance
            }

            val method = targetInstance.javaClass.methods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.size == 2
            } ?: throw NoSuchMethodException("未找到 injectInputEvent 方法")

            method.isAccessible = true
            method.invoke(targetInstance, event, INJECT_MODE_WAIT_FOR_RESULT)
        }.onFailure {
            it.printStackTrace()
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun trySetDisplayId(event: MotionEvent, displayId: Int) {
        runCatching {
            InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(event, displayId)
            return
        }
        runCatching {
            InputEvent::class.java.getDeclaredField("mDisplayId").apply { isAccessible = true }
                .setInt(event, displayId)
        }
    }

    override fun moveAppToDisplay(packageName: String, displayId: Int) {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atmService = atmClass.getMethod("getService").invoke(null)
            val getTasksMethod = atmService.javaClass.methods.firstOrNull { it.name == "getTasks" }
                ?: throw NoSuchMethodException("未找到 getTasks 方法")

            val tasks = when (getTasksMethod.parameterCount) {
                4 -> getTasksMethod.invoke(atmService, 50, false, false, -1)
                3 -> getTasksMethod.invoke(atmService, 50, false, false)
                1 -> getTasksMethod.invoke(atmService, 50)
                else -> throw NoSuchMethodException("未知的 getTasks 参数个数: ${getTasksMethod.parameterCount}")
            } as? List<*> ?: return

            var targetTaskId = -1
            for (task in tasks) {
                if (task == null) continue
                val topActivityField = task.javaClass.getField("topActivity")
                val componentName = topActivityField.get(task) ?: continue

                val getPackageNameMethod = componentName.javaClass.getMethod("getPackageName")
                val pkgName = getPackageNameMethod.invoke(componentName) as String

                if (pkgName == packageName) {
                    val taskIdField = task.javaClass.getField("taskId")
                    targetTaskId = taskIdField.getInt(task)
                    break
                }
            }
            if (targetTaskId != -1) {
                val moveMethod = atmService.javaClass.getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                moveMethod.invoke(atmService, targetTaskId, displayId)
                val intent = getLaunchIntentWithoutContext(packageName,getCurrentUserId())
                if (intent != null) {
                    launchActivityInternal(intent, displayId,getCurrentUserId())
                } else {
                }
            } else {
                val intent = getLaunchIntentWithoutContext(packageName,getCurrentUserId())
                if (intent != null) {
                    launchActivityInternal(intent, displayId, getCurrentUserId())
                } else {
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    private fun getLaunchIntentWithoutContext(
        packageName: String,
        userId: Int
    ): android.content.Intent? {
        try {
            // 直接获取系统的 package 服务
            val pmBinder = ServiceManager.getService("package")
            val pmService = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, pmBinder)

            // 反射获取底层查询方法
            val queryMethod = pmService.javaClass.methods.firstOrNull {
                it.name == "queryIntentActivities"
            } ?: throw NoSuchMethodException("未找到 queryIntentActivities 方法")

            // 构造需要解析的原生 Intent
            val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                `package` = packageName
            }

            val queryParams = queryMethod.parameterTypes
            val queryArgs = arrayOfNulls<Any>(queryParams.size)
            for (i in queryParams.indices) {
                val type = queryParams[i]
                when {
                    type == android.content.Intent::class.java -> queryArgs[i] = launchIntent
                    type == String::class.java -> queryArgs[i] = null
                    type == Long::class.javaPrimitiveType -> queryArgs[i] = 0L
                    type == Int::class.javaPrimitiveType -> queryArgs[i] = userId
                }
            }

            // 执行查询并提取 ResolveInfo 列表
            val parceledListSlice = queryMethod.invoke(pmService, *queryArgs)
            val list = parceledListSlice?.javaClass?.getMethod("getList")
                ?.invoke(parceledListSlice) as? List<*>

            if (!list.isNullOrEmpty()) {
                val resolveInfo = list[0]
                val activityInfoField = resolveInfo!!.javaClass.getField("activityInfo")
                val activityInfo = activityInfoField.get(resolveInfo)

                val nameField = activityInfo.javaClass.getField("name")
                val activityClassName = nameField.get(activityInfo) as String

                val packageNameField = activityInfo.javaClass.getField("packageName")
                val actPkgName = packageNameField.get(activityInfo) as String

                return android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    setClassName(actPkgName, activityClassName)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun launchActivityInternal(
        intent: android.content.Intent,
        displayId: Int,
        userId: Int
    ) {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atmService = atmClass.getMethod("getService").invoke(null)

            val opts = android.app.ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or 0x00200000)

            // 【核心修复】优先查找带有多用户参数的 startActivityAsUser
            var startActivityMethod = atmService.javaClass.methods.firstOrNull {
                it.name == "startActivityAsUser" && it.parameterTypes.size >= 10
            }
            var isAsUser = true

            if (startActivityMethod == null) {
                startActivityMethod = atmService.javaClass.methods.firstOrNull {
                    it.name == "startActivity" && it.parameterTypes.size >= 10
                } ?: throw NoSuchMethodException("未找到 startActivity(AsUser) 方法")
                isAsUser = false
            }

            val paramTypes = startActivityMethod.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)
            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                when {
                    type == android.content.Intent::class.java -> args[i] = intent
                    type == android.os.Bundle::class.java -> args[i] = opts.toBundle()
                    type == String::class.java -> {
                        if (i == 1 || (i == 2 && paramTypes[1] != String::class.java)) {
                            args[i] = "com.android.shell"
                        } else {
                            args[i] = null
                        }
                    }

                    type == Int::class.javaPrimitiveType -> {
                        // 如果是 startActivityAsUser，签名最后的一个 int 就是 userId
                        if (isAsUser && i == paramTypes.size - 1) {
                            args[i] = userId
                        } else {
                            args[i] = 0 // 其他的如 requestCode 填 0 即可
                        }
                    }

                    else -> args[i] = null
                }
            }

            val res = startActivityMethod.invoke(atmService, *args)

        } catch (e: Exception) {
            e.printStackTrace()

            // 【终极兜底防线】如果反射在 Android 17 彻底失效，直接走 am start 命令行强制唤醒！
            try {
                val comp = intent.component?.flattenToString() ?: return
                val cmd = arrayOf(
                    "am",
                    "start",
                    "-n",
                    comp,
                    "--display",
                    displayId.toString(),
                    "--user",
                    userId.toString()
                )
                Runtime.getRuntime().exec(cmd)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("BlockedPrivateApi")
    override fun captureScreen(
        displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float
    ): ParcelFileDescriptor? {
        return try {
            val bitmap = captureLiveDisplay(displayId, width, height)
            val bos = ByteArrayOutputStream(1048576)
            val bytes: ByteArray?
            if (bitmap == null) return null
            bos.use { bos ->
                val originalWidth = bitmap.width
                val originalHeight = bitmap.height
                val targetWidth = (originalWidth * densityDpi / 720)
                val targetHeight = (originalHeight * densityDpi / 720)

                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()

                if (softwareBitmap == null) return null
                val scaledBitmap = createBitmap(targetWidth, targetHeight)
                val kWidth = 1 / 999f * targetWidth
                val kHeight = 1 / 999f * targetHeight

                val canvas = Canvas(scaledBitmap)
                val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(
                    softwareBitmap,
                    null,
                    Rect(0, 0, targetWidth, targetHeight),
                    scalePaint
                )
                softwareBitmap.recycle()
                if (x2 != -1f) {
                    BitmapEditor.drawArrowLine(
                        canvas = canvas,
                        x1 = x1 * kWidth, y1 = y1 * kHeight,
                        x2 = x2 * kWidth, y2 = y2 * kHeight,
                        color = 0x7F7F7F7F,
                        strokeWidth = 10f
                    )
                } else if (x1 != -1f) {
                    BitmapEditor.drawDot(
                        canvas,
                        x1 * kWidth,
                        y1 * kHeight,
                        20f,
                        0x7F7F7F7F
                    )
                }
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                scaledBitmap.recycle()
                bytes = bos.toByteArray()
            }

            if (bytes?.isEmpty() ?: true) return null

            val sharedMemory = SharedMemory.create("screenshot", bytes.size)
            val buffer = sharedMemory.mapReadWrite()
            buffer.put(bytes)
            SharedMemory.unmap(buffer)
            sharedMemory.setProtect(android.system.OsConstants.PROT_READ)
            val getFdDup =
                SharedMemory::class.java.getDeclaredMethod("getFdDup").apply { isAccessible = true }
            val pfd = getFdDup.invoke(sharedMemory) as ParcelFileDescriptor
            sharedMemory.close()
            pfd
        } catch (e: Exception) {
            null
        }
    }

    fun captureLiveDisplay(
        displayId: Int, width: Int, height: Int, virtualDisplay: VirtualDisplay? = null
    ): Bitmap? {
        val sdk = Build.VERSION.SDK_INT

        // Android 14 ~ 17 使用最新的 WMS 架构截屏
        if (sdk >= 34) {
            return captureAndroid14Plus(displayId, width, height)
        }

        if (virtualDisplay != null) {
            val token = getDisplayToken(virtualDisplay)
            if (token != null) {
                return captureByTokenLegacy(token, width, height)
            }
        }
        return captureByLayerStackMirror(displayId, width, height)
    }

    /**
     * 方案一：Android 14+ (含 Android 16/17) WMS 截图深度适配
     */
    private fun captureAndroid14Plus(displayId: Int, width: Int, height: Int): Bitmap? {
        try {
            // 1. 获取 IWindowManager 实例
            val windowBinder = ServiceManager.getService("window")
            val iWindowManagerStub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterfaceMethod = iWindowManagerStub.getDeclaredMethod("asInterface", IBinder::class.java)
            val iWindowManager = asInterfaceMethod.invoke(null, windowBinder)
            val iWindowManagerClass = Class.forName("android.view.IWindowManager")

            // 2. 【核心修复】动态判断：Android 16/17 将 ScreenCapture 迁移并重命名为了 ScreenCaptureInternal
            val (screenCaptureClassName, captureArgsClassName) = try {
                Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs")
                Pair("android.window.ScreenCaptureInternal", "android.window.ScreenCaptureInternal\$CaptureArgs")
            } catch (e: ClassNotFoundException) {
                Pair("android.window.ScreenCapture", "android.window.ScreenCapture\$CaptureArgs")
            }

            // 3. 构建 CaptureArgs 截图参数
            val captureArgsBuilderClass = Class.forName("$captureArgsClassName\$Builder")
            val builder = captureArgsBuilderClass.newInstance()

            val setSourceCropMethod = captureArgsBuilderClass.methods.first { it.name == "setSourceCrop" }
            setSourceCropMethod.invoke(builder, Rect(0, 0, width, height))

            val buildMethod = captureArgsBuilderClass.methods.first { it.name == "build" }
            val captureArgs = buildMethod.invoke(builder)

            // 4. 创建 Listener 监听器
            val screenCaptureClass = Class.forName(screenCaptureClassName)
            val syncListenerMethod = screenCaptureClass.methods.first { it.name == "createSyncCaptureListener" }
            val syncListener = syncListenerMethod.invoke(null)

            // 5. 动态寻找 captureDisplay 方法（应对 Android 17 可能随时新增或变动参数）
            val captureDisplayMethod = iWindowManagerClass.methods.firstOrNull {
                it.name == "captureDisplay" &&
                        it.parameterTypes.size >= 3 &&
                        it.parameterTypes[1].name == captureArgsClassName
            } ?: throw NoSuchMethodException("未找到 captureDisplay 方法")

            // 智能填充参数：前三个参数类型固定，后面的未知参数用默认值补齐
            val args = arrayOfNulls<Any>(captureDisplayMethod.parameterTypes.size)
            args[0] = displayId
            args[1] = captureArgs
            args[2] = syncListener
            for (i in 3 until args.size) {
                val type = captureDisplayMethod.parameterTypes[i]
                args[i] = when (type) {
                    Int::class.javaPrimitiveType -> 0
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }

            captureDisplayMethod.invoke(iWindowManager, *args)

            // 6. 提取截屏 Buffer 数据并转换为 Bitmap
            val getBufferMethod = syncListener.javaClass.methods.first { it.name == "getBuffer" }
            val screenshotBuffer = getBufferMethod.invoke(syncListener)

            if (screenshotBuffer != null) {
                val hwBufferMethod = screenshotBuffer.javaClass.methods.first { it.name == "getHardwareBuffer" }
                val colorSpaceMethod = screenshotBuffer.javaClass.methods.first { it.name == "getColorSpace" }

                val hardwareBuffer = hwBufferMethod.invoke(screenshotBuffer) as HardwareBuffer
                val colorSpace = colorSpaceMethod.invoke(screenshotBuffer) as ColorSpace

                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                hardwareBuffer.close()
                return bitmap
            }
        } catch (e: Exception) {
        }
        return null
    }

    /**
     * 方案二：Android 13及以下，根据 Token 截取
     */
    private fun captureByTokenLegacy(displayToken: IBinder, width: Int, height: Int): Bitmap? {
        val sdk = Build.VERSION.SDK_INT
        try {
            val scClass = Class.forName("android.view.SurfaceControl")
            if (sdk >= Build.VERSION_CODES.S) { // Android 12 (API 31/32)
                val argsClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs")
                val builderClass =
                    Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
                val builder = builderClass.getDeclaredConstructor(IBinder::class.java)
                    .newInstance(displayToken)
                builderClass.getMethod(
                    "setSize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ).invoke(builder, width, height)
                val args = builderClass.getMethod("build").invoke(builder)

                val captureMethod = scClass.getMethod("captureDisplay", argsClass)
                val hdBuffer = captureMethod.invoke(null, args)

                if (hdBuffer != null) {
                    val hardwareBuffer = hdBuffer.javaClass.getMethod("getHardwareBuffer")
                        .invoke(hdBuffer) as HardwareBuffer
                    val colorSpace =
                        hdBuffer.javaClass.getMethod("getColorSpace").invoke(hdBuffer) as ColorSpace
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    hardwareBuffer.close()
                    return bitmap
                }
            } else { // Android 10 / 11
                val screenshotMethod = scClass.getMethod(
                    "screenshot",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                return screenshotMethod.invoke(null, displayToken, width, height) as Bitmap
            }
        } catch (e: Exception) {
        }
        return null
    }

    /**
     * 方案三：Android 12及以下，LayerStack 并行镜像克隆（无损原屏渲染）
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun captureByLayerStackMirror(displayId: Int, width: Int, height: Int): Bitmap? {
        var imageReader: ImageReader? = null
        var mirrorDisplayToken: IBinder? = null
        try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmgInstance = dmgClass.getMethod("getInstance").invoke(null)
            val displayInfo = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(dmgInstance, displayId) ?: return null
            val layerStack =
                displayInfo.javaClass.getDeclaredField("layerStack").getInt(displayInfo)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val scClass = Class.forName("android.view.SurfaceControl")
            val createDisplayMethod = scClass.getDeclaredMethod(
                "createDisplay", String::class.java, Boolean::class.javaPrimitiveType
            )
            mirrorDisplayToken =
                createDisplayMethod.invoke(null, "TmpParallelMirror_$displayId", false) as IBinder
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.newInstance()

            transactionClass.getMethod(
                "setDisplaySurface", IBinder::class.java, android.view.Surface::class.java
            ).invoke(transaction, mirrorDisplayToken, imageReader.surface)
            transactionClass.getMethod(
                "setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType
            ).invoke(transaction, mirrorDisplayToken, layerStack)
            transactionClass.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            ).invoke(
                transaction,
                mirrorDisplayToken,
                0,
                Rect(0, 0, width, height),
                Rect(0, 0, width, height)
            )
            transactionClass.getMethod("apply").invoke(transaction)
            Thread.sleep(40)
            val image = imageReader.acquireLatestImage() ?: return null
            val hardwareBuffer = image.hardwareBuffer ?: return null
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            hardwareBuffer.close()
            image.close()

            return hardwareBitmap
        } catch (e: Exception) {
        } finally {
            try {
                if (mirrorDisplayToken != null) {
                    val scClass = Class.forName("android.view.SurfaceControl")
                    scClass.getMethod("destroyDisplay", IBinder::class.java)
                        .invoke(null, mirrorDisplayToken)
                }
                imageReader?.close()
            } catch (_: Exception) {
            }
        }
        return null
    }

    // sdk 32及以下调用
    @SuppressLint("SoonBlockedPrivateApi")
    private fun getDisplayToken(vd: VirtualDisplay): IBinder? {
        return try {
            val tokenField = VirtualDisplay::class.java.getDeclaredField("mToken")
            tokenField.isAccessible = true
            tokenField.get(vd) as IBinder
        } catch (e: Exception) {
            null
        }
    }

    private fun getCurrentUserId(): Int {
        if (userId!=-1)return userId
        userId =try {
            val amClass = Class.forName("android.app.ActivityManager")
            amClass.getMethod("getCurrentUser").invoke(null) as Int
        } catch (_: Exception) {
            0
        }
        return userId
    }
}