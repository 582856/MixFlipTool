package com.parallelc.mixfliptool

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PrivilegedBridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "dualScreenConfig") {
            check(Binder.getCallingUid() == 1000 || Binder.getCallingUid() == 0) { "System caller required" }
            return DualScreenService.configuration(checkNotNull(context))
        }
        check(Binder.getCallingUid() == 0 || Binder.getCallingUid() == 2000) { "Privileged caller required" }
        require(method == "applicationThread")
        // ActivityManager requires a registered client even when the binding is requested by Root.
        HiddenApiBypass.addHiddenApiExemptions("")
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getMethod("currentActivityThread").invoke(null)
        val appThread = type.getMethod("getApplicationThread").invoke(thread) as android.os.IInterface
        return Bundle().apply { putBinder("thread", appThread.asBinder() as IBinder) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
