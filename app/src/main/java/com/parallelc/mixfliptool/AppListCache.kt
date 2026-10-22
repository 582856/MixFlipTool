package com.parallelc.mixfliptool

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.roundToInt

internal object AppListCache {
    private data class Snapshot(val boot: Int, val configuration: String, val sequence: Int, val apps: List<AppInfo>)

    @Volatile private var memory: Snapshot? = null

    private fun cacheFile(context: Context) = AtomicFile(File(context.cacheDir, "installed-apps-v1.bin"))

    fun exists(context: Context) = memory != null || cacheFile(context).baseFile.exists()

    @Synchronized
    fun load(context: Context, forceRefresh: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val configuration = context.resources.configuration.let {
            "${it.locales.toLanguageTags()}:${it.densityDpi}:${it.uiMode}"
        }
        fun isCurrent(snapshot: Snapshot) = snapshot.boot == boot && snapshot.configuration == configuration &&
            pm.getChangedPackages(snapshot.sequence) == null

        if (!forceRefresh) {
            memory?.takeIf(::isCurrent)?.let {
                Log.d("mixfliptool", "应用列表缓存：memory (${it.apps.size})")
                return it.apps
            }
            val disk = runCatching {
                DataInputStream(cacheFile(context).openRead().buffered()).use { input ->
                    if (input.readInt() != 1) return@use null
                    val header = Snapshot(input.readInt(), input.readUTF(), input.readInt(), emptyList())
                    if (!isCurrent(header)) return@use null
                    val count = input.readInt()
                    require(count in 2..10000)
                    header.copy(apps = List(count) {
                        val packageName = input.readUTF()
                        val appName = input.readUTF()
                        val isSystem = input.readBoolean()
                        val size = input.readInt()
                        require(size in 1..1048576)
                        val bytes = ByteArray(size).also(input::readFully)
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, size))
                        AppInfo(BitmapDrawable(context.resources, bitmap), appName, packageName, "", isSystem)
                    })
                }
            }.getOrNull()
            if (disk != null) {
                memory = disk
                Log.d("mixfliptool", "应用列表缓存：disk (${disk.apps.size})")
                return disk.apps
            }
        }

        val sequence = pm.getChangedPackages(0)?.sequenceNumber ?: 0
        val iconSize = (48 * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L)).map { info ->
            val bitmap = pm.getApplicationIcon(info).toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            AppInfo(BitmapDrawable(context.resources, bitmap), pm.getApplicationLabel(info).toString(),
                info.packageName, "", info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        }
        check(apps.size > 1) { "获取应用列表失败" }
        val snapshot = Snapshot(boot, configuration, sequence, apps)
        memory = snapshot
        val file = cacheFile(context)
        runCatching {
            val stream = file.startWrite()
            try {
                val output = DataOutputStream(stream.buffered())
                output.writeInt(1)
                output.writeInt(boot)
                output.writeUTF(configuration)
                output.writeInt(sequence)
                output.writeInt(apps.size)
                apps.forEach { app ->
                    output.writeUTF(app.packageName)
                    output.writeUTF(app.appName)
                    output.writeBoolean(app.isSystem)
                    val bytes = ByteArrayOutputStream().also {
                        (app.icon as BitmapDrawable).bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
                output.flush()
                file.finishWrite(stream)
            } catch (exception: Exception) {
                file.failWrite(stream)
                throw exception
            }
        }.onFailure { Log.w("mixfliptool", "应用列表缓存保存失败", it) }
        Log.d("mixfliptool", "应用列表缓存：rebuild (${apps.size}), force=$forceRefresh")
        return apps
    }
}
