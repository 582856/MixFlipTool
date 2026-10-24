package com.parallelc.mixfliptool

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter

class DualScreenConnection(private val context: Context) : AutoCloseable {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val requests = Mutex()

    suspend fun connect() {
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        val command = "CLASSPATH='$apk' exec /system/bin/app_process /system/bin com.parallelc.mixfliptool.DualScreenProcess"
        process = if (runCatching {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)) {
            // Shizuku 13 keeps this API private; the remote process dies with our app.
            Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java,
                Array<String>::class.java, String::class.java).apply { isAccessible = true }
                .invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } else {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }
        reader = process!!.inputStream.bufferedReader()
        writer = process!!.outputStream.bufferedWriter()
        check(readResponse() == "READY") { "无法连接双屏控制，请检查 Root 或 Shizuku 授权" }
    }

    suspend fun update(wanted: Boolean): JSONObject = request(if (wanted) "on" else "off")

    suspend fun sample(): JSONObject = request("sample")

    suspend fun command(value: String): JSONObject = request(value)

    private suspend fun request(value: String): JSONObject = requests.withLock {
        writer!!.apply { write("$value\n"); flush() }
        JSONObject(readResponse())
    }

    private suspend fun readResponse(): String = withTimeout(15_000) {
        while (true) {
            if (reader!!.ready()) {
                val line = reader!!.readLine() ?: error("双屏控制进程已退出")
                if (line.startsWith("MFT:ERROR:")) error(line.removePrefix("MFT:ERROR:"))
                if (line.startsWith("MFT:")) return@withTimeout line.removePrefix("MFT:")
            } else {
                check(process!!.isAlive) { "双屏控制不可用，请检查 Root 或 Shizuku 授权" }
                delay(2)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("双屏控制无响应")
    }

    override fun close() {
        // EOF releases the helper's Binder request, including when our app process is killed.
        runCatching { writer?.apply { write("quit\n"); flush() } }
        runCatching { writer?.close() }
        runCatching { process?.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        process?.destroy()
        reader = null
        writer = null
        process = null
    }
}
