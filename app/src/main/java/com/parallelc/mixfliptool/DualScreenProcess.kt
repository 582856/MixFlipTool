package com.parallelc.mixfliptool

import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

object DualScreenProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        var controller: DualScreenController? = null
        try {
            Looper.prepareMainLooper()
            HiddenApiBypass.addHiddenApiExemptions("")
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("initializeMainlineModules").invoke(null)
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as android.content.Context
            controller = DualScreenController(context)
            // The vendor rejects ordinary Presentation windows after the outer display is recreated.
            // Authorize only our own display-only overlay using the already granted privileged backend.
            val overlayGrant = ProcessBuilder("cmd", "appops", "set", "com.parallelc.mixfliptool", "SYSTEM_ALERT_WINDOW", "allow")
                .redirectErrorStream(true).start()
            overlayGrant.inputStream.use { it.readBytes() }
            check(overlayGrant.waitFor() == 0) { "无法授权外屏展示，请检查 Root 或 Shizuku 权限" }
            println("MFT:READY")
            System.out.flush()
            while (true) {
                val command = readlnOrNull() ?: break
                if (command == "quit") break
                val snapshot = controller.request(command)
                println("MFT:$snapshot")
                System.out.flush()
            }
        } catch (exception: Exception) {
            println("MFT:ERROR:${exception.cause?.message ?: exception.message}")
            System.out.flush()
        } finally {
            runCatching { controller?.close() }
        }
    }
}

internal class DualScreenController(context: android.content.Context) : AutoCloseable {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
    private val screen = OuterScreenPower(context)
    private val displayState = MusicDisplayState()
    private val presentationState = MusicPresentationState()
    private val music = MusicStateReader(context)
    private val controller = StateController()
    private var playing = false
    private var hasSession = false
    private var lastSnapshot: org.json.JSONObject? = null
    private var active = false
    private var enabled = false
    private var trackKey = ""
    private var trackChangeUntil = 0L
    private var lastDimmed = true
    private var displayRefreshKey = ""
    var displayOptions = MusicDisplayOptions()

    fun request(command: String): org.json.JSONObject {
        val now = SystemClock.elapsedRealtime()
        if (command == "on") enabled = true
        if (command == "off") enabled = false
        val controlAllowed = active && (!displayState.dimmed || displayOptions.shows(MusicDisplayOptions.CONTROLS, true))
        if (controlAllowed && (command == "next" || command == "previous")) {
            trackChangeUntil = now + 2_000L
            music.skip(command == "next")
        }
        if (controlAllowed && command == "playPause") music.togglePlayback()
        val snapshot = if (command in setOf("on", "off", "next", "previous", "playPause")) {
            music.read(enabled).also {
                playing = it.optBoolean("playing")
                // A requested track change can briefly rebuild the player's media session.
                hasSession = it.optBoolean("hasSession") || now < trackChangeUntil && lastSnapshot != null
                if (!hasSession) {
                    if (lastSnapshot != null) it.put("art", "")
                    lastSnapshot = null
                } else if (it.optBoolean("hasTrack")) lastSnapshot = org.json.JSONObject(it.toString()).apply { remove("art") }
            }
        } else org.json.JSONObject()
        val locked = !power.isInteractive || keyguard.isKeyguardLocked
        val wasDimmed = displayState.dimmed
        displayState.update(enabled, playing, lastSnapshot != null, locked, now, hasSession)
        displayState.command(command, now)
        if (wasDimmed != displayState.dimmed || command in setOf("wake", "sleep", "toggle", "next", "previous", "playPause")) {
            android.util.Log.i("MixFlipModule", "command=$command dimmed=$wasDimmed->${displayState.dimmed} locked=$locked playing=$playing")
        }
        if (displayState.visible && !snapshot.optBoolean("hasTrack")) {
            lastSnapshot?.let { cached ->
                for (key in listOf("title", "artist", "album", "lyric", "nextLyric", "hasTrack")) snapshot.put(key, cached.opt(key))
            }
        }
        val newTrackKey = listOf(snapshot.optString("title"), snapshot.optString("artist"), snapshot.optString("album")).joinToString("\u0000")
        val newDisplayKey = listOf(displayOptions,
            if (displayOptions.shows(MusicDisplayOptions.CURRENT_LYRIC, true)) snapshot.optString("lyric") else "",
            if (displayOptions.shows(MusicDisplayOptions.NEXT_LYRIC, true)) snapshot.optString("nextLyric") else "",
            displayOptions.shows(MusicDisplayOptions.CONTROLS, true) && playing,
            if (displayState.dimmed && playing && displayOptions.shows(MusicDisplayOptions.RHYTHM, true)) now / 250L else 0L
        ).joinToString("\u0000")
        val refresh = newTrackKey != trackKey || snapshot.has("art") || newDisplayKey != displayRefreshKey
        displayRefreshKey = newDisplayKey
        trackKey = newTrackKey
        val wasExiting = presentationState.exiting
        val wanted = presentationState.update(displayState.visible, active, command, now)
        if (!wanted) screen.restore()
        val layoutReady = controller.update(wanted, screen::prepare)
        val dimmed = if (presentationState.exiting) lastDimmed else displayState.dimmed
        active = layoutReady && presentationState.ready
        if (active) active = screen.update(dimmed, refresh || presentationState.exiting && !wasExiting)
        else if (!controller.hasRequest) screen.restore()
        if (active) lastDimmed = dimmed
        snapshot.put("active", active).put("playing", playing).put("dimmed", dimmed)
            .put("preparing", displayState.visible && controller.hasRequest && !active)
            .put("exiting", active && presentationState.exiting)
            .put("panelState", screen.state()).put("level", music.level(active && playing &&
                displayOptions.shows(MusicDisplayOptions.RHYTHM, displayState.dimmed)))
            .put("rhythmAvailable", music.rhythmAvailable()).put("rhythmSource", music.rhythmSource())
        music.appendNativeFrame(snapshot)
        return snapshot
    }

