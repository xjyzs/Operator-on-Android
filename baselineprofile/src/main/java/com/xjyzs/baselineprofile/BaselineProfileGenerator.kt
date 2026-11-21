package com.xjyzs.baselineprofile

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * 为 Operator on Android 生成合理的 Baseline Profile 与 Startup Profile。
 *
 * 拆分为两个测试：
 * 1. [generateStartupProfile] (`includeInStartupProfile = true`)：
 *    仅覆盖冷启动关键路径，配合 `dexLayoutOptimization = true` 将启动期类/方法放入主 DEX，
 *    避免次级页面代码膨胀主 DEX、影响冷启动速度。
 * 2. [generateUserJourneys] (`includeInStartupProfile = false`)：
 *    覆盖主界面（Pad 常驻左栏 / 手机抽屉）、新建对话并发送“打开浏览器”（5秒后停止）、消息列表滚动、
 *    删除测试会话收尾、悬浮窗展开与滑动、查看虚拟屏、设置页（虚拟屏开关与应用选择器）、
 *    以及欢迎页 Root / Shizuku 分段按钮精准切换等完整用户旅程。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    companion object {
        init {
            Configurator.getInstance().uiAutomationFlags =
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        }
    }

    @get:Rule
    val rule = BaselineProfileRule()

    private val targetPackageName: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: throw IllegalStateException("targetAppId not passed as instrumentation runner arg")

    @Test
    fun generateStartupProfile() {
        rule.collect(
            packageName = targetPackageName,
            includeInStartupProfile = true,
            maxIterations = 10,
            stableIterations = 3,
        ) {
            pressHome()
            startActivityAndWait()
            waitForInitialScreen()
            device.waitForIdle()
        }
    }

    @Test
    fun generateUserJourneys() {
        rule.collect(
            packageName = targetPackageName,
            includeInStartupProfile = false,
            maxIterations = 10,
            stableIterations = 3,
        ) {
            pressHome()
            startActivityAndWait()
            waitForInitialScreen()

            // 1. 主界面关键路径（兼容 Pad 常驻左栏与手机 Modal 抽屉；发送“打开浏览器”并在测试后删除该会话）
            exerciseMainScreenIfPresent()

            // 2. 悬浮窗展开与滑动消息列表
            exerciseFloatingOverlayIfPresent()

            // 3. 设置页：滚动、虚拟屏开关、悬浮窗开关与应用选择器弹窗（带 API URL 防清空保护）
            exerciseConfigScreen()

            // 4. 权限与模式引导页：包含 Root / Shizuku 分段按钮精准切换
            exerciseWelcomeScreen()
        }
    }

    private fun MacrobenchmarkScope.waitForInitialScreen() {
        device.wait(
            Until.hasObject(
                By.pkg(packageName).text(
                    Pattern.compile("输入指令\\.\\.\\.|Enter command\\.\\.\\.|欢迎使用 Operator|Welcome to Operator")
                )
            ),
            5_000L
        )
    }

    private fun MacrobenchmarkScope.hasAccessibilityDialog(): Boolean {
        return device.hasObject(
            By.pkg(packageName).text(
                Pattern.compile(".*无障碍权限.*|.*accessibility permission.*")
            )
        )
    }

    private fun MacrobenchmarkScope.findScrollable(): UiObject2? {
        return device.findObjects(By.pkg(packageName).scrollable(true)).lastOrNull()
    }

    private fun MacrobenchmarkScope.findScrollables(): List<UiObject2> {
        return device.findObjects(By.pkg(packageName).scrollable(true))
    }

    private fun MacrobenchmarkScope.exerciseMainScreenIfPresent() {
        val isOnMain = device.hasObject(
            By.pkg(packageName).text(Pattern.compile("输入指令\\.\\.\\.|Enter command\\.\\.\\."))
        ) || device.hasObject(
            By.pkg(packageName)
                .desc(Pattern.compile("对话记录|Chat History|新对话|New Conversation"))
        )
        if (!isOnMain || hasAccessibilityDialog()) return

        // 1. 处理左侧对话记录与“新对话”
        // 在 Pad (宽屏 >= 500dp) 上，左栏是 PermanentNavigationDrawer，默认直接常驻展示，顶部导航栏没有 Menu 按钮；
        // 在手机上，左栏是 ModalNavigationDrawer，需要点击左上角 Menu 展开。
        val isPadPermanentDrawer = device.hasObject(
            By.pkg(packageName).desc(Pattern.compile("新对话|New Conversation"))
        ) && !device.hasObject(
            By.pkg(packageName).desc(Pattern.compile("对话记录|Chat History"))
        )

        if (!isPadPermanentDrawer) {
            // 手机模式：点击菜单图标展开抽屉
            device.findObject(
                By.pkg(packageName).desc(Pattern.compile("对话记录|Chat History"))
            )?.let { menuBtn ->
                menuBtn.click()
                device.wait(
                    Until.hasObject(
                        By.pkg(packageName).desc(Pattern.compile("新对话|New Conversation"))
                    ),
                    2_000L
                )
                device.waitForIdle()
            }
        }

        // 点击“新对话”按钮
        device.findObject(
            By.pkg(packageName).desc(Pattern.compile("新对话|New Conversation"))
        )?.let { newSessionBtn ->
            newSessionBtn.click()
            device.waitForIdle()
        }

        // 滚动会话历史列表
        findScrollables().firstOrNull()?.let { sessionList ->
            runCatching {
                sessionList.scroll(Direction.DOWN, 0.5f)
                device.waitForIdle()
                sessionList.scroll(Direction.UP, 0.5f)
            }
            device.waitForIdle()
        }

        // 如果在手机上抽屉未自动关闭，按返回键关闭抽屉；Pad 上是常驻抽屉，千万不能按 Back
        if (!isPadPermanentDrawer) {
            val drawerVisible = device.hasObject(
                By.pkg(packageName).text(Pattern.compile("对话记录|Chat History"))
            )
            if (drawerVisible) {
                device.pressBack()
                device.waitForIdle()
            }
        }

        if (hasAccessibilityDialog()) return

        // 2. 发送消息："打开浏览器"，等待5秒如果还在运行就停止
        val inputField = device.findObject(
            By.pkg(packageName).text(Pattern.compile("输入指令\\.\\.\\.|Enter command\\.\\.\\."))
        ) ?: device.findObject(By.pkg(packageName).clazz("android.widget.EditText"))

        if (inputField != null) {
            inputField.click()
            device.waitForIdle()

            val focusedInput = device.findObject(By.pkg(packageName).focused(true)) ?: inputField
            focusedInput.text = "打开浏览器"
            device.waitForIdle()

            // 点击发送按钮
            device.findObject(By.pkg(packageName).desc(Pattern.compile("发送|Send")))?.click()
            device.waitForIdle()

            // 等待5秒，如果还在运行就停止
            SystemClock.sleep(5_000L)
            device.waitForIdle()

            val stopBtn = device.findObject(By.pkg(packageName).desc(Pattern.compile("停止|Stop")))
            if (stopBtn != null) {
                stopBtn.click()
                device.waitForIdle()
                // 等待恢复 STOP 状态（发送按钮重新出现）
                device.wait(
                    Until.hasObject(By.pkg(packageName).desc(Pattern.compile("发送|Send"))),
                    5_000L
                )
                device.waitForIdle()
            }
        }

        // 3. 滑动主界面的消息列表
        findScrollables().lastOrNull()?.let { messageList ->
            runCatching {
                messageList.scroll(Direction.DOWN, 0.8f)
                device.waitForIdle()
                messageList.scroll(Direction.UP, 0.8f)
            }
            device.waitForIdle()
        }

        // 4. 删除测试时创建的“打开浏览器”会话，恢复干净的会话列表
        deleteCurrentBrowserTestSession(isPadPermanentDrawer)

        // 5. 右侧抽屉：控制、查看虚拟屏与跳转设置
        device.findObject(
            By.pkg(packageName).desc(Pattern.compile("设置|Settings"))
        )?.let { openRightDrawerBtn ->
            openRightDrawerBtn.click()
            val rightDrawerOpened = device.wait(
                Until.hasObject(
                    By.pkg(packageName).text(Pattern.compile("远程管理|Tokens|控制|Control"))
                ),
                2_000L
            )
            if (rightDrawerOpened) {
                device.waitForIdle()

                // 跳转“查看虚拟屏”并返回
                val viewVirtualScreenBtn = device.findObject(
                    By.pkg(packageName).text(Pattern.compile("查看虚拟屏|View Virtual Screen"))
                )
                if (viewVirtualScreenBtn != null) {
                    viewVirtualScreenBtn.click()
                    // 等待 FullscreenVirtualScreen 预览展示
                    device.wait(
                        Until.hasObject(By.pkg(packageName).clazz("android.view.SurfaceView")),
                        3_000L
                    )
                    device.waitForIdle()
                    SystemClock.sleep(1_000L)
                    // 退出虚拟屏预览返回主界面
                    device.pressBack()
                    device.waitForIdle()

                    // 重新打开右侧抽屉
                    device.findObject(
                        By.pkg(packageName).desc(Pattern.compile("设置|Settings"))
                    )?.click()
                    device.wait(
                        Until.hasObject(
                            By.pkg(packageName)
                                .text(Pattern.compile("远程管理|Tokens|控制|Control"))
                        ),
                        2_000L
                    )
                    device.waitForIdle()
                }

                // 滑动右侧抽屉
                findScrollable()?.let { drawerScroll ->
                    runCatching {
                        drawerScroll.scroll(Direction.DOWN, 0.7f)
                        drawerScroll.scroll(Direction.UP, 0.7f)
                    }
                    device.waitForIdle()
                }

                // 点击右侧抽屉底部的设置齿轮图标，进入 ConfigActivity
                device.findObjects(
                    By.pkg(packageName).desc(Pattern.compile("设置|Settings"))
                ).lastOrNull()?.click()
                device.waitForIdle()
            }
        }
    }

    /**
     * 删除测试时使用的“打开浏览器”会话：长按该会话条目 -> 点击菜单“删除” -> 弹窗确认“删除”。
     */
    private fun MacrobenchmarkScope.deleteCurrentBrowserTestSession(isPadPermanentDrawer: Boolean) {
        if (!isPadPermanentDrawer) {
            // 手机模式下重新打开左侧抽屉
            device.findObject(
                By.pkg(packageName).desc(Pattern.compile("对话记录|Chat History"))
            )?.click()
            device.wait(
                Until.hasObject(By.pkg(packageName).text(Pattern.compile("对话记录|Chat History"))),
                2_000L
            )
            device.waitForIdle()
        }

        // 寻找包含“打开浏览器”的会话条目
        val sessionItem = device.findObject(
            By.pkg(packageName).text(Pattern.compile(".*打开浏览器.*"))
        )

        if (sessionItem != null) {
            // 长按会话条目呼出操作菜单
            runCatching { sessionItem.click(1_200L) }
            device.waitForIdle()

            // 点击菜单中的“删除”
            val menuDeleteBtn = device.wait(
                Until.findObject(By.pkg(packageName).text(Pattern.compile("删除|Delete"))),
                2_000L
            )
            if (menuDeleteBtn != null) {
                menuDeleteBtn.click()
                device.waitForIdle()

                // 点击二次确认对话框中的“删除”
                val confirmDeleteBtn = device.wait(
                    Until.findObject(By.pkg(packageName).text(Pattern.compile("删除|Delete"))),
                    2_000L
                )
                confirmDeleteBtn?.click()
                device.waitForIdle()
                SystemClock.sleep(500L)
            }
        }

        if (!isPadPermanentDrawer) {
            // 手机模式下按返回键关闭左侧抽屉
            if (device.hasObject(
                    By.pkg(packageName).text(Pattern.compile("对话记录|Chat History"))
                )
            ) {
                device.pressBack()
                device.waitForIdle()
            }
        }
    }

    private fun MacrobenchmarkScope.exerciseFloatingOverlayIfPresent() {
        // 查找最小化的悬浮窗悬浮球
        val floatingBall = device.findObject(
            By.desc(Pattern.compile("悬浮窗|Floating Window"))
        ) ?: device.findObject(By.pkg(packageName).desc(Pattern.compile("悬浮窗|Floating Window")))

        if (floatingBall != null) {
            // 点击展开悬浮窗卡片
            floatingBall.click()
            device.waitForIdle()
            SystemClock.sleep(500L)

            // 悬浮窗展开后内部有一个 LazyColumn 消息列表
            findScrollables().lastOrNull()?.let { overlayMessageList ->
                runCatching {
                    overlayMessageList.scroll(Direction.DOWN, 0.6f)
                    device.waitForIdle()
                    overlayMessageList.scroll(Direction.UP, 0.6f)
                }
                device.waitForIdle()
            }

            device.waitForIdle()
        }
    }

    // 4. 打开“不尝试获取布局的应用”选择器弹窗，覆盖包列表加载、Drawable->ImageBitmap 与 LazyColumn 滚动
    private fun MacrobenchmarkScope.exerciseConfigScreen() {
        // 保存进入前的 API 配置，作为双重保险，绝不允许被意外抹除
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val apiPref = targetContext.getSharedPreferences("api", Context.MODE_PRIVATE)
        val savedApiUrl = apiPref.getString("apiUrl", "") ?: ""
        val savedApiKey = apiPref.getString("apiKey", "") ?: ""
        val savedModel = apiPref.getString("model", "") ?: ""

        try {
            val alreadyInConfig = device.wait(
                Until.hasObject(By.pkg(packageName).text("API Base URL")),
                1_500L
            )
            if (!alreadyInConfig) {
                val intent = Intent().apply {
                    component = ComponentName(packageName, "$packageName.ConfigActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivityAndWait(intent)
            }

            if (!device.wait(Until.hasObject(By.pkg(packageName).text("API Base URL")), 3_000L)) {
                return
            }
            device.waitForIdle()

            // 1. 切换“虚拟屏”开关并立即恢复，覆盖 setVirtualDisplayEnabled 与重组
            device.findObject(
                By.pkg(packageName).text(Pattern.compile("虚拟屏|Virtual Screen"))
            )?.let { virtualScreenRow ->
                virtualScreenRow.click()
                device.waitForIdle()
                SystemClock.sleep(500L)
                device.findObject(
                    By.pkg(packageName).text(Pattern.compile("虚拟屏|Virtual Screen"))
                )?.click()
                device.waitForIdle()
                SystemClock.sleep(500L)
            }

            // 2. 切换“悬浮窗”开关并立即恢复原状
            device.findObject(By.pkg(packageName).text("悬浮窗"))?.let { overlayRow ->
                overlayRow.click()
                device.waitForIdle()
                SystemClock.sleep(300L)
                device.findObject(By.pkg(packageName).text("悬浮窗"))?.click()
                device.waitForIdle()
                SystemClock.sleep(300L)
            }

            // 3. 滚动设置列表，覆盖 LargeFlexibleTopAppBar 折叠及表单渲染
            findScrollable()?.let { scrollable ->
                runCatching {
                    scrollable.scroll(Direction.DOWN, 0.8f)
                    device.waitForIdle()
                    scrollable.scroll(Direction.UP, 0.8f)
                }
                device.waitForIdle()
            }

            // 4. 打开“不尝试获取布局的应用”选择器弹窗，覆盖包列表加载、Drawable->ImageBitmap 与 LazyColumn 滚动
            // 注意：仅通过专用语义 "app_search_input" 精确交互弹窗内部搜索框，严禁使用通用的 EditText 匹配，防止误碰背景中的 API URL！
            device.findObject(By.pkg(packageName).text("不尝试获取布局的应用"))?.let { entry ->
                entry.click()
                val searchBox = device.wait(
                    Until.findObject(By.pkg(packageName).desc("app_search_input")),
                    4_000L
                )
                if (searchBox != null) {
                    device.waitForIdle()
                    findScrollables().lastOrNull()?.let { appList ->
                        runCatching {
                            appList.scroll(Direction.DOWN, 0.8f)
                            device.waitForIdle()
                            appList.scroll(Direction.UP, 0.8f)
                        }
                        device.waitForIdle()
                    }
                    // 在弹窗内的独立搜索框中输入与清空
                    runCatching {
                        searchBox.text = "a"
                        device.waitForIdle()
                        searchBox.text = ""
                        device.waitForIdle()
                    }
                    // 关闭弹窗
                    device.pressBack()
                    device.waitForIdle()
                    device.wait(
                        Until.gone(By.pkg(packageName).desc("app_search_input")),
                        2_000L
                    )
                    device.waitForIdle()
                }
            }
        } finally {
            // 双重安全防护：若发生任何意外改动，自动恢复初始配置
            if (apiPref.getString("apiUrl", "") != savedApiUrl) {
                apiPref.edit().putString("apiUrl", savedApiUrl).apply()
            }
            if (apiPref.getString("apiKey", "") != savedApiKey) {
                apiPref.edit().putString("apiKey", savedApiKey).apply()
            }
            if (apiPref.getString("model", "") != savedModel) {
                apiPref.edit().putString("model", savedModel).apply()
            }
        }
    }

    private fun MacrobenchmarkScope.exerciseWelcomeScreen() {
        val intent = Intent().apply {
            component = ComponentName(packageName, "$packageName.WelcomeActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndWait(intent)

        val loaded = device.wait(
            Until.hasObject(
                By.pkg(packageName).text(Pattern.compile("欢迎使用 Operator|Welcome to Operator"))
            ),
            3_000L
        )
        if (!loaded) return
        device.waitForIdle()

        // 重点：在尚未滚动页面之前立即操作 Root / Shizuku 分段按钮，保证位于可视区域顶部！
        // 针对用户在 Root 模式下需要切实触发并点到 Shizuku 的诉求：
        val shizukuBtn = device.findObject(By.pkg(packageName).text("Shizuku"))
        val rootBtn = device.findObject(By.pkg(packageName).text("Root"))

        if (shizukuBtn != null && rootBtn != null) {
            // 先点击 Shizuku，触发 Shizuku 路径和检测流程
            val clickableShizuku = shizukuBtn.parent?.takeIf { it.isClickable } ?: shizukuBtn
            clickableShizuku.click()
            device.waitForIdle()
            // 停留 1.5 秒确保 Shizuku 检测协程充分执行并记录代码路径
            SystemClock.sleep(1_500L)
            device.waitForIdle()

            // 再切回 Root 按钮，保证不改变用户原本的 Root 模式偏好设置
            val currentRootBtn = device.findObject(By.pkg(packageName).text("Root")) ?: rootBtn
            val clickableRoot = currentRootBtn.parent?.takeIf { it.isClickable } ?: currentRootBtn
            clickableRoot.click()
            device.waitForIdle()
            SystemClock.sleep(1_500L)
            device.waitForIdle()
        }

        // 滚动权限列表
        findScrollable()?.let { scrollable ->
            runCatching {
                scrollable.scroll(Direction.DOWN, 0.8f)
                device.waitForIdle()
                scrollable.scroll(Direction.UP, 0.8f)
            }
            device.waitForIdle()
        }
    }
}
