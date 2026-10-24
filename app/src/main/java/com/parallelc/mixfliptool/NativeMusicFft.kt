package com.parallelc.mixfliptool

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeMusicFft(private val context: Context) : AutoCloseable {
    private var attemptedPackage = ""
    private var player: IBinder? = null
    private var connection: Any? = null
    private var manager: Any? = null
    private var previousEnabled: Boolean? = null
    private var fftEnabled = false
    @Volatile private var frame: Frame? = null
    @Volatile private var accepting = false
    data class Frame(val encoded: String, val time: Long, val sampleRate: Int, val captureRate: Int)

    fun select(packageName: String) {
        if (attemptedPackage == packageName) return
        close()
        attemptedPackage = packageName
        if (Process.myUid() !in setOf(0, 1000) || packageName != PACKAGE) return
        runCatching {
            val info = context.packageManager.getPackageInfo(PACKAGE, 0)
            // Private transaction numbers are verified only for this player version.
            if (info.longVersionCode != 9006010L) return
            bind(info.applicationInfo!!.uid)
        }.onFailure { release() }
    }

    fun frame(): Frame? = frame?.let {
        if (accepting && SystemClock.elapsedRealtime() - it.time <= 600L) it else null
    }

    fun position(): Long? = runCatching {
        val remote = player ?: return null
        val query = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            query.writeInterfaceToken(CONTROLLER)
            check(remote.transact(19, query, reply, 0))
            reply.readException()
            reply.readInt().toLong().takeIf { it >= 0L }
        } finally {
            query.recycle()
            reply.recycle()
        }
    }.getOrNull()

    fun setFftEnabled(enabled: Boolean) {
        if (player == null || fftEnabled == enabled) return
        runCatching {
            transact(38) { writeInt(if (enabled) 1 else 0) }
            fftEnabled = enabled
            if (!enabled) frame = null
        }
    }

    private fun bind(playerUid: Int) {
        val amClass = Class.forName("android.app.IActivityManager")
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        manager = am
        val appThread = if (Process.myUid() == 1000) {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            threadClass.getMethod("getApplicationThread").invoke(thread)
        } else {
        val token = Binder()
        val holder = amClass.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, AUTHORITY, 0, token, "MixFlipTool")
        val result = try {
            val provider = holder.javaClass.getField("provider").get(holder)
            Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java)
                .invoke(provider, AttributionSource.Builder(Process.myUid()).setPackageName("android").build(),
                    AUTHORITY, "applicationThread", null, null) as Bundle
        } finally {
            amClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                Int::class.javaPrimitiveType).invoke(am, AUTHORITY, token, 0)
        }
        Class.forName("android.app.IApplicationThread\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, result.getBinder("thread"))
        }
        val connected = CountDownLatch(1)
        val serviceBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface("android.app.IServiceConnection")
                data.readTypedObject(ComponentName.CREATOR)
                val service = data.readStrongBinder()
                if (accepting) player = service
                connected.countDown()
                return true
            }
        }
        val connectionType = Class.forName("android.app.IServiceConnection")
        val client = Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType)) { _, method, _ ->
            if (method.name == "asBinder") serviceBinder else null
        }
        connection = client
        accepting = true
        val bound = amClass.methods.first { it.name == "bindService" && it.parameterCount == 8 }
            .invoke(am, appThread, null, Intent(KEY).setComponent(ComponentName(PACKAGE,
                    "$PACKAGE.service.MainProcessPlayService")), null, client, 1L,
                    if (Process.myUid() == 0) APP_PACKAGE else context.packageName, 0) as Int
        check(bound > 0 && connected.await(750, TimeUnit.MILLISECONDS))
        val remote = checkNotNull(player)
        check(remote.interfaceDescriptor == CONTROLLER)
        val query = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            query.writeInterfaceToken(CONTROLLER)
            check(remote.transact(39, query, reply, 0))
            reply.readException()
            previousEnabled = reply.readInt() != 0
            fftEnabled = previousEnabled == true
        } finally {
            query.recycle()
            reply.recycle()
        }
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                if (Binder.getCallingUid() != playerUid) return false
                data.enforceInterface(CALLBACK)
                if (code == 3 && accepting) {
                    val values = data.createFloatArray()
                    val sampleRate = data.readInt()
                    val captureRate = data.readInt()
                    if (values != null && values.size in 1024..4096 && values.all { it.isFinite() } && captureRate > 0) {
                        // Match the player's MiMusicVisualizer mapping instead of substituting RMS energy.
                        // Forward unchanged floats to the player's own processor in the display process.
                        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        values.forEach { bytes.putFloat(it) }
                        frame = Frame(Base64.encodeToString(bytes.array(), Base64.NO_WRAP),
                            SystemClock.elapsedRealtime(), sampleRate, captureRate)
                    }
                }
                reply?.writeNoException()
                if (code == 2 || code == 6) reply?.writeInt(0)
                return true
            }
        }
        transact(36) { writeString(KEY); writeStrongBinder(callback) }
    }

    private fun transact(code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(CONTROLLER)
            data.write()
            check(checkNotNull(player).transact(code, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
    }

    private fun release() {
        accepting = false
        previousEnabled?.let { enabled -> runCatching { transact(38) { writeInt(if (enabled) 1 else 0) } } }
        previousEnabled = null
        fftEnabled = false
        connection?.let { client ->
            runCatching {
                Class.forName("android.app.IActivityManager").getMethod("unbindService",
                    Class.forName("android.app.IServiceConnection")).invoke(manager, client)
            }
        }
        connection = null
        player = null
        manager = null
        frame = null
    }

    override fun close() {
        release()
        attemptedPackage = ""
    }

    companion object {
        private const val PACKAGE = "com.netease.cloudmusic"
        private const val APP_PACKAGE = "com.parallelc.mixfliptool"
        private const val AUTHORITY = "$APP_PACKAGE.privileged"
        private const val KEY = "$APP_PACKAGE.fft"
        private const val CONTROLLER = "$PACKAGE.aidl.PlayController"
        private const val CALLBACK = "$PACKAGE.aidl.PlayControllCallback"
    }
}
