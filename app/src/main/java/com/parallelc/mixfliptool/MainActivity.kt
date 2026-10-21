package com.parallelc.mixfliptool

import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {
    private var privileged by mutableStateOf(false)
    private var installedApps by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var chooseAuthorization by mutableStateOf(false)
    private var appsPermissionRequested = false

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
        if (busy) return
        busy = true
        privileged = false
        lifecycleScope.launch {
            privileged = withContext(Dispatchers.IO) {
                (!rootOnly && runCatching { grantDumpPermissionWithShizuku() }.getOrDefault(false)) ||
                    grantDumpPermissionWithRoot()
            }
            installedApps = hasInstalledAppsPermission(this@MainActivity)
            busy = false
            message = if (privileged) "" else "点击第一项，选择 Root 或 Shizuku 授权。"
        }
    }

    private fun requestInstalledApps() {
        if (!privileged || busy) return
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
        if (busy || !privileged || !hasInstalledAppsPermission(this)) return
        busy = true
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { allowStartApps() && configAppScale() }
            }.onSuccess { success ->
                if (success) startActivity(Intent(this@MainActivity, AppScaleSettingsActivity::class.java))
                message = if (success) "配置已应用。" else "配置失败，请重新检测权限。"
            }.onFailure { exception ->
                Log.e("mixfliptool", "配置失败", exception)
                message = "配置失败：${exception.message ?: "请重新授权后重试"}"
            }
            busy = false
        }
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

    private fun allowStartApps(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (!hasInstalledAppsPermission(this)) return false

        val apps = packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L)).map { packageInfo -> packageInfo.packageName }.sorted()
        if (apps.size == 1) {
            Toast.makeText(this, "获取应用列表失败！", Toast.LENGTH_SHORT).show()
            return false
        }

        val pipe = ParcelFileDescriptor.createPipe()

        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { inputStream ->
                    val buffer = ByteArray(1024)
                    var readBytes: Int
                    while (inputStream.read(buffer).also { readBytes = it } != -1) {
                        val message = String(buffer, 0, readBytes)
                        Log.d("mixfliptool", "allowStartApps: $message")
                    }
                }
            } catch (e: IOException) {
                Log.e("mixfliptool", "allowStartApps: ", e)
            }
        }

        val windowManager = SystemServiceHelper.getSystemService("window")
        try {
            windowManager.dump(pipe[1].fileDescriptor, arrayOf("-setForceDisplayCompatMode", apps.joinToString(":"), "allowstart"))
        } finally {
            pipe[1].close()
            executor.shutdown()
        }
        return true
    }

    private fun configAppScale(): Boolean {
        val miuiSizeCompat = SystemServiceHelper.getSystemService("MiuiSizeCompat")
        val pipe = ParcelFileDescriptor.createPipe()
        val pipeIn = pipe[0].fileDescriptor
        val pipeOut = pipe[1].fileDescriptor
        val shellCallback = HiddenApiBypass.newInstance(Class.forName("android.os.ShellCallback"))
        val resultReceiver = ResultReceiver(null)
        HiddenApiBypass.invoke(IBinder::class.java, miuiSizeCompat, "shellCommand", pipeIn, pipeOut, pipeOut, arrayOf("reload-rule"), shellCallback, resultReceiver)

        val context = this
        runBlocking { AppScaleManager(context).getAllPreferences() }.forEach { (packageName, scale) ->
            HiddenApiBypass.invoke(IBinder::class.java, miuiSizeCompat, "shellCommand", pipeIn, pipeOut, pipeOut, arrayOf("update-rule", packageName, "enable::true"), shellCallback, resultReceiver)
            HiddenApiBypass.invoke(IBinder::class.java, miuiSizeCompat, "shellCommand", pipeIn, pipeOut, pipeOut, arrayOf("update-rule", packageName, "scale::$scale"), shellCallback, resultReceiver)
            HiddenApiBypass.invoke(IBinder::class.java, miuiSizeCompat, "shellCommand", pipeIn, pipeOut, pipeOut, arrayOf("update-rule", packageName, "activityRule::null"), shellCallback, resultReceiver)
        }
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
        refreshPermissions()
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
