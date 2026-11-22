package com.xjyzs.baselineprofile

import android.app.UiAutomation
import android.content.ComponentName
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
 *    删除测试会话收尾、查看虚拟屏、悬浮窗展开与滑动（并拖边收起）、设置页（虚拟屏开关与应用选择器）、
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
            waitIdle()
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

            // 1. 主界面关键路径（兼容 Pad 常驻左栏与手机 Modal 抽屉；发送“打开浏览器”并在测试后删除该会话；查看虚拟屏）
            exerciseMainScreenIfPresent()

            // 2. 在主界面阶段展开并滑动悬浮窗，测试完拖回边缘收起（避免在设置页展开后遮挡并卡死 waitForIdle）
            exerciseFloatingOverlayIfPresent()

            // 3. 设置页：滚动、虚拟屏开关、悬浮窗开关与应用选择器弹窗
            exerciseConfigScreen()

            // 4. 权限与模式引导页：包含 Root / Shizuku 分段按钮精准切换
            exerciseWelcomeScreen()
        }
    }

    /**
     * 使用带超时的 waitForIdle，避免 Compose 动画/光标/悬浮窗导致默认 10s 卡死。
     */
    private fun MacrobenchmarkScope.waitIdle(timeoutMs: Long = 600L) {
        device.waitForIdle(timeoutMs)
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
                waitIdle()
            }
        }

        // 点击“新对话”按钮
        device.findObject(
            By.pkg(packageName).desc(Pattern.compile("新对话|New Conversation"))
        )?.let { newSessionBtn ->
            newSessionBtn.click()
            waitIdle()
        }

        // 滚动会话历史列表
        findScrollables().firstOrNull()?.let { sessionList ->
            runCatching {
                sessionList.scroll(Direction.DOWN, 0.5f)
                waitIdle()
                sessionList.scroll(Direction.UP, 0.5f)
            }
            waitIdle()
        }

        // 如果在手机上抽屉未自动关闭，按返回键关闭抽屉；Pad 上是常驻抽屉，千万不能按 Back
        if (!isPadPermanentDrawer) {
            val drawerVisible = device.hasObject(
                By.pkg(packageName).text(Pattern.compile("对话记录|Chat History"))
            )
            if (drawerVisible) {
                device.pressBack()
                waitIdle()
            }
        }

        if (hasAccessibilityDialog()) return

        // 2. 发送消息："打开浏览器"，等待5秒如果还在运行就停止
        val inputField = device.findObject(
            By.pkg(packageName).text(Pattern.compile("输入指令\\.\\.\\.|Enter command\\.\\.\\."))
        ) ?: device.findObject(By.pkg(packageName).clazz("android.widget.EditText"))

        if (inputField != null) {
            inputField.click()
            waitIdle()

            val focusedInput = device.findObject(By.pkg(packageName).focused(true)) ?: inputField
            runCatching { focusedInput.text = "打开浏览器" }
            waitIdle()

            // 点击发送按钮
            device.findObject(By.pkg(packageName).desc(Pattern.compile("发送|Send")))?.click()
            waitIdle()

            // 等待5秒，如果还在运行就停止
            SystemClock.sleep(5_000L)
            waitIdle()

            val stopBtn = device.findObject(By.pkg(packageName).desc(Pattern.compile("停止|Stop")))
            if (stopBtn != null) {
                stopBtn.click()
                waitIdle()
                // 等待恢复 STOP 状态（发送按钮重新出现）
                device.wait(
                    Until.hasObject(By.pkg(packageName).desc(Pattern.compile("发送|Send"))),
                    5_000L
                )
                waitIdle()
            }
        }

        // 3. 滑动主界面的消息列表
        findScrollables().lastOrNull()?.let { messageList ->
            runCatching {
                messageList.scroll(Direction.DOWN, 0.8f)
                waitIdle()
                messageList.scroll(Direction.UP, 0.8f)
            }
            waitIdle()
        }

        // 4. 删除测试时创建的“打开浏览器”会话，恢复干净的会话列表
        deleteCurrentBrowserTestSession(isPadPermanentDrawer)

        // 5. 右侧抽屉：控制、跳转“查看虚拟屏”并滑动右侧抽屉，然后关闭抽屉留在主界面
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
                waitIdle()

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
                    waitIdle()
                    SystemClock.sleep(1_000L)
                    // 退出虚拟屏预览返回主界面
                    device.pressBack()
                    waitIdle()

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
                    waitIdle()
                }

                // 滑动右侧抽屉
                findScrollable()?.let { drawerScroll ->
                    runCatching {
                        drawerScroll.scroll(Direction.DOWN, 0.7f)
                        waitIdle()
                        drawerScroll.scroll(Direction.UP, 0.7f)
                    }
                    waitIdle()
                }

                // 关闭右侧抽屉，留在主界面准备测试悬浮窗
                if (device.hasObject(
                        By.pkg(packageName).text(Pattern.compile("远程管理|Tokens|控制|Control"))
                    )
                ) {
                    device.pressBack()
                    waitIdle()
                }
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
            waitIdle()
        }

        // 寻找包含“打开浏览器”的会话条目
        val sessionItem = device.findObject(
            By.pkg(packageName).text(Pattern.compile(".*打开浏览器.*"))
        )

        if (sessionItem != null) {
            // 长按会话条目呼出操作菜单
            runCatching { sessionItem.click(1_200L) }
            waitIdle()

            // 点击菜单中的“删除”
            val menuDeleteBtn = device.wait(
                Until.findObject(By.pkg(packageName).text(Pattern.compile("删除|Delete"))),
                2_000L
            )
            if (menuDeleteBtn != null) {
                menuDeleteBtn.click()
                waitIdle()

                // 点击二次确认对话框中的“删除”
                val confirmDeleteBtn = device.wait(
                    Until.findObject(By.pkg(packageName).text(Pattern.compile("删除|Delete"))),
                    2_000L
                )
                confirmDeleteBtn?.click()
                waitIdle()
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
                waitIdle()
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
            val handle = device.wait(
                Until.findObject(By.desc("floating_window_handle")),
                2_000L
            ) ?: return
            waitIdle()

            // 仅滑动悬浮窗内部的消息列表，避免误触背景或抢占输入法焦点
            device.findObject(By.desc("floating_message_list"))?.let { overlayMessageList ->
                runCatching {
                    overlayMessageList.scroll(Direction.DOWN, 0.5f)
                    waitIdle()
                    overlayMessageList.scroll(Direction.UP, 0.5f)
                }
                waitIdle()
            }

            // 通过顶部把手将悬浮窗卡片拖到屏幕边缘收起（distToEdge < 60dp 时触发 isMinimized = true）
            repeat(2) { attempt ->
                val currentHandle = device.findObject(By.desc("floating_window_handle")) ?: return
                val rect = currentHandle.visibleBounds
                val handleY = rect.centerY()
                if (attempt == 0) {
                    val startX = (rect.centerX() + rect.width() / 4).coerceAtMost(rect.right - 20)
                    device.swipe(startX, handleY, 10, handleY, 30)
                } else {
                    val startX = (rect.centerX() - rect.width() / 4).coerceAtLeast(rect.left + 20)
                    device.swipe(startX, handleY, device.displayWidth - 10, handleY, 30)
                }
                SystemClock.sleep(400L)
                waitIdle()
                if (!device.hasObject(By.desc("floating_window_handle"))) {
                    return
                }
            }
        }
    }

    private fun MacrobenchmarkScope.exerciseConfigScreen() {
        val intent = Intent().apply {
            component = ComponentName(packageName, "$packageName.ConfigActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndWait(intent)

        // 等待设置页首屏顶部元素出现（不能等屏幕底部的 "API Base URL"，以免在小屏下未滚动前误判为未打开而直接 return）
        val configLoaded = device.wait(
            Until.hasObject(
                By.pkg(packageName).text(Pattern.compile("通用|悬浮窗|虚拟屏|Virtual Screen"))
            ),
            3_000L
        )
        if (!configLoaded) return
        waitIdle()

        // 1. 切换“虚拟屏”开关并立即恢复，覆盖 setVirtualDisplayEnabled 与重组
        device.findObject(
            By.pkg(packageName).text(Pattern.compile("虚拟屏|Virtual Screen"))
        )?.let { virtualScreenRow ->
            virtualScreenRow.click()
            // 等待后台 releaseVirtualDisplay() 完成；若虚拟屏内有刚打开的浏览器，系统会将其迁回主屏盖住当前页面
            SystemClock.sleep(1_000L)
            waitIdle()
            ensureConfigActivityForeground()

            device.findObject(
                By.pkg(packageName).text(Pattern.compile("虚拟屏|Virtual Screen"))
            )?.click()
            SystemClock.sleep(600L)
            waitIdle()
            ensureConfigActivityForeground()
        }

        // 2. 切换“悬浮窗”开关并立即恢复原状
        device.findObject(By.pkg(packageName).text("悬浮窗"))?.let { overlayRow ->
            overlayRow.click()
            SystemClock.sleep(300L)
            waitIdle()
            device.findObject(By.pkg(packageName).text("悬浮窗"))?.click()
            SystemClock.sleep(300L)
            waitIdle()
        }

        // 3. 滚动设置列表，覆盖 LargeFlexibleTopAppBar 折叠及表单渲染
        findScrollable()?.let { scrollable ->
            runCatching {
                scrollable.scroll(Direction.DOWN, 0.8f)
                waitIdle()
                scrollable.scroll(Direction.UP, 0.8f)
            }
            waitIdle()
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
                waitIdle()
                findScrollables().lastOrNull()?.let { appList ->
                    runCatching {
                        appList.scroll(Direction.DOWN, 0.8f)
                        waitIdle()
                        appList.scroll(Direction.UP, 0.8f)
                    }
                    waitIdle()
                }
                // 在弹窗内的独立搜索框中输入与清空
                runCatching {
                    searchBox.text = "a"
                    waitIdle()
                    searchBox.text = ""
                    waitIdle()
                }
                // 关闭弹窗
                device.pressBack()
                waitIdle()
                device.wait(
                    Until.gone(By.pkg(packageName).desc("app_search_input")),
                    2_000L
                )
                waitIdle()
            }
        }
    }

    private fun MacrobenchmarkScope.ensureConfigActivityForeground() {
        val isConfigVisible = device.currentPackageName == packageName && device.hasObject(
            By.pkg(packageName).text(Pattern.compile("通用|悬浮窗|虚拟屏|Virtual Screen"))
        )
        if (!isConfigVisible) {
            val bringBackIntent = Intent().apply {
                component = ComponentName(packageName, "$packageName.ConfigActivity")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivityAndWait(bringBackIntent)
            device.wait(
                Until.hasObject(
                    By.pkg(packageName).text(Pattern.compile("通用|悬浮窗|虚拟屏|Virtual Screen"))
                ),
                3_000L
            )
            waitIdle()
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
        waitIdle()

        // 重点：在尚未滚动页面之前立即操作 Root / Shizuku 分段按钮，保证位于可视区域顶部！
        // 针对用户在 Root 模式下需要切实触发并点到 Shizuku 的诉求：
        val shizukuBtn = device.findObject(By.pkg(packageName).text("Shizuku"))
        val rootBtn = device.findObject(By.pkg(packageName).text("Root"))

        if (shizukuBtn != null && rootBtn != null) {
            // 先点击 Shizuku，触发 Shizuku 路径和检测流程
            val clickableShizuku = shizukuBtn.parent?.takeIf { it.isClickable } ?: shizukuBtn
            clickableShizuku.click()
            waitIdle()
            // 停留 1.5 秒确保 Shizuku 检测协程充分执行并记录代码路径
            SystemClock.sleep(1_500L)
            waitIdle()

            // 再切回 Root 按钮，保证不改变用户原本的 Root 模式偏好设置
            val currentRootBtn = device.findObject(By.pkg(packageName).text("Root")) ?: rootBtn
            val clickableRoot = currentRootBtn.parent?.takeIf { it.isClickable } ?: currentRootBtn
            clickableRoot.click()
            waitIdle()
            SystemClock.sleep(1_500L)
            waitIdle()
        }

        // 滚动权限列表
        findScrollable()?.let { scrollable ->
            runCatching {
                scrollable.scroll(Direction.DOWN, 0.8f)
                waitIdle()
                scrollable.scroll(Direction.UP, 0.8f)
            }
            waitIdle()
        }
    }
}
