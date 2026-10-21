package com.parallelc.mixfliptool

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

const val INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

fun hasInstalledAppsPermission(context: Context): Boolean = try {
    context.packageManager.getPermissionInfo(INSTALLED_APPS_PERMISSION, 0)
    context.checkSelfPermission(INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
} catch (_: PackageManager.NameNotFoundException) {
    true
}

@Composable
fun PermissionScreen(
    privileged: Boolean, installedApps: Boolean, busy: Boolean, message: String,
    chooseAuthorization: Boolean, onPrivilegeClick: () -> Unit, onAppsClick: () -> Unit,
    onContinue: () -> Unit, onDismiss: () -> Unit, onRoot: () -> Unit, onShizuku: () -> Unit
) {
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Text("权限设置", style = MiuixTheme.textStyles.title1)
                Text("MixFlipTool 需要以下权限\n授权完成后即可进入应用。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(20.dp))
                PermissionItem("Root 或 Shizuku", "用于配置外屏应用和缩放规则", privileged,
                    !busy, onPrivilegeClick)
                PermissionItem("应用列表", "用于读取和管理已安装的应用",
                    installedApps, privileged && !busy, onAppsClick)
                Text(if (busy) "正在检测或应用配置，请稍候…" else message,
                    modifier = Modifier.heightIn(min = 48.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.weight(1f))
                TextButton("进入应用", onContinue, Modifier.fillMaxWidth(),
                    enabled = privileged && installedApps && !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        if (chooseAuthorization) {
            Dialog(onDismissRequest = onDismiss) {
                Card(insideMargin = PaddingValues(24.dp)) {
                    Text("选择授权方式", style = MiuixTheme.textStyles.title3)
                    Spacer(Modifier.height(20.dp))
                    TextButton("Root 授权", onRoot, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TextButton("Shizuku 授权", onShizuku, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TextButton("取消", onDismiss, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(title: String, summary: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().semantics {
        stateDescription = if (checked) "已授权" else "未授权"
    }, insideMargin = PaddingValues(20.dp), onClick = { if (enabled) onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MiuixTheme.textStyles.headline1)
                Text(summary, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Box(Modifier.padding(start = 12.dp).size(24.dp)) {
                if (checked) Icon(Icons.Default.Check, "已授权", tint = MiuixTheme.colorScheme.primary)
            }
        }
    }
}
