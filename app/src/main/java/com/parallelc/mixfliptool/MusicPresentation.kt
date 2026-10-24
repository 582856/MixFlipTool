package com.parallelc.mixfliptool

import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.text.LineBreaker
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import android.icu.text.BreakIterator
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.view.animation.PathInterpolator
import kotlin.math.min
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*

class MusicPresentation(context: Context, val display: Display, private val onTrackCommand: (String) -> Unit) : Dialog(
    context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null),
    android.R.style.Theme_Material_NoActionBar
) {
    private val musicView = MusicView(this.context, ::setSleepBrightness)
    private var recording: Job? = null
    val rhythmError get() = musicView.rhythmError

    init { musicView.setTrackCommand(onTrackCommand) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setDecorFitsSystemWindows(false)
        }
        setContentView(musicView)
        var reported = false
        fun reportSurface() {
            if (!reported && isShowing) {
                reported = true
                onTrackCommand("surfaceReady")
            }
        }
        musicView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                musicView.post {
                    if (musicView.viewTreeObserver.isAlive) musicView.viewTreeObserver.removeOnDrawListener(this)
                    reportSurface()
                }
            }
        })
        // An off display may not produce a frame until its independent power group starts.
        musicView.postDelayed({ reportSurface() }, 250L)
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window?.insetsController?.hide(WindowInsets.Type.systemBars())
        window?.decorView?.post {
            runCatching {
                val decor = window!!.decorView
                if (!isShowing || !decor.isAttachedToWindow) return@post
                val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(decor) ?: return@post
                val surface = root.javaClass.getMethod("getSurfaceControl").invoke(root) as SurfaceControl
                if (!surface.isValid) return@post
                SurfaceControl.Transaction().use { transaction ->
                    // Xiaomi's compositor must recognize this surface as AOD content in Doze.
                    // The independent group now owns Doze; this legacy flag gates the primary backlight across all displays on bixi.
                    transaction.javaClass.getMethod("setLayerWithAOD", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(transaction, surface, false)
                    transaction.apply()
                }
            }.onFailure { android.util.Log.e("MixFlipModule", "Unable to mark outer AOD surface", it) }
        }
    }

    fun update(title: String, artist: String, album: String, artwork: Bitmap?, state: JSONObject) {
        musicView.setPlaybackState(state.optBoolean("dimmed", true), state.optBoolean("playing"))
        musicView.update(title, artist, album, artwork, state)
        if (state.optBoolean("active")) musicView.fadeContent(true)
    }

    internal fun setDisplayOptions(options: MusicDisplayOptions) = musicView.setDisplayOptions(options)

    fun fadeOut(onFinished: () -> Unit) = musicView.fadeContent(false, onFinished)

    fun recordFrames(seconds: Int) {
        if (recording?.isActive == true || !isShowing || musicView.width == 0) return
        recording = CoroutineScope(Dispatchers.Main.immediate).launch {
            val started = android.os.SystemClock.uptimeMillis()
            val directory = File(context.cacheDir, "mixflip-record-$started")
            val width = minOf(720, musicView.width) / 2 * 2
            val height = (musicView.height.toLong() * width / musicView.width).toInt() / 2 * 2
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            var frames = 0
            try {
                withContext(Dispatchers.IO) { check(directory.mkdirs()) }
                android.util.Log.i("MixFlipRecording", "start path=$directory size=${width}x$height")
                while (isActive && isShowing && android.os.SystemClock.uptimeMillis() - started < seconds.coerceIn(5, 45) * 1000L) {
                    val frameTime = android.os.SystemClock.uptimeMillis()
                    // Draw only our existing view; physical-display capture can stall this device's writeback engine.
                    canvas.save()
                    canvas.scale(width.toFloat() / musicView.width, height.toFloat() / musicView.height)
                    musicView.draw(canvas)
                    canvas.restore()
                    val name = "frame-%05d.jpg".format(java.util.Locale.US, frames)
                    withContext(Dispatchers.IO) {
                        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                        File(directory, "frames.csv").appendText("${frameTime - started},$name\n")
                    }
                    frames++
                    delay((50L - (android.os.SystemClock.uptimeMillis() - frameTime)).coerceAtLeast(1L))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                android.util.Log.e("MixFlipRecording", "recording failed", error)
            } finally {
                bitmap.recycle()
                android.util.Log.i("MixFlipRecording", "finished path=$directory frames=$frames elapsed=${android.os.SystemClock.uptimeMillis() - started}")
            }
        }
    }

    override fun dismiss() {
        recording?.cancel()
        super.dismiss()
    }

    private fun setSleepBrightness(sleeping: Boolean) {
        window?.let {
            val attributes = it.attributes
            attributes.screenBrightness = if (sleeping) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            it.attributes = attributes
        }
    }

    private class MusicView(context: Context, private val onSleepChanged: (Boolean) -> Unit) : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private var systemTypeface = TextView(context).typeface
        private var emphasisTypeface = Typeface.create(systemTypeface, 600, false)
        private var backgroundColors = intArrayOf(0xff41474d.toInt(), 0xff15191e.toInt())
        private var background: Shader? = null
        private var title = ""
        private var artist = ""
        private var album = ""
        private var infoColumns = arrayOf(verticalGlyphs("未知专辑"), verticalGlyphs("正在播放"), verticalGlyphs("未知歌手"))
        private var artwork: Bitmap? = null
        private var previousArtwork: Bitmap? = null
        private var previousBackgroundColors: IntArray? = null
        private var artworkChangedAt = 0L
        private var previousInfoColumns: Array<List<String>>? = null
        private var infoChangedAt = 0L
        private val trackInterpolator = PathInterpolator(.2f, 0f, .2f, 1f)
        private var lyric = ""
        private var nextLyric = ""
        private var marqueeStarted = android.os.SystemClock.uptimeMillis()
        private var currentLyricLayout: StaticLayout? = null
        private var nextLyricLayout: StaticLayout? = null
        private var previousLyricLayout: StaticLayout? = null
        private var previousNextLyricLayout: StaticLayout? = null
        private var lyricChangedAt = 0L
        private var nextLyricChangedAt = 0L
        private val componentResult = runCatching { PlayerRhythmComponent(context) }
        private val component = componentResult.getOrNull()
        val rhythmError = if (component != null) "" else "原版律动组件暂不可用，请确认已安装网易云 9.6.10"
        private var cameraOnTop = false
        private val cameraBounds = RectF()
        private val coverRect = RectF()
        private val infoRect = RectF()
        private val lyricRect = RectF()
        private var rotation = 0f
        private var lastFrame = 0L
        private var sleeping = true
        private var displayOptions = MusicDisplayOptions()
        private var playing = false
        private var playbackInitialized = false
        private var awakeProgress = 0f
        private var transition: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var controlGesture = false
        private var contentProgress = 0f
        private var contentVisible = false
        private var contentAnimation: ValueAnimator? = null
        private var onTrackCommand: (String) -> Unit = {}
        private val controls = listOf("previous", "playPause", "next").mapIndexed { index, command ->
            ImageButton(context).apply {
                contentDescription = listOf("上一首", "播放", "下一首")[index]
                setImageDrawable(mediaIcon(command))
                imageTintList = ColorStateList.valueOf(0xfff5f5f3.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (index == 1) 0x20ffffff else Color.TRANSPARENT)
                    }, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                visibility = INVISIBLE
                setOnClickListener {
                    if (contentVisible && displayOptions.shows(MusicDisplayOptions.CONTROLS, sleeping)) onTrackCommand(command)
                }
            }
        }

        private fun mediaIcon(command: String): ShapeDrawable {
            val path = Path().apply {
                when (command) {
                    "pause" -> {
                        addRect(20f, 16f, 30f, 56f, Path.Direction.CW)
                        addRect(42f, 16f, 52f, 56f, Path.Direction.CW)
                    }
                    "previous", "next" -> {
                        addRect(14f, 16f, 20f, 56f, Path.Direction.CW)
                        moveTo(50f, 16f); lineTo(50f, 56f); lineTo(22f, 36f); close()
                        if (command == "next") transform(Matrix().apply { setScale(-1f, 1f, 36f, 36f) })
                    }
                    else -> { moveTo(24f, 16f); lineTo(56f, 36f); lineTo(24f, 56f); close() }
                }
            }
            return ShapeDrawable(PathShape(path, 72f, 72f)).apply {
                intrinsicWidth = 72
                intrinsicHeight = 72
                paint.color = 0xfff5f5f3.toInt()
                paint.isAntiAlias = true
            }
        }
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                // Resolve the toggle in the controller, not against an asynchronously rendered frame.
                android.util.Log.i("MixFlipGesture", "doubleTap time=${event.eventTime} sleeping=$sleeping")
                onTrackCommand("toggle")
                return true
            }
        })

        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            component?.let { addView(it.view, LayoutParams(it.width, it.height)) }
            controls.forEach { addView(it, LayoutParams(0, 0)) }
            rotation = 0f
            componentResult.exceptionOrNull()?.let { android.util.Log.e("MixFlipRhythm", "Original component unavailable", it) }
        }

        // Consume the whole gesture so touches never operate an app underneath the display-only page.
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                controlGesture = contentVisible && controls.any {
                    it.visibility == VISIBLE && it.isEnabled &&
                        event.x >= it.left && event.x < it.right && event.y >= it.top && event.y < it.bottom
                }
                if (controlGesture) onTrackCommand("touch")
            }
            return !controlGesture
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!contentVisible) return true
            if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                android.util.Log.i("MixFlipGesture", "action=${event.actionMasked} time=${event.eventTime} down=${event.downTime} x=${event.x} y=${event.y} sleeping=$sleeping")
            }
            gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    onTrackCommand("touch")
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (kotlin.math.abs(dx) > 90f * resources.displayMetrics.density && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                        if (!isSleeping()) onTrackCommand(if (dx < 0) "next" else "previous")
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return true
        }

        fun setTrackCommand(command: (String) -> Unit) { onTrackCommand = command }

        fun setDisplayOptions(options: MusicDisplayOptions) {
            if (displayOptions == options) return
            displayOptions = options
            updateRhythmVisibility()
            invalidate()
        }

        fun fadeContent(visible: Boolean, onFinished: () -> Unit = {}) {
            if (contentVisible == visible) {
                if (contentAnimation == null && !visible) onFinished()
                return
            }
            contentVisible = visible
            android.util.Log.i("MixFlipModule", "content ${if (visible) "enter" else "exit"} started")
            contentAnimation?.let { contentAnimation = null; it.cancel() }
            val animation = ValueAnimator.ofFloat(contentProgress, if (visible) 1f else 0f).apply {
                duration = if (visible) 360L else 240L
                interpolator = trackInterpolator
                addUpdateListener { contentProgress = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (contentAnimation !== animation) return
                        contentAnimation = null
                        android.util.Log.i("MixFlipModule", "content ${if (visible) "enter" else "exit"} finished")
                        onFinished()
                    }
                })
            }
            contentAnimation = animation
            animation.start()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            if (contentProgress == 0f) return
            if (contentProgress == 1f) { super.draw(canvas); return }
            val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255f * contentProgress).toInt())
            super.draw(canvas)
            canvas.restoreToCount(layer)
        }

        fun setPlaybackState(dimmed: Boolean, isPlaying: Boolean) {
            if (playbackInitialized && sleeping == dimmed && playing == isPlaying) return
            val modeChanged = sleeping != dimmed
            if (modeChanged) {
                previousArtwork = null
                previousBackgroundColors = null
                previousInfoColumns = null
            }
            sleeping = dimmed
            playing = isPlaying
            controls[1].setImageDrawable(mediaIcon(if (playing) "pause" else "play"))
            controls[1].contentDescription = if (playing) "暂停" else "播放"
            lastFrame = 0L
            if (!playbackInitialized) {
                playbackInitialized = true
                awakeProgress = if (sleeping) 0f else 1f
                onSleepChanged(sleeping)
            } else if (modeChanged) {
                transition?.let { transition = null; it.cancel() }
                if (!sleeping) onSleepChanged(false)
                val animation = ValueAnimator.ofFloat(awakeProgress, if (sleeping) 0f else 1f).apply {
                    duration = 480L
                    interpolator = PathInterpolator(.2f, 0f, .2f, 1f)
                    addUpdateListener {
                        awakeProgress = it.animatedValue as Float
                        updateRhythmVisibility()
                        invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (transition !== animation) return
                            transition = null
                            onSleepChanged(sleeping)
                            updateRhythmVisibility()
                            invalidate()
                        }
                    })
                }
                transition = animation
                animation.start()
            }
            updateRhythmVisibility()
            invalidate()
        }

        private fun updateRhythmVisibility() {
            controls.forEach { button ->
                val alpha = displayOptions.alpha(MusicDisplayOptions.CONTROLS, awakeProgress)
                button.alpha = alpha * (.55f + .45f * awakeProgress)
                button.visibility = if (alpha > 0f) VISIBLE else INVISIBLE
                button.isEnabled = displayOptions.shows(MusicDisplayOptions.CONTROLS, sleeping)
            }
            component?.view?.apply {
                alpha = displayOptions.alpha(MusicDisplayOptions.RHYTHM, awakeProgress) * (.55f + .45f * awakeProgress)
                visibility = if (alpha > 0f && playing) VISIBLE else INVISIBLE
            }
        }

        private fun isSleeping() = sleeping

        fun update(newTitle: String, newArtist: String, newAlbum: String, bitmap: Bitmap?, state: JSONObject) {
            var changed = false
            if (title != newTitle || artist != newArtist || album != newAlbum) {
                previousInfoColumns = infoColumns.takeIf { title.isNotBlank() && !sleeping && awakeProgress == 1f }
                infoChangedAt = android.os.SystemClock.uptimeMillis()
                title = newTitle
                artist = newArtist
                album = newAlbum
                infoColumns = arrayOf(verticalGlyphs(album.ifBlank { "未知专辑" }),
                    verticalGlyphs(title.ifBlank { "正在播放" }), verticalGlyphs(artist.ifBlank { "未知歌手" }))
                marqueeStarted = android.os.SystemClock.uptimeMillis()
                changed = true
            }
            val trackChanged = changed
            if (lyric != state.optString("lyric") || trackChanged) {
                previousLyricLayout = currentLyricLayout.takeIf { awakeProgress > 0f }
                lyricChangedAt = android.os.SystemClock.uptimeMillis()
                lyric = state.optString("lyric")
                currentLyricLayout = null
                changed = true
            }
            if (nextLyric != state.optString("nextLyric") || trackChanged) {
                previousNextLyricLayout = nextLyricLayout.takeIf { awakeProgress > 0f }
                nextLyricChangedAt = android.os.SystemClock.uptimeMillis()
                nextLyric = state.optString("nextLyric")
                nextLyricLayout = null
                changed = true
            }
            if (artwork !== bitmap) {
                val animate = artwork != null && !sleeping && awakeProgress == 1f
                previousArtwork = artwork.takeIf { animate }
                previousBackgroundColors = backgroundColors.copyOf().takeIf { animate }
                artworkChangedAt = android.os.SystemClock.uptimeMillis()
                artwork = bitmap
                updateBackground(bitmap)
                component?.updateArtwork(bitmap, cameraOnTop)
                changed = true
            }
            if (playing && displayOptions.alpha(MusicDisplayOptions.RHYTHM, awakeProgress) > 0f) component?.update(state)
            if (changed) invalidate()
        }

        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            requestLayout()
            return super.onApplyWindowInsets(insets)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val cutout = rootWindowInsets?.displayCutout?.boundingRects?.maxByOrNull { it.width() * it.height() }
            cameraOnTop = cutout != null && cutout.centerY() < height / 2
            val density = resources.displayMetrics.density
            val margin = (9f * density + .5f).toInt()
            val containerWidth = (105f * density + .5f).toInt()
            val containerHeight = (216f * density + .5f).toInt()
            val x = if (cameraOnTop) margin else width - margin - containerWidth
            val y = if (cameraOnTop) margin else height - margin - containerHeight
            cameraBounds.set(x.toFloat(), y.toFloat(), (x + containerWidth).toFloat(), (y + containerHeight).toFloat())
            val scale = width / 1208f
            val contentTop = if (cameraOnTop) cameraBounds.bottom + 70f * scale else 80f * scale
            val contentBottom = if (cameraOnTop) height - 80f * scale else cameraBounds.top - 70f * scale
            infoRect.set(cameraBounds.left + 20f * scale, contentTop,
                cameraBounds.right + 10f * scale, contentBottom)
            val contentLeft = if (cameraOnTop) cameraBounds.right else 0f
            val contentRight = if (cameraOnTop) width.toFloat() else cameraBounds.left
            val coverSize = min(width * .48f, height * .42f)
            val coverCenterX = (contentLeft + contentRight) / 2f
            val coverCenterY = height * if (cameraOnTop) .36f else .40f
            coverRect.set(coverCenterX - coverSize / 2f, coverCenterY - coverSize / 2f,
                coverCenterX + coverSize / 2f, coverCenterY + coverSize / 2f)
            val lyricLeft = contentLeft + 56f * scale
            val lyricRight = contentRight - 56f * scale
            val controlSize = maxOf(48f * density, 144f * scale).toInt()
            val controlTop = (height - 80f * scale - controlSize).toInt()
            controls.forEachIndexed { index, button ->
                val controlLeft = (coverCenterX + (index - 1) * 180f * scale - controlSize / 2f).toInt()
                val padding = (38f * scale).toInt()
                button.setPadding(padding, padding, padding, padding)
                button.layout(controlLeft, controlTop, controlLeft + controlSize, controlTop + controlSize)
            }
            lyricRect.set(lyricLeft, coverRect.bottom + 40f * scale, lyricRight, controlTop - 20f * scale)
            currentLyricLayout = null
            nextLyricLayout = null
            previousLyricLayout = null
            previousNextLyricLayout = null
            component?.let {
                val childX = x + (containerWidth - it.width) / 2
                val childY = y + (containerHeight - it.height) / 2
                it.view.layout(childX, childY, childX + it.width, childY + it.height)
                it.updateArtwork(artwork, cameraOnTop)
            }
        }

        override fun onDetachedFromWindow() {
            contentAnimation?.let { contentAnimation = null; it.cancel() }
            transition?.let { transition = null; it.cancel() }
            previousArtwork = null
            previousBackgroundColors = null
            previousInfoColumns = null
            component?.close()
            super.onDetachedFromWindow()
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            systemTypeface = TextView(context).typeface
            emphasisTypeface = Typeface.create(systemTypeface, 600, false)
            currentLyricLayout = null
            nextLyricLayout = null
            previousLyricLayout = null
            previousNextLyricLayout = null
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            background = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val scale = w / 1208f
            val fontScale = scale * resources.configuration.fontScale
            val now = android.os.SystemClock.uptimeMillis()
            val artworkProgress = trackInterpolator.getInterpolation(((now - artworkChangedAt) / 420f).coerceIn(0f, 1f))
            if (artworkProgress >= 1f) {
                previousArtwork = null
                previousBackgroundColors = null
            }
            val coverAlpha = displayOptions.alpha(MusicDisplayOptions.COVER, awakeProgress)
            if (playing && awakeProgress > 0f && coverAlpha > 0f) {
                if (lastFrame != 0L) rotation = (rotation + (now - lastFrame).coerceAtMost(64L) * .015f * awakeProgress) % 360f
                lastFrame = now
            } else lastFrame = 0L
            canvas.drawColor(Color.BLACK)
            paint.alpha = (255f * awakeProgress).toInt()
            if (awakeProgress > 0f) {
                paint.shader = previousBackgroundColors?.let { old ->
                    LinearGradient(w, 0f, 0f, h, IntArray(2) {
                        ColorUtils.blendARGB(old[it], backgroundColors[it], artworkProgress)
                    }, null, Shader.TileMode.CLAMP)
                } ?: (background ?: LinearGradient(w, 0f, 0f, h,
                    backgroundColors, null, Shader.TileMode.CLAMP).also { background = it })
                canvas.drawRect(0f, 0f, w, h, paint)
                paint.shader = null
            }
            paint.alpha = 255
            (artwork ?: previousArtwork)?.takeIf { coverAlpha > 0f }?.let { bitmap ->
                val coverLayer = canvas.saveLayerAlpha(coverRect.left - 40f * scale, coverRect.top - 60f * scale,
                    coverRect.right + 40f * scale, coverRect.bottom + 48f * scale, (255f * coverAlpha).toInt())
                val centerX = coverRect.centerX()
                val centerY = coverRect.centerY()
                val size = coverRect.width()
                paint.color = 0xff18191b.toInt()
                paint.setShadowLayer(28f * scale, 0f, 16f * scale, 0x66000000)
                canvas.drawCircle(centerX, centerY, size / 2f, paint)
                paint.clearShadowLayer()
                canvas.save()
                canvas.rotate(rotation, centerX, centerY)
                val imageSize = size * .92f
                canvas.clipPath(Path().apply { addCircle(centerX, centerY, imageSize / 2f, Path.Direction.CW) })
                paint.alpha = (120f + 135f * awakeProgress).toInt()
                if (artwork == null) paint.alpha = (paint.alpha * (1f - artworkProgress)).toInt()
                val imageRect = RectF(centerX - imageSize/2, centerY - imageSize/2,
                    centerX + imageSize/2, centerY + imageSize/2)
                previousArtwork?.takeIf { artwork != null }?.let {
                    canvas.drawBitmap(it, null, imageRect, paint)
                    paint.alpha = (paint.alpha * artworkProgress).toInt()
                }
                canvas.drawBitmap(bitmap, null, imageRect, paint)
                paint.alpha = 255
                canvas.restore()
                paint.color = 0x338b8b8b
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f * scale
                for (ring in 1..5) {
                    val radius = size * (.462f + ring * .006f)
                    canvas.drawCircle(centerX, centerY, radius, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = 0xff18181a.toInt()
                canvas.drawCircle(centerX, centerY, size * .024f, paint)
                paint.color = 0xffb5b5b5.toInt()
                canvas.drawCircle(centerX, centerY, size * .008f, paint)
                if (awakeProgress > 0f) {
                    canvas.saveLayerAlpha(coverRect.left, coverRect.top - 60f * scale,
                        coverRect.right + 40f * scale, coverRect.bottom, (255f * awakeProgress).toInt())
                    drawTonearm(canvas, scale)
                    canvas.restore()
                }
                canvas.restoreToCount(coverLayer)
            }
            val infoProgress = ((now - infoChangedAt) / 360f).coerceIn(0f, 1f)
            val oldInfo = previousInfoColumns
            if (oldInfo != null && infoProgress < 1f) {
                val opacity = if (infoProgress < .5f) 1f - infoProgress * 2f else (infoProgress - .5f) * 2f
                canvas.saveLayerAlpha(infoRect.left, infoRect.top, infoRect.right, infoRect.bottom, (255f * opacity).toInt())
                drawInfo(canvas, fontScale, if (infoProgress < .5f) oldInfo else infoColumns)
                canvas.restore()
            } else {
                previousInfoColumns = null
                drawInfo(canvas, fontScale)
            }
            if (displayOptions.alpha(MusicDisplayOptions.CURRENT_LYRIC, awakeProgress) > 0f ||
                displayOptions.alpha(MusicDisplayOptions.NEXT_LYRIC, awakeProgress) > 0f) {
                drawLyrics(canvas, scale, fontScale)
            }
            paint.color = Color.BLACK
            canvas.drawRoundRect(cameraBounds, cameraBounds.width()/2, cameraBounds.width()/2, paint)
            if (awakeProgress > 0f || transition != null) postInvalidateDelayed(33L)
        }

        private fun drawLyrics(canvas: Canvas, scale: Float, fontScale: Float) {
            if (lyricRect.width() <= 0f) return
            fun layout(value: String, size: Float, bold: Boolean): StaticLayout {
                val lyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size * fontScale
                    typeface = if (bold) emphasisTypeface else systemTypeface
                    color = 0xfff5f5f3.toInt()
                }
                return StaticLayout.Builder.obtain(value, 0, value.length, lyricPaint, lyricRect.width().toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(true)
                    .setLineSpacing(6f * fontScale, 1f)
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            }
            val current = currentLyricLayout ?: layout(lyric, 62f, true).also { currentLyricLayout = it }
            val next = nextLyricLayout ?: layout(nextLyric, 46.5f, false).also { nextLyricLayout = it }
            fun slotHeight(value: StaticLayout) = value.paint.fontMetrics.let { it.bottom - it.top } * 2f + 6f * fontScale
            val currentHeight = slotHeight(current)
            val nextHeight = slotHeight(next)
            val now = android.os.SystemClock.uptimeMillis()
            fun drawLine(value: StaticLayout, previous: StaticLayout?, changedAt: Long,
                         top: Float, height: Float, alpha: Float, visibility: Float): Boolean {
                val progress = if (previous == null) 1f else ((now - changedAt) / 200f).coerceIn(0f, 1f)
                fun draw(value: StaticLayout, opacity: Float) {
                    if (opacity <= 0f) return
                    value.paint.alpha = (alpha * visibility * opacity).toInt()
                    canvas.save()
                    canvas.translate(0f, top + (height - value.height) / 2f)
                    value.draw(canvas)
                    canvas.restore()
                }
                if (previous != null && progress < .5f) draw(previous, 1f - progress * 2f)
                else draw(value, if (previous == null) 1f else (progress - .5f) * 2f)
                return progress >= 1f
            }
            canvas.save()
            canvas.clipRect(lyricRect)
            canvas.translate(lyricRect.left, lyricRect.top)
            if (drawLine(current, previousLyricLayout, lyricChangedAt, 0f, currentHeight, 255f,
                    displayOptions.alpha(MusicDisplayOptions.CURRENT_LYRIC, awakeProgress))) previousLyricLayout = null
            if (drawLine(next, previousNextLyricLayout, nextLyricChangedAt,
                    currentHeight + 16f * scale, nextHeight, 145f,
                    displayOptions.alpha(MusicDisplayOptions.NEXT_LYRIC, awakeProgress))) previousNextLyricLayout = null
            canvas.restore()
        }

        private fun verticalGlyphs(value: String): List<String> {
            val iterator = BreakIterator.getCharacterInstance().apply { setText(value) }
            val glyphs = ArrayList<String>()
            val word = StringBuilder()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val glyph = value.substring(start, end)
                val codePoint = glyph.codePointAt(0)
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN ||
                    Character.isDigit(codePoint) || (word.isNotEmpty() && glyph in listOf("'", "’", "-"))) {
                    word.append(glyph)
                } else {
                    if (word.isNotEmpty()) { glyphs.add(word.toString()); word.setLength(0) }
                    if (!glyph.isBlank()) glyphs.add(glyph)
                }
                start = end
                end = iterator.next()
            }
            if (word.isNotEmpty()) glyphs.add(word.toString())
            val rows = ArrayList<String>()
            var prefix = ""
            for (glyph in glyphs) {
                val kind = Character.getType(glyph.codePointAt(0))
                if (kind == Character.START_PUNCTUATION.toInt() || kind == Character.INITIAL_QUOTE_PUNCTUATION.toInt()) {
                    prefix += glyph
                } else if (glyph.codePoints().allMatch {
                        Character.getType(it) in setOf(Character.END_PUNCTUATION.toInt(),
                            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt(),
                            Character.DASH_PUNCTUATION.toInt(), Character.CONNECTOR_PUNCTUATION.toInt(),
                            Character.MATH_SYMBOL.toInt(), Character.CURRENCY_SYMBOL.toInt())
                    }) {
                    if (rows.isEmpty()) prefix += glyph else {
                        rows[rows.lastIndex] += prefix + glyph
                        prefix = ""
                    }
                } else {
                    rows.add(prefix + glyph)
                    prefix = ""
                }
            }
            if (prefix.isNotEmpty()) {
                if (rows.isEmpty()) rows.add(prefix) else rows[rows.lastIndex] += prefix
            }
            return rows
        }

        private fun drawVerticalColumn(canvas: Canvas, glyphs: List<String>, bounds: RectF,
                                       size: Float, color: Int, bold: Boolean) {
            if (glyphs.isEmpty() || bounds.width() <= 0f || bounds.height() <= 0f) return
            text.textSize = min(size, bounds.width() * .8f)
            text.color = color
            text.typeface = if (bold) emphasisTypeface else systemTypeface
            text.textAlign = Paint.Align.CENTER
            val columnSize = text.textSize
            val metrics = text.fontMetrics
            val step = maxOf(text.textSize * 1.25f, metrics.descent - metrics.ascent)
            val textHeight = glyphs.size * step
            val overflow = (textHeight - bounds.height()).coerceAtLeast(0f)
            val travel = overflow / (width / 1208f * .026f)
            val elapsed = if (awakeProgress == 0f || overflow == 0f) 0f else
                ((android.os.SystemClock.uptimeMillis() - marqueeStarted) % (travel * 2 + 2800f)).toFloat()
            val offset = when {
                elapsed < 1400f -> 0f
                elapsed < 1400f + travel -> overflow * (elapsed - 1400f) / travel
                elapsed < 2800f + travel -> overflow
                else -> overflow * (1f - (elapsed - 2800f - travel) / travel)
            }
            val top = if (overflow == 0f) bounds.centerY() - textHeight / 2f else bounds.top - offset * awakeProgress
            canvas.save()
            canvas.clipRect(bounds)
            glyphs.forEachIndexed { index, glyph ->
                text.textSize = columnSize
                val lineWidth = text.measureText(glyph)
                if (lineWidth > bounds.width() * .9f) text.textSize *= bounds.width() * .9f / lineWidth
                val lineMetrics = text.fontMetrics
                val baseline = top + (index + .5f) * step - (lineMetrics.ascent + lineMetrics.descent) / 2f
                canvas.drawText(glyph, bounds.centerX(), baseline, text)
            }
            canvas.restore()
        }

        private fun drawInfo(canvas: Canvas, fontScale: Float, columns: Array<List<String>> = infoColumns) {
            val sizes = floatArrayOf(40f, 60f, 42f)
            val dimColors = intArrayOf(0xff888888.toInt(), 0xffbbbbbb.toInt(), 0xff888888.toInt())
            val brightColors = intArrayOf(0x99f5f5f3.toInt(), 0xfff5f5f3.toInt(), 0xbbf5f5f3.toInt())
            val columnWidth = infoRect.width() / 3f
            columns.forEachIndexed { index, glyphs ->
                val alpha = displayOptions.alpha(1 shl index, awakeProgress)
                if (alpha <= 0f) return@forEachIndexed
                val bounds = RectF(infoRect.left + index * columnWidth, infoRect.top,
                    infoRect.left + (index + 1) * columnWidth, infoRect.bottom)
                drawVerticalColumn(canvas, glyphs, bounds,
                    sizes[index] * fontScale, ColorUtils.blendARGB(dimColors[index], brightColors[index], awakeProgress).let {
                        ColorUtils.setAlphaComponent(it, (Color.alpha(it) * alpha).toInt())
                    },
                    index == 1)
            }
        }

        private fun updateBackground(bitmap: Bitmap?) {
            val bins = IntArray(4096)
            val hsl = FloatArray(3)
            if (bitmap != null) for (y in 0 until 24) for (x in 0 until 24) {
                val color = bitmap.getPixel(x * bitmap.width / 24, y * bitmap.height / 24)
                ColorUtils.colorToHSL(color, hsl)
                if (hsl[2] in .08f.. .92f) {
                    val bin = (Color.red(color) shr 4 shl 8) or (Color.green(color) shr 4 shl 4) or (Color.blue(color) shr 4)
                    bins[bin] += (1 + hsl[1] * 3).toInt()
                }
            }
            val dominant = bins.indices.maxByOrNull { bins[it] } ?: 0
            val color = if (bins[dominant] == 0) 0xff515b66.toInt() else
                Color.rgb((dominant shr 8) * 16 + 8, (dominant shr 4 and 15) * 16 + 8, (dominant and 15) * 16 + 8)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] = (hsl[1] * .65f).coerceIn(.08f, .42f)
            hsl[2] = .28f
            val top = ColorUtils.HSLToColor(hsl)
            hsl[2] = .085f
            backgroundColors = intArrayOf(top, ColorUtils.HSLToColor(hsl))
            background = null
        }

        private fun drawTonearm(canvas: Canvas, scale: Float) {
            val pivotX = coverRect.right + 8f * scale
            val pivotY = coverRect.top - 28f * scale
            val tipX = coverRect.centerX() + coverRect.width() * .24f
            val tipY = coverRect.centerY() - coverRect.height() * .20f
            paint.color = 0xff242629.toInt()
            canvas.drawCircle(pivotX, pivotY, 18f * scale, paint)
            paint.color = 0xffbabebd.toInt()
            canvas.drawCircle(pivotX, pivotY, 10f * scale, paint)
            paint.color = 0xffd8dcda.toInt()
            paint.strokeWidth = 5.5f * scale
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            canvas.drawPath(Path().apply {
                moveTo(pivotX, pivotY)
                lineTo(pivotX - 36f * scale, pivotY + 108f * scale)
                lineTo(tipX, tipY)
            }, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 12f * scale
            canvas.drawLine(tipX, tipY, tipX - 16f * scale, tipY + 20f * scale, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
    }
}
