package com.parallelc.mixfliptool

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class HomeActivity : ComponentActivity() {
    private var openDualScreen by mutableStateOf(false)
    private val moduleStatus = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DualScreenService.preferences(context).edit().putBoolean("module_loaded", true)
                .putString("error", intent.getStringExtra("error").orEmpty()).apply()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(moduleStatus, IntentFilter(DualScreenService.STATUS_ACTION), android.Manifest.permission.DUMP,
            null, Context.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(moduleStatus)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!checkPermissions()) return
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        openDualScreen = intent.getBooleanExtra("dual_screen", false)
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                FeatureScreen(openDualScreen = openDualScreen, onOpenApps = {
                    startActivity(Intent(this, AppScaleSettingsActivity::class.java))
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && checkPermissions()) {
            DualScreenService.preferences(this).edit().putBoolean("module_loaded", false).apply()
            DualScreenService.sync(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openDualScreen = intent.getBooleanExtra("dual_screen", false)
    }

    private fun checkPermissions(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED &&
            hasInstalledAppsPermission(this)) return true
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        return false
    }
}

@Composable
private fun FeatureScreen(openDualScreen: Boolean, onOpenApps: () -> Unit) {
    var showDualScreen by rememberSaveable { mutableStateOf(openDualScreen) }
    LaunchedEffect(openDualScreen) { if (openDualScreen) showDualScreen = true }
    BackHandler(enabled = showDualScreen) { showDualScreen = false }

    Scaffold(topBar = {
        if (showDualScreen) {
            SmallTopAppBar(title = "外屏音乐显示", navigationIcon = {
                IconButton(onClick = { showDualScreen = false }, Modifier.padding(start = 12.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            })
        } else {
            TopAppBar(title = "MixFlipTool")
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(),
                start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp,
                    bottom = 12.dp + padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showDualScreen) {
                DualScreenSettings()
            } else {
                FeatureItem("外屏应用解锁", "管理外屏应用与缩放比例", Icons.Default.Apps, onOpenApps)
                FeatureItem("外屏音乐显示", "设置亮屏与息屏时显示的音乐元素", Icons.Default.FlipToFront) {
                    showDualScreen = true
                }
                AppUpdateItem()
            }
        }
    }
}

@Composable
private fun AppUpdateItem() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("app_updates", Context.MODE_PRIVATE) }
    val installed = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var checking by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var newerRelease by remember { mutableStateOf<AppRelease?>(null) }
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    suspend fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        try {
            val release = latestAppRelease()
            preferences.edit().putLong("last_successful_check", System.currentTimeMillis()).apply()
            newerRelease = release?.takeIf { isNewerRelease(it.version, installed) }
            title = if (newerRelease != null) "发现新版本 ${newerRelease!!.version}" else "暂无新版本"
            message = when {
                newerRelease != null -> newerRelease!!.notes.ifBlank { "作者暂未填写更新说明。" }
                release == null -> "GitHub 暂无正式发布版本。"
                else -> "当前版本 $installed 已是最新版本。"
            }
            showDialog = manual || newerRelease != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            newerRelease = null
            title = "检查更新失败"
            message = if (error is java.io.IOException && error.message?.startsWith("GitHub ") == true)
                error.message.orEmpty() else "无法连接 GitHub，请检查网络后重试。"
            showDialog = manual
        } finally {
            checking = false
        }
    }

    LaunchedEffect(Unit) {
        val elapsed = System.currentTimeMillis() - preferences.getLong("last_successful_check", 0L)
        if (elapsed < 0L || elapsed >= 86_400_000L) checkUpdate(false)
    }
    FeatureItem("检查更新", if (checking) "正在检查 GitHub 更新…" else "当前版本 $installed · GitHub Releases",
        Icons.Default.SystemUpdateAlt) { scope.launch { checkUpdate(true) } }

    if (showDialog) Dialog(onDismissRequest = { showDialog = false }) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
            Text(title, modifier = Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.title3,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(message, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()), style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(if (newerRelease == null) "知道了" else "稍后", { showDialog = false }, Modifier.weight(1f))
                newerRelease?.let { release ->
                    TextButton("前往下载", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))) }
                            .onSuccess { showDialog = false }
                            .onFailure { android.widget.Toast.makeText(context, "未找到可打开链接的浏览器", android.widget.Toast.LENGTH_SHORT).show() }
                    }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
private fun DualScreenSettings() {
    val context = LocalContext.current
    val prefs = remember { DualScreenService.preferences(context) }
    var master by remember { mutableStateOf(prefs.getBoolean("master", false)) }
    var options by remember { mutableStateOf(MusicDisplayOptions.read(prefs::getInt)) }
    var error by remember { mutableStateOf(prefs.getString("error", "").orEmpty()) }
    var moduleLoaded by remember { mutableStateOf(prefs.getBoolean("module_loaded", false)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            master = prefs.getBoolean("master", false)
            options = MusicDisplayOptions.read(prefs::getInt)
            error = prefs.getString("error", "").orEmpty()
            moduleLoaded = prefs.getBoolean("module_loaded", false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun set(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).remove("error").apply()
        DualScreenService.sync(context)
    }
    DualScreenToggle("外屏音乐显示", "总开关，展开手机播放音乐时生效", master, true) { set("master", it) }
    fun setElements(key: String, mask: Int) {
        prefs.edit().putInt(key, mask).apply()
        DualScreenService.sync(context)
    }
    MusicElementsSection("亮屏时", options.bright, master) { setElements(MusicDisplayOptions.BRIGHT_KEY, it) }
    MusicElementsSection("息屏时", options.dim, master) { setElements(MusicDisplayOptions.DIM_KEY, it) }
    Text(if (moduleLoaded) "LSP 模块已连接，关闭应用后继续生效。" else "请在 LSPosed 中启用 MixFlipTool，勾选“系统框架”和“系统界面”，然后重启手机。",
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text("仅在手机展开时生效。内屏锁定、音乐暂停或外屏 15 秒无操作后显示息屏信息，暂停一小时后退出；双击切换亮屏与息屏，亮屏时左右滑动切歌。合盖后恢复系统外屏。",
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    if (error.isNotEmpty()) Text(error, modifier = Modifier.padding(horizontal = 12.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun MusicElementsSection(title: String, mask: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Text(title, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
        style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp)) {
        Column {
            MusicDisplayOptions.elements.forEach { (name, element) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                    Switch(checked = mask and element != 0, enabled = enabled,
                        onCheckedChange = { checked -> onChange(if (checked) mask or element else mask and element.inv()) },
                        modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun DualScreenToggle(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MiuixTheme.textStyles.headline1)
                Text(summary, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun FeatureItem(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = MiuixTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MiuixTheme.textStyles.headline1)
                Text(summary, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 8.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
