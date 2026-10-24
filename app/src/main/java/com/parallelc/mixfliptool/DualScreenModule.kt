package com.parallelc.mixfliptool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import de.robv.android.xposed.XSharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.atomic.AtomicBoolean

object DualScreenModule {
    private val installed = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transitions = Mutex()
    private var job: Job? = null
    private var enabled = false
    private var displayOptions = MusicDisplayOptions()

    @JvmStatic fun install(attached: Context) {
        if (!installed.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            try {
                val context = attached.applicationContext ?: attached
                HiddenApiBypass.addHiddenApiExemptions("")
                Toast.makeText(context, "Vector 注入成功: com.android.systemui", Toast.LENGTH_LONG).show()
                Log.i("MixFlipModule", "injected package=${context.packageName}; pid=${android.os.Process.myPid()}")
                context.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        if (intent.action == DualScreenService.CONFIG_ACTION) {
                            if (intent.hasExtra(MusicDisplayOptions.BRIGHT_KEY)) {
                                displayOptions = MusicDisplayOptions.read(intent::getIntExtra)
                            }
                            setEnabled(context, intent.getBooleanExtra("enabled", false))
                        } else if (intent.action == Intent.ACTION_USER_UNLOCKED) loadSettings(context)
                    }
                }, IntentFilter(DualScreenService.CONFIG_ACTION).apply { addAction(Intent.ACTION_USER_UNLOCKED) },
                    android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
                loadSettings(context)
            } catch (error: Throwable) {
                Log.e("MixFlipModule", "Module initialization failed", error)
            }
        }
    }

    private fun loadSettings(context: Context) = scope.launch {
        val config = withContext(Dispatchers.IO) {
            runCatching {
                XSharedPreferences(DualScreenService.MODULE_PACKAGE, "dual_screen_module").let {
                    it.reload()
                    check(it.contains("enabled"))
                    it.getBoolean("enabled", false) to MusicDisplayOptions.read(it::getInt)
                }
            }.getOrElse {
                // Read once at host startup; no app service or recurring provider connection is needed.
                runCatching {
                    val saved = context.contentResolver.call(Uri.parse("content://${DualScreenService.MODULE_PACKAGE}.privileged"),
                        "dualScreenConfig", null, null) ?: android.os.Bundle()
                    saved.getBoolean("enabled") to MusicDisplayOptions.read(saved::getInt)
                }.getOrDefault(false to MusicDisplayOptions())
            }
        }
        displayOptions = config.second
        setEnabled(context, config.first)
    }

    private fun setEnabled(context: Context, wanted: Boolean) = scope.launch {
        transitions.withLock {
            if (wanted != enabled || wanted && job?.isActive != true) {
                job?.cancelAndJoin()
                enabled = wanted
                job = if (wanted) scope.launch { runMusic(context) } else null
            }
            sendStatus(context)
        }
    }

    private fun sendStatus(context: Context, error: String = "") {
        context.sendBroadcast(Intent(DualScreenService.STATUS_ACTION).setPackage(DualScreenService.MODULE_PACKAGE)
            .putExtra("enabled", enabled).putExtra("error", error), android.Manifest.permission.DUMP)
    }

    private suspend fun runMusic(context: Context) {
        var controller: DualScreenController? = null
        var presentation: MusicPresentation? = null
        var artwork: Bitmap? = null
        val commands = Channel<String>(Channel.UNLIMITED)
        val displays = context.getSystemService(DisplayManager::class.java)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) {
                if (presentation?.display?.displayId != displayId) return
                // The vendor can reuse the same display ID after destroying its window tokens.
                Log.i("MixFlipModule", "outer display removed; discarding its presentation")
                presentation?.dismiss()
                presentation = null
                commands.trySend("surfaceLost")
            }
        }
        val hardwareGesture = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == OuterDisplayPowerHooks.GESTURE_ACTION) commands.trySend("wake")
                else if (intent.action == "com.parallelc.mixfliptool.RECORD_OUTER") {
                    presentation?.recordFrames(intent.getIntExtra("seconds", 30))
                    commands.trySend("wake")
                }
            }
        }
        var gestureRegistered = false
        var displayRegistered = false
        try {
            displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            displayRegistered = true
            context.registerReceiver(hardwareGesture, IntentFilter(OuterDisplayPowerHooks.GESTURE_ACTION).apply {
                addAction("com.parallelc.mixfliptool.RECORD_OUTER")
            },
                android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
            gestureRegistered = true
            controller = withContext(Dispatchers.IO) { DualScreenController(context) }
            var nextStatus = 0L
            var active = false
            var displayRetries = 0
            var lastMode = ""
            while (currentCoroutineContext().isActive) {
                val started = SystemClock.elapsedRealtime()
                val command = commands.tryReceive().getOrNull()
                val full = started >= nextStatus || !active
                controller.displayOptions = displayOptions
                val state = withContext(Dispatchers.IO) {
                    controller.request(command ?: if (full) "on" else "sample")
                }
                if (full && command == null) nextStatus = SystemClock.elapsedRealtime() + 500L
                active = state.optBoolean("active")
                val dimmed = state.optBoolean("dimmed", true)
                val playing = state.optBoolean("playing")
                if (state.has("art")) {
                    val encoded = state.getString("art")
                    artwork = withContext(Dispatchers.Default) {
                        if (encoded.isEmpty()) null else Base64.decode(encoded, Base64.NO_WRAP).let {
                            BitmapFactory.decodeByteArray(it, 0, it.size)
                        }
                    }
                }
                if (state.optBoolean("exiting")) {
                    presentation?.fadeOut { commands.trySend("exitComplete") }
                        ?: commands.trySend("exitComplete")
                } else if ((active || state.optBoolean("preparing")) && state.optBoolean("hasTrack")) {
                    val display = displays.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
                    if (display != null) {
                        if (presentation?.display?.displayId != display.displayId || presentation?.isShowing != true) {
                            presentation?.dismiss()
                            val candidate = MusicPresentation(context, display) { commands.trySend(it) }
                            try {
                                candidate.setDisplayOptions(displayOptions)
                                candidate.update(state.optString("title"), state.optString("artist"), state.optString("album"), artwork, state)
                                candidate.show()
                                presentation = candidate
                                Log.i("MixFlipModule", "outer presentation created display=${display.displayId}")
                                displayRetries = 0
                            } catch (error: WindowManager.InvalidDisplayException) {
                                candidate.dismiss()
                                if (++displayRetries >= 12) throw error
                                delay(250)
                                continue
                            }
                        }
                        presentation?.setDisplayOptions(displayOptions)
                        presentation?.update(state.optString("title"), state.optString("artist"), state.optString("album"), artwork, state)
                    } else if (++displayRetries >= 12) error("外屏暂不可用，请重新展开手机后开启")
                } else {
                    presentation?.dismiss()
                    presentation = null
                    displayRetries = 0
                }
                val mode = "active=$active preparing=${state.optBoolean("preparing")} exiting=${state.optBoolean("exiting")} dimmed=$dimmed playing=$playing panel=${state.optInt("panelState")} rhythm=${state.optString("rhythmSource")}"
                if (mode != lastMode) {
                    lastMode = mode
                    Log.i("MixFlipModule", mode)
                }
                val interval = if (active && playing && (!dimmed || displayOptions.shows(MusicDisplayOptions.RHYTHM, true))) 33L else 250L
                delay((interval - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1L))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e("MixFlipModule", "Music display stopped", error)
            sendStatus(context, error.message ?: "外屏启动失败，请检查模块作用域并重新开启")
        } finally {
            if (displayRegistered) displays.unregisterDisplayListener(displayListener)
            if (gestureRegistered) context.unregisterReceiver(hardwareGesture)
            commands.close()
            runCatching { presentation?.dismiss() }
            withContext(NonCancellable + Dispatchers.IO) { controller?.close() }
        }
    }
}
