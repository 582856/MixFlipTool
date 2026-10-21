package com.parallelc.mixfliptool

import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.Locale

class AppScaleSettingsActivity : ComponentActivity() {
    private val viewModel: AppScaleSettingsViewModel by viewModels {
        AppScaleSettingsViewModelFactory(this)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        viewModel.handlePermissionResult(isGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!permissionsReady()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainScreen(viewModel)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.requestPermission.collect { request ->
                    if (request) {
                        permissionLauncher.launch(INSTALLED_APPS_PERMISSION)
                    }
                }
            }
        }

        viewModel.refreshApplications()
    }

    private fun permissionsReady() =
        checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED &&
            hasInstalledAppsPermission(this)

    override fun onResume() {
        super.onResume()
        if (!permissionsReady()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }
}

@Composable
fun ScaleDialog(appInfo: AppInfo, onDismiss: () -> Unit, onScaleChange: (String) -> Unit) {
    var isScalingEnabled by remember { mutableStateOf(appInfo.scale.isNotEmpty()) }
    var scaleValue by remember { mutableFloatStateOf(appInfo.scale.toFloatOrNull() ?: 0.8f) }
    var manualScaleInput by remember { mutableStateOf(String.format(Locale.ROOT, "%.2f", scaleValue)) }
    var showError by remember { mutableStateOf(false) }

    fun handleDone() {
        if (isScalingEnabled && showError) return
        onScaleChange(if (isScalingEnabled) manualScaleInput else "")
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(insideMargin = PaddingValues(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(appInfo.appName, style = MiuixTheme.textStyles.title3)
                Text("设置缩放比例", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("开启缩放", modifier = Modifier.weight(1f))
                    Switch(checked = isScalingEnabled, onCheckedChange = { isScalingEnabled = it })
                }
                if (isScalingEnabled) {
                    Slider(
                        value = scaleValue,
                        onValueChange = { newScale ->
                            scaleValue = newScale
                            manualScaleInput = String.format(Locale.ROOT, "%.2f", newScale) // 保留两位小数
                            showError = false
                        },
                        valueRange = 0.01f..1f, // 缩放范围改为 0 到 1
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = manualScaleInput,
                        onValueChange = { input ->
                            manualScaleInput = input
                            val parsed = input.toFloatOrNull()
                            showError = parsed == null || parsed !in 0.01f..1f ||
                                !input.matches(Regex("(?:0\\.\\d{1,2}|1(?:\\.0{1,2})?)"))
                            if (!showError) scaleValue = parsed!!
                        },
                        label = "缩放比例（0.01–1.00）",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { handleDone() }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (showError) Text("请输入 0.01 到 1.00 之间的数值，最多两位小数。",
                        style = MiuixTheme.textStyles.footnote1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", onDismiss, Modifier.weight(1f))
                    TextButton("完成", { handleDone() }, Modifier.weight(1f),
                        enabled = !isScalingEnabled || !showError,
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: AppScaleSettingsViewModel) {
    val appList by viewModel.appList.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val showSystemApps by viewModel.showSystemApps.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val visibleApps = remember(appList, showSystemApps, text) {
        appList.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (app.appName.contains(text, ignoreCase = true) || app.packageName.contains(text, ignoreCase = true))
        }
    }
    LaunchedEffect(showSystemApps, text) { listState.scrollToItem(0) }

    Scaffold(topBar = {
        SmallTopAppBar(title = "MixFlipTool", actions = {
            Row(Modifier.padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("系统应用", style = MiuixTheme.textStyles.footnote1)
                Spacer(Modifier.width(8.dp))
                Switch(checked = showSystemApps, onCheckedChange = viewModel::setShowSystemApps,
                    modifier = Modifier.semantics { contentDescription = "显示系统应用" })
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(),
            start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))) {
            TextField(value = text, onValueChange = { text = it }, label = "搜索应用或包名",
                useLabelAsPlaceholder = true, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.padding(start = 16.dp)) },
                trailingIcon = {
                    IconButton(onClick = { showResetDialog = true }) { Icon(Icons.Default.Restore, "重置缩放设置") }
                })
            Text(if (isRefreshing) "正在加载应用…" else "${visibleApps.size} 个应用",
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            PullToRefresh(isRefreshing = isRefreshing, onRefresh = viewModel::refreshApplications,
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
                        bottom = 20.dp + padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        Card(onClick = { selectedApp = app }, insideMargin = PaddingValues(16.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Image(rememberDrawablePainter(app.icon), null, Modifier.size(48.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(app.appName, style = MiuixTheme.textStyles.headline1,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (app.scale.isNotEmpty()) Text("缩放比例：${app.scale}",
                                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                                    Text(app.packageName, style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (visibleApps.isEmpty() && !isRefreshing) item {
                        Text("没有匹配的应用", Modifier.padding(20.dp))
                    }
                }
            }
        }
    }
    selectedApp?.let { app ->
        ScaleDialog(app, onDismiss = { selectedApp = null },
            onScaleChange = { viewModel.settingAppScale(app.packageName, it) })
    }
    if (showResetDialog) {
        Dialog(onDismissRequest = { showResetDialog = false }) {
            Card(insideMargin = PaddingValues(24.dp)) {
                Text("重置缩放设置", style = MiuixTheme.textStyles.title3)
                Spacer(Modifier.height(16.dp))
                Text("确定恢复默认设置？")
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("取消", { showResetDialog = false }, Modifier.weight(1f))
                    TextButton("确认", { showResetDialog = false; viewModel.resetAppScale() },
                        Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
        }
    }
}
