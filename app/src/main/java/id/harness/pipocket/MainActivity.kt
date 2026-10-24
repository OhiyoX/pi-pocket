package id.harness.pipocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.harness.pipocket.ui.ChatScreen
import id.harness.pipocket.ui.FilesScreen
import id.harness.pipocket.ui.SshSheet
import id.harness.pipocket.ui.PiPocketTheme
import id.harness.pipocket.ui.ProvidersSheet
import id.harness.pipocket.ui.SessionsScreen
import id.harness.pipocket.ui.SettingsSheet
import id.harness.pipocket.ui.MiscSheet
import id.harness.pipocket.ui.ShellScreen

class MainActivity : ComponentActivity() {
    private val notifyPermission =
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()

    /** 通知权限（Android 13+）。不给也能用，只是收不到"答完了"的提醒。 */
    private fun askNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching {
            registerForActivityResult(notifyPermission) { /* 拒了就拒了，不打扰 */ }
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(applicationContext)
        askNotifyPermission()
        // 后台守护：前台服务的启动窗口只能在 app 在前台时（Android 12+ 限制）
        AppState.syncLinkService(this)
        handleOpenSessionIntent(intent)
        // 前后台钩子：Android 会在后台收掉 socket，回前台必须主动重探；
        // 后台停心跳/退避（省电）。这就是“掉桥”修根的一半。
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    AppState.onAppForeground()
                }

                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    AppState.onAppBackground()
                }
            },
        )
        enableEdgeToEdge()
        setContent {
            PiPocketTheme {
                // 工具块的终端列数：**实测等宽字宽**后算"卡片真实能放几列"，
                // 桥按这个宽度调 pi 的渲染器 ⇒ 换行点与终端 1:1，app 不再二次折行。
                val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.SideEffect {
                    // 以前是拍一个 0.6em + 0.93 余量的估算 ⇒ 报给桥的列数偏大，
                    // pi 按 90 列折、卡片只放得下 ~45 列 ⇒ 每行再折一次，断在词中间。
                    // 现在直接用 Paint 量 Monospace 在**工具块字号**下的实际字宽：不猜。
                    val fontSp = AppState.fontToolSp.coerceAtLeast(8)
                    val advancePx = android.graphics.Paint().apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = with(density) { fontSp.sp.toPx() }
                    }.measureText("0").coerceAtLeast(1f)
                    // 可用宽度：屏幕 - 卡片外边距(12×2) - 卡片内边距(12×2) - 2dp 抗锯齿余量
                    val usablePx = with(density) { (screenW - 50f).dp.toPx() }
                    val capacity = (usablePx / advancePx).toInt().coerceIn(20, 200)
                    // 手动列数只能**变窄**到仍能放下的程度；报大了一定会双层折行。
                    val cols = if (AppState.termColsOverride > 0) minOf(AppState.termColsOverride, capacity) else capacity
                    if (AppState.termCols != cols) AppState.termCols = cols
                }
                // 上报屏幕/字号/主题给桥（桥拼进注入 pi 的提示，让 agent 按手机屏出内容）。
                // 只在旋转、改字号、换主题时发（DeviceInfo.signature 去重）；连上后会自动补发一次。
                val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                val ctxForDevice = androidx.compose.ui.platform.LocalContext.current
                val dark0 = AppState.isDark(isSystemInDarkTheme())
                androidx.compose.runtime.LaunchedEffect(
                    cfg.screenWidthDp, cfg.screenHeightDp, cfg.fontScale, density.density,
                    dark0, AppState.fontBodySp, AppState.fontToolNameSp, AppState.fontToolSp,
                    AppState.fontMetaSp, AppState.fontInputSp, AppState.chatFontScale,
                ) {
                    val dm = ctxForDevice.resources.displayMetrics
                    AppState.reportDevice(
                        id.harness.pipocket.DeviceInfo.of(
                            screenWdp = cfg.screenWidthDp,
                            screenHdp = cfg.screenHeightDp,
                            density = density.density,
                            densityDpi = dm.densityDpi,
                            fontScale = cfg.fontScale * AppState.fontScale,
                            theme = if (dark0) "dark" else "light",
                            bodySp = AppState.fontBodySp,
                            toolNameSp = AppState.fontToolNameSp,
                            toolSp = AppState.fontToolSp,
                            metaSp = AppState.fontMetaSp,
                            inputSp = AppState.fontInputSp,
                            chatScale = AppState.chatFontScale,
                        ),
                    )
                }

                // 系统栏图标明暗必须跟随 **app 的主题**（app 可能压过系统深浅）
                val dark = AppState.isDark(isSystemInDarkTheme())
                SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    )
                }

                // 查看器 overlay 放在最上层（图片 > 文件面板），点击/返回都走 overlay 自己的 BackHandler
                Box(modifier = Modifier.fillMaxSize()) {
                val shellOpen = AppState.shellOpen
                val filesOpen = AppState.filesOpen
                val session = AppState.current
                when {
                    filesOpen -> {
                        BackHandler { AppState.closeFiles() }
                        FilesScreen()
                    }
                    shellOpen -> {
                        BackHandler { AppState.closeShell() }
                        ShellScreen()
                    }

                    session == null -> SessionsScreen()

                    else -> {
                        BackHandler { AppState.closeSession() }
                        ChatScreen(session = session)
                    }
                }
                id.harness.pipocket.ui.ViewerOverlays()
                }

                // 设置面板（复杂配置都收在这里）
                if (AppState.sshOpen) {
                    SshSheet(onDismiss = { AppState.closeSsh() })
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    id.harness.pipocket.ui.LocalSettingsChild provides (AppState.settingsNavigation.parent == SettingsPage.Home),
                ) {
                    when (AppState.settingsNavigation.page) {
                        SettingsPage.Home -> SettingsSheet(onDismiss = { AppState.settingsOpen = false })
                        SettingsPage.AppProfile -> id.harness.pipocket.ui.AppProfileSheet(onDismiss = AppState::backSettingsPage)
                        SettingsPage.Providers -> ProvidersSheet(onDismiss = AppState::closeProviders)
                        SettingsPage.Misc -> MiscSheet(onDismiss = AppState::closeMisc)
                        SettingsPage.Appearance -> id.harness.pipocket.ui.AppearanceSheet(onDismiss = AppState::backSettingsPage)
                        SettingsPage.Connection -> id.harness.pipocket.ui.ConnectionSheet(onDismiss = AppState::backSettingsPage)
                        SettingsPage.Updates -> id.harness.pipocket.ui.UpdateSettingsSheet(onDismiss = AppState::backSettingsPage)
                        null -> Unit
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 少数厂商/复用的 Intent 可能不带 extra（实测 `am start` 重复发同一个 Intent 时 extras 为空），
        // 回落到 getIntent() 再试一次；仍拿不到就不硬跳（留在列表页）。
        handleOpenSessionIntent(intent)
        if (intent.getStringExtra(EXTRA_OPEN_SESSION_FILE).isNullOrBlank()) {
            handleOpenSessionIntent(getIntent())
        }
    }

    /** 点通知进来：打开那条会话（拿不到就留在列表页，不硬跳）。 */
    private fun handleOpenSessionIntent(intent: android.content.Intent?) {
        android.util.Log.i("PiPocketDebug", "handleOpenSessionIntent extra=${intent?.extras?.keySet()}")
        val file = intent?.getStringExtra(EXTRA_OPEN_SESSION_FILE)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_OPEN_SESSION_FILE)
        AppState.openSessionByFile(file)
    }

    companion object {
        const val EXTRA_OPEN_SESSION_FILE = "openSessionFile"
    }
}
