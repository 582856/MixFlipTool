package com.parallelc.mixfliptool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class AppRelease(val version: String, val notes: String, val downloadUrl: String)

internal fun isNewerRelease(candidate: String, installed: String): Boolean {
    val pattern = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    val remote = pattern.matchEntire(candidate.trim()) ?: return false
    val local = pattern.matchEntire(installed.trim()) ?: return false
    val remoteParts = remote.groupValues[1].split('.').map { it.toLongOrNull() ?: return false }
    val localParts = local.groupValues[1].split('.').map { it.toLongOrNull() ?: return false }
    for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
        val difference = (remoteParts.getOrElse(index) { 0L }).compareTo(localParts.getOrElse(index) { 0L })
        if (difference != 0) return difference > 0
    }
    return remote.groupValues[2].isEmpty() && local.groupValues[2].isNotEmpty()
}

internal suspend fun latestAppRelease(): AppRelease? = withContext(Dispatchers.IO) {
    val connection = URL("https://api.github.com/repos/582856/MixFlipTool/releases/latest")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "MixFlipTool")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        when (connection.responseCode) {
            404 -> return@withContext null
            403, 429 -> throw IOException("GitHub 请求次数受限，请稍后重试。")
            200 -> Unit
            else -> throw IOException("GitHub 暂时不可用，请稍后重试。")
        }
        val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
        val assets = release.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).asSequence().map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val download = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
            ?: release.getString("html_url")
        check(download.startsWith("https://github.com/582856/MixFlipTool/releases/"))
        AppRelease(release.getString("tag_name"), release.optString("body").trim(), download)
    } finally {
        connection.disconnect()
    }
}
