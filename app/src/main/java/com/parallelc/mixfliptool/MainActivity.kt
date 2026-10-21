package com.parallelc.mixfliptool

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
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

    private fun grantDumpPermissionWithRoot(): Boolean {
        var process: Process? = null
        return try {
            val userId = HiddenApiBypass.invoke(
                android.os.UserHandle::class.java, android.os.Process.myUserHandle(), "getIdentifier"
            ) as Int
            process = ProcessBuilder(
                "su", "-c",
                "pm grant --user $userId $packageName android.permission.DUMP"
            ).redirectErrorStream(true).start()
            process.outputStream.close()
            process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        } catch (e: IOException) {
            Log.d("mixfliptool", "Root授权不可用", e)
            false
        } finally {
            process?.destroy()
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
            if (!Shizuku.pingBinder() || !checkShizukuPermission(0)) {
                Toast.makeText(this, "没有权限！请允许Root授权，或通过ADB或Shizuku授权！", Toast.LENGTH_SHORT).show()
                return false
            }
            val iPmClass = Class.forName("android.content.pm.IPackageManager")
            val iPmStub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterfaceMethod = iPmStub.getMethod("asInterface", IBinder::class.java)
            val iPmInstance = asInterfaceMethod.invoke(null, ShizukuBinderWrapper(
                SystemServiceHelper.getSystemService("package"))
            )
            HiddenApiBypass.invoke(iPmClass, iPmInstance, "grantRuntimePermission", "com.parallelc.mixfliptool", android.Manifest.permission.DUMP, 0)
        }
        try {
            val permissionInfo = packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
            if (permissionInfo != null && checkSelfPermission("com.android.permission.GET_INSTALLED_APPS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("com.android.permission.GET_INSTALLED_APPS"), 0)
                Toast.makeText(this, "获取应用列表失败！", Toast.LENGTH_SHORT).show()
                return false
            }
        } catch (e : PackageManager.NameNotFoundException) {
            Log.e("mixfliptool", e.stackTraceToString())
        }

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
        windowManager.dump(pipe[1].fileDescriptor, arrayOf("-setForceDisplayCompatMode", apps.joinToString(":"), "allowstart"))

        executor.shutdown()
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
        lifecycleScope.launch {
            runCatching {
                if (checkSelfPermission(android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
                    withContext(Dispatchers.IO) { grantDumpPermissionWithRoot() }
                }
                allowStartApps() && configAppScale()
            }.onFailure { exception ->
                Log.e("mixfliptool", exception.stackTraceToString())
                Toast.makeText(this@MainActivity, "配置失败！", Toast.LENGTH_SHORT).show()
            }.onSuccess { result ->
                if (result) Toast.makeText(this@MainActivity, "配置成功！", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
