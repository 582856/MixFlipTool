package com.parallelc.mixfliptool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import dalvik.system.PathClassLoader
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

class PlayerRhythmComponent(context: Context) : AutoCloseable {
    private val packageInfo = context.packageManager.getPackageInfo("com.netease.cloudmusic", 0)
    private val loader = object : PathClassLoader(packageInfo.applicationInfo!!.sourceDir, View::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (name == "com.netease.cloudmusic.utils.xt") com.netease.cloudmusic.utils.xt::class.java
            else super.loadClass(name, resolve)
    }
    private val type: Class<*>
    val view: View
    private val driverType: Class<*>
    private val driver: Any
    private val callback: Any
    private val fftMethod: java.lang.reflect.Method
    private var lastFallback = 0L
    private var lastBitmap: Bitmap? = null
    private var lastTop = false
    val density = context.resources.displayMetrics.density
    val width = (98f * density + .5f).toInt()
    val height = (64f * density + .5f).toInt()

    init {
        check(packageInfo.longVersionCode == 9006010L) { "原版律动组件暂仅适配网易云 9.6.10" }
        com.netease.cloudmusic.utils.xt.density = density
        type = loader.loadClass("com.netease.cloudmusic.module.miminiplayer.MiAnimViewRender")
        view = type.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType)
            .newInstance(context, null, 0) as View
        driverType = loader.loadClass("uf0.g0")
        driver = driverType.getConstructor().newInstance()
        driverType.getMethod("j", type).invoke(driver, view)
        callback = driverType.getMethod("e").invoke(driver)
        fftMethod = callback.javaClass.getMethod("a", FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // MiAnimViewRender uses a random recoil below the audio-driven target.
        // Preserve the original 98 x 64 dp geometry and white-under-color alpha layering.
        Log.i("MixFlipRhythm", "renderer=${view.javaClass.name}; originalApk=${packageInfo.applicationInfo!!.sourceDir}; size=${width}x$height")
    }

    fun update(state: JSONObject) {
        if (state.optString("rhythmSource") == "native_fft") {
            if (!state.has("fft")) return
            val bytes = Base64.decode(state.getString("fft"), Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val values = FloatArray(buffer.remaining())
            buffer.get(values)
            fftMethod.invoke(callback, values, state.getInt("sampleRate"), state.getInt("captureRate"))
        } else {
            val now = SystemClock.uptimeMillis()
            // The original player delivers targets about every 320 ms; its two animators draw between them.
            if (now - lastFallback >= 320L) {
                lastFallback = now
                val target = ceil(state.optDouble("level", 0.0).coerceIn(0.0, 1.0) * 12).toInt()
                type.getMethod("i", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(view, target, now)
            }
        }
    }

    fun updateArtwork(bitmap: Bitmap?, cameraOnTop: Boolean) {
        if (lastBitmap === bitmap && lastTop == cameraOnTop) return
        lastBitmap = bitmap
        lastTop = cameraOnTop
        val color = if (bitmap == null) Color.WHITE else runCatching {
            val y = (bitmap.height * if (cameraOnTop) .8f else .02f).toInt()
            val cropHeight = (bitmap.height * .18f).toInt().coerceAtLeast(1)
            val crop = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, cropHeight.coerceAtMost(bitmap.height - y))
            val paletteType = loader.loadClass("androidx.palette.graphics.Palette")
            val builder = paletteType.getMethod("from", Bitmap::class.java).invoke(null, crop)
            builder.javaClass.getMethod("clearFilters").invoke(builder)
            builder.javaClass.getMethod("maximumColorCount", Int::class.javaPrimitiveType).invoke(builder, 16)
            val palette = try { builder.javaClass.getMethod("generate").invoke(builder) } finally { crop.recycle() }
            val dominant = loader.loadClass("bg.h").getMethod("e", paletteType).invoke(null, palette)
            val selectorType = loader.loadClass("com.netease.cloudmusic.utils.il")
            val adjusted = selectorType.getMethod("b", Int::class.javaPrimitiveType)
                .invoke(selectorType.getField("a").get(null), dominant)
            val hsl = adjusted.javaClass.getMethod("getSecond").invoke(adjusted) as FloatArray
            val colors = loader.loadClass("uf0.e0")
            colors.getDeclaredMethod("F1", FloatArray::class.java).apply { isAccessible = true }
                .invoke(colors.getConstructor().newInstance(), hsl) as Int
        }.getOrElse {
            Log.w("MixFlipRhythm", "Original palette unavailable", it)
            Color.WHITE
        }
        type.getMethod("h", Int::class.javaPrimitiveType).invoke(view, color)
    }

    override fun close() {
        driverType.getMethod("h").invoke(driver)
        driverType.getMethod("j", type).invoke(driver, null)
    }
}
