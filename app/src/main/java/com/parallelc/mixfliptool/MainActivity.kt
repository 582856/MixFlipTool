package com.parallelc.mixfliptool

import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {
    private var privileged by mutableStateOf(false)
    private var installedApps by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var chooseAuthorization by mutableStateOf(false)
    private var appsPermissionRequested = false
    private var checkingPermissions = false

    private val appsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        installedApps = hasInstalledAppsPermission(this)
        message = if (installedApps) "" else "请允许获取应用列表，授权后返回此页。"
    }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 100) {
            if (result == PackageManager.PERMISSION_GRANTED) refreshPermissions() else {
                message = "Shizuku 授权未通过，请点击重试。"
            }
        }
    }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshPermissions() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshPermissions() }
    }

    private fun currentUserId() = HiddenApiBypass.invoke(
        android.os.UserHandle::class.java, android.os.Process.myUserHandle(), "getIdentifier"
    ) as Int

    private fun grantDumpPermissionWithRoot(): Boolean {
        var process: Process? = null
        return try {
            val userId = currentUserId()
            process = ProcessBuilder(
                "su", "-c",
                "pm grant --user $userId $packageName android.permission.DUMP"
            ).redirectErrorStream(true).start()
            process.outputStream.close()
            process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.d("mixfliptool", "Root授权不可用", e)
            false
        } finally {
            process?.destroy()
        }
    }

    private fun grantDumpPermissionWithShizuku(): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false
        val iPmClass = Class.forName("android.content.pm.IPackageManager")
        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(
            null, ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package"))
        )
        HiddenApiBypass.invoke(iPmClass, service, "grantRuntimePermission",
            packageName, android.Manifest.permission.DUMP, currentUserId())
        return checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPermissions(rootOnly: Boolean = false) {
        installedApps = hasInstalledAppsPermission(this)
        if (isFinishing || busy || checkingPermissions) return
        checkingPermissions = true
        if (rootOnly) busy = true
        lifecycleScope.launch {
            privileged = withContext(Dispatchers.IO) {
                checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED ||
                    (!rootOnly && runCatching { grantDumpPermissionWithShizuku() }.getOrDefault(false)) ||
                    grantDumpPermissionWithRoot()
            }
            installedApps = hasInstalledAppsPermission(this@MainActivity)
            checkingPermissions = false
            if (rootOnly) busy = false
            message = if (privileged) "" else "点击第一项，选择 Root 或 Shizuku 授权。"
            if (privileged && installedApps) enterApplication()
        }
    }

    private fun requestInstalledApps() {
        if (!privileged || busy || checkingPermissions) return
        if (hasInstalledAppsPermission(this)) {
            installedApps = true
        } else if (appsPermissionRequested &&
            !shouldShowRequestPermissionRationale(INSTALLED_APPS_PERMISSION)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            appsPermissionRequested = true
            appsPermissionLauncher.launch(INSTALLED_APPS_PERMISSION)
        }
    }

    private fun enterApplication() {
        if (isFinishing || busy || checkingPermissions || !privileged || !hasInstalledAppsPermission(this)) return
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun checkShizukuPermission(code: Int): Boolean {
        if (Shizuku.isPreV11()) {
            // Pre-v11 is unsupported
            return false
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // Granted
            return true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Users choose "Deny and don't ask again"
            return false
        } else {
            // Request the permission
            Shizuku.requestPermission(code)
            return false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privileged = checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        installedApps = hasInstalledAppsPermission(this)
        if (privileged && installedApps) {
            enterApplication()
            return
        }
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        appsPermissionRequested = savedInstanceState?.getBoolean("appsPermissionRequested") ?: false
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        setContent {
            PermissionScreen(privileged, installedApps, busy, message, chooseAuthorization,
                onPrivilegeClick = { chooseAuthorization = true },
                onAppsClick = { requestInstalledApps() },
                onContinue = { enterApplication() },
                onDismiss = { chooseAuthorization = false },
                onRoot = { chooseAuthorization = false; refreshPermissions(rootOnly = true) },
                onShizuku = {
                    chooseAuthorization = false
                    runCatching {
                        if (!Shizuku.pingBinder()) {
                            message = "请先启动 Shizuku 服务，再返回授权。"
                        } else if (checkShizukuPermission(100)) refreshPermissions()
                        else message = "请在 Shizuku 中允许 MixFlipTool 授权。"
                    }.onFailure { message = "Shizuku 暂不可用，请确认服务已启动。" }
                })
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) refreshPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("appsPermissionRequested", appsPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }
}
