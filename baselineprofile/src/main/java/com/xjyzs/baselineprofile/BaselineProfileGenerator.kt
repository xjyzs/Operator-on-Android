package com.xjyzs.baselineprofile

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
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
 *    避免次级页面代码膨胀主 DEX、影响冷启动速度
 * 2. [generateUserJourneys] (`includeInStartupProfile = false`)：
 *    覆盖主界面、左右抽屉、设置页、应用选择器弹窗以及权限引导页的完整交互路径，
 *    同时兼容已授权 Root/Shizuku（停留 MainActivity）与未授权（跳转 WelcomeActivity）两种设备环境
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

            // 1. 主界面关键路径（若当前设备具备 Root/Shizuku 权限且停留在 MainActivity）
            exerciseMainScreenIfPresent()

            // 2. 设置页与安装应用选择器弹窗
            exerciseConfigScreen()

            // 3. 权限引导页
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

    private fun MacrobenchmarkScope.exerciseMainScreenIfPresent() {
        val isOnMain = device.hasObject(
            By.pkg(packageName).text(Pattern.compile("输入指令\\.\\.\\.|Enter command\\.\\.\\."))
        ) || device.hasObject(
            By.pkg(packageName).desc(Pattern.compile("对话记录|Chat History"))
        )
        if (!isOnMain || hasAccessibilityDialog()) return

        // 左侧边栏（对话记录）
        val menuBtn = device.findObject(
            By.pkg(packageName).desc(Pattern.compile("对话记录|Chat History"))
        )
        val isModalLeftDrawer = menuBtn != null
        if (menuBtn != null && !hasAccessibilityDialog()) {
            menuBtn.click()
            device.wait(
                Until.hasObject(By.pkg(packageName).desc(Pattern.compile("新对话|New Conversation"))),
                2_000L
            )
            device.waitForIdle()
        }

        if (!hasAccessibilityDialog()) {
            device.findObject(
                By.pkg(packageName).desc(Pattern.compile("新对话|New Conversation"))
            )?.let { newSessionBtn ->
                newSessionBtn.click()
                device.waitForIdle()
            }

            findScrollable()?.let { list ->
                runCatching {
                    list.scroll(Direction.DOWN, 0.5f)
                    list.scroll(Direction.UP, 0.5f)
                }
                device.waitForIdle()
            }
        }

        if (isModalLeftDrawer && !hasAccessibilityDialog()) {
            device.pressBack()
            device.waitForIdle()
        }

        // 底部指令输入框（BasicTextField + imePadding + 软键盘路径，不点击发送）
        if (!hasAccessibilityDialog()) {
            device.findObject(
                By.pkg(packageName).text(Pattern.compile("输入指令\\.\\.\\.|Enter command\\.\\.\\."))
            )?.let { inputHint ->
                inputHint.click()
                device.waitForIdle()
                runCatching {
                    device.findObject(By.pkg(packageName).focused(true))?.let { focusedInput ->
                        focusedInput.text = "ping"
                        device.waitForIdle()
                        focusedInput.text = ""
                        device.waitForIdle()
                    }
                }
                if (!hasAccessibilityDialog()) {
                    device.pressBack()
                    device.waitForIdle()
                }
            }
        }

        // 右侧边栏（控制 / Tokens / 远程管理 -> 进入设置页）
        if (!hasAccessibilityDialog()) {
            device.findObject(
                By.pkg(packageName).desc(Pattern.compile("设置|Settings"))
            )?.let { openRightDrawerBtn ->
                openRightDrawerBtn.click()
                val rightDrawerOpened = device.wait(
                    Until.hasObject(By.pkg(packageName).text(Pattern.compile("远程管理|Tokens"))),
                    2_000L
                )
                if (rightDrawerOpened) {
                    device.waitForIdle()
                    findScrollable()?.let { drawerScroll ->
                        runCatching {
                            drawerScroll.scroll(Direction.DOWN, 0.7f)
                            drawerScroll.scroll(Direction.UP, 0.7f)
                        }
                        device.waitForIdle()
                    }
                    // 右侧边栏底部的设置按钮
                    device.findObjects(
                        By.pkg(packageName).desc(Pattern.compile("设置|Settings"))
                    ).lastOrNull()?.click()
                    device.waitForIdle()
                }
            }
        }
    }

    private fun MacrobenchmarkScope.exerciseConfigScreen() {
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

        // 滚动设置列表，覆盖 LargeFlexibleTopAppBar 折叠及表单渲染
        findScrollable()?.let { scrollable ->
            runCatching {
                scrollable.scroll(Direction.DOWN, 0.8f)
                device.waitForIdle()
                scrollable.scroll(Direction.UP, 0.8f)
            }
            device.waitForIdle()
        }

        // 切换“悬浮窗”开关并立即恢复原状，覆盖 SwitchRow 与 AnimatedVisibility 动画重组
        device.findObject(By.pkg(packageName).text("悬浮窗"))?.let { overlayRow ->
            overlayRow.click()
            device.waitForIdle()
            device.findObject(By.pkg(packageName).text("悬浮窗"))?.click()
            device.waitForIdle()
        }

        // 打开“不尝试获取布局的应用”选择器弹窗，覆盖包列表加载、Drawable->ImageBitmap 与 LazyColumn 滚动
        device.findObject(By.pkg(packageName).text("不尝试获取布局的应用"))?.let { entry ->
            entry.click()
            val dialogLoaded = device.wait(
                Until.hasObject(By.pkg(packageName).clazz("android.widget.EditText")),
                4_000L
            )
            if (dialogLoaded) {
                device.waitForIdle()
                findScrollable()?.let { appList ->
                    runCatching {
                        appList.scroll(Direction.DOWN, 0.8f)
                        device.waitForIdle()
                        appList.scroll(Direction.UP, 0.8f)
                    }
                    device.waitForIdle()
                }
                device.findObject(By.pkg(packageName).clazz("android.widget.EditText"))?.let { searchBox ->
                    runCatching {
                        searchBox.text = "a"
                        device.waitForIdle()
                        searchBox.text = ""
                        device.waitForIdle()
                    }
                }
                device.pressBack()
                device.waitForIdle()
                if (device.hasObject(By.pkg(packageName).clazz("android.widget.EditText"))) {
                    device.pressBack()
                    device.waitForIdle()
                }
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

        findScrollable()?.let { scrollable ->
            runCatching {
                scrollable.scroll(Direction.DOWN, 0.8f)
                device.waitForIdle()
                scrollable.scroll(Direction.UP, 0.8f)
            }
            device.waitForIdle()
        }

        // 切换 Root / Shizuku 分段按钮并恢复原状，避免改变 useShizuku 持久化配置
        val rootBtn = device.findObject(By.pkg(packageName).text("Root"))
        val shizukuBtn = device.findObject(By.pkg(packageName).text("Shizuku"))
        if (rootBtn != null && shizukuBtn != null) {
            val wasShizukuSelected = shizukuBtn.isChecked || shizukuBtn.isSelected
            if (wasShizukuSelected) {
                rootBtn.click()
                device.waitForIdle()
                device.findObject(By.pkg(packageName).text("Shizuku"))?.click()
            } else {
                shizukuBtn.click()
                device.waitForIdle()
                device.findObject(By.pkg(packageName).text("Root"))?.click()
            }
            device.waitForIdle()
        }
    }
}