    override fun close() {
        runCatching { screen.restore() }
        runCatching { controller.update(false) }
        runCatching { music.close() }
    }

    private class OuterScreenPower(private val context: android.content.Context) {
        private val displays = context.getSystemService(DisplayManager::class.java)
        private val displayType = Display::class.java.getMethod("getType")
        private val owner = android.os.Binder()
        private var prepared = false
        private var displayId = Display.INVALID_DISPLAY
        private var dimmed: Boolean? = null

        fun prepare() {
            dimmed = null
            if (prepared) return
            send("prepare")
            prepared = true
        }

        private fun send(command: String, sleeping: Boolean = true): Boolean {
            val done = java.util.concurrent.CountDownLatch(1)
            var result = 0
            val intent = android.content.Intent(OuterDisplayPowerHooks.ACTION).setPackage("android")
                .putExtra("command", command).putExtra("display", displayId).putExtra("sleeping", sleeping)
                .putExtras(android.os.Bundle().apply { putBinder("owner", owner) })
            context.sendOrderedBroadcast(intent, android.Manifest.permission.DUMP, object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    result = resultCode
                    done.countDown()
                }
            }, android.os.Handler(Looper.getMainLooper()), 0, null, null)
            check(done.await(3, java.util.concurrent.TimeUnit.SECONDS) &&
                (result == OuterDisplayPowerHooks.PROTOCOL || command == "state" && result == OuterDisplayPowerHooks.PENDING)) {
                "外屏独立电源未连接，请在 LSPosed 勾选系统框架和系统界面后重启手机"
            }
            return result == OuterDisplayPowerHooks.PROTOCOL
        }

        fun update(sleeping: Boolean, refresh: Boolean): Boolean {
            val display = displays.displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY && displayType.invoke(it) == 1
            } ?: return false
            check(prepared)
            val changed = displayId != display.displayId || sleeping != dimmed
            displayId = display.displayId
            // Draw the information page before suspending the panel, and briefly resume for new metadata.
            if ((changed || sleeping && refresh) && !send("state", sleeping)) {
                dimmed = null
                return false
            }
            dimmed = sleeping
            // Flip 2 uses the vendor's low-brightness AOD preset, independently of normal backlight.
            // The system DisplayPowerController now owns brightness and panel transitions.
            return true
        }

        fun state() = displays.getDisplay(displayId)?.state ?: Display.STATE_UNKNOWN

        fun restore() {
            if (prepared) {
                // STATE_UNKNOWN restores the system's planned state, including the folded-screen mapping.
                // Release the group policy instead of issuing that old direct panel override.
                try { send("release") } finally { prepared = false }
            }
            displayId = Display.INVALID_DISPLAY
            dimmed = null
        }
    }

    // This helper runs under Root/Shizuku and enables HiddenApiBypass before accessing the vendor API.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private class StateController {
        private val globalClass = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal")
        private val global = globalClass.getMethod("getInstance").invoke(null)
        private val service = globalClass.getDeclaredField("mDeviceStateManager").apply {
            isAccessible = true
        }.get(global)
        private val serviceClass = Class.forName("android.hardware.devicestate.IDeviceStateManager")
        private val requestClass = Class.forName("android.hardware.devicestate.DeviceStateRequest")
        private val callbackClass = Class.forName("android.hardware.devicestate.DeviceStateRequest\$Callback")
        @Volatile private var requested = false
        val hasRequest get() = requested
        private val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, _ ->
            if (method.name == "onRequestCanceled") requested = false
            null
        }
        private val stateId: Int

        init {
            val info = info()
            val states = info.javaClass.getField("supportedStates").get(info) as? List<*>
                ?: error("当前系统不支持此双屏接口")
            val presentation = states.filterNotNull().firstOrNull { name(it) == "OPENED_PRESENTATION" }
                ?: error("当前设备未提供内外屏齐用模式")
            stateId = id(presentation)
        }

        private fun info(): Any = serviceClass.getMethod("getDeviceStateInfo").invoke(service)
        private fun id(state: Any) = state.javaClass.getMethod("getIdentifier").invoke(state) as Int
        private fun name(state: Any) = state.javaClass.getMethod("getName").invoke(state) as String

        fun update(wanted: Boolean, beforeRequest: () -> Unit = {}): Boolean {
            val info = info()
            val base = info.javaClass.getField("baseState").get(info)
            val current = info.javaClass.getField("currentState").get(info)
            val unfolded = name(base) == "OPENED" || name(base) == "HALF_OPENED"
            if (!wanted || !unfolded) {
                if (requested) globalClass.getMethod("cancelStateRequest").invoke(global)
                requested = false
                return false
            }
            if (!requested && id(current) == id(base)) {
                beforeRequest()
                val builder = requestClass.getMethod("newBuilder", Int::class.javaPrimitiveType).invoke(null, stateId)
                // Android cancels our own request immediately when the physical fold state changes.
                builder.javaClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, 1)
                val request = builder.javaClass.getMethod("build").invoke(builder)
                requested = true
                globalClass.getMethod("requestState", requestClass, Executor::class.java, callbackClass)
                    .invoke(global, request, Executor { it.run() }, callback)
            }
            return requested && id(info().let { it.javaClass.getField("currentState").get(it) }) == stateId
        }
    }
}
