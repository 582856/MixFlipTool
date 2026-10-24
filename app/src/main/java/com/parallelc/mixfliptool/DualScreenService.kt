package com.parallelc.mixfliptool

import android.content.Context
import android.content.Intent
import android.os.Bundle

object DualScreenService {
    const val CONFIG_ACTION = "com.parallelc.mixfliptool.DUAL_SCREEN_CONFIG"
    const val STATUS_ACTION = "com.parallelc.mixfliptool.DUAL_SCREEN_STATUS"
    const val HOST_PACKAGE = "com.android.systemui"
    const val MODULE_PACKAGE = "com.parallelc.mixfliptool"

    fun preferences(context: Context) = context.getSharedPreferences("dual_screen", Context.MODE_PRIVATE)

    fun configuration(context: Context): Bundle {
        val prefs = preferences(context)
        val options = MusicDisplayOptions.read(prefs::getInt)
        return Bundle().apply {
            putBoolean("enabled", prefs.getBoolean("master", false))
            putInt(MusicDisplayOptions.BRIGHT_KEY, options.bright)
            putInt(MusicDisplayOptions.DIM_KEY, options.dim)
        }
    }

    fun sync(context: Context) {
        context.stopService(Intent().setClassName(MODULE_PACKAGE, "$MODULE_PACKAGE.DualScreenService"))
        val config = configuration(context)
        @Suppress("DEPRECATION")
        val shared = runCatching { context.getSharedPreferences("dual_screen_module", Context.MODE_WORLD_READABLE) }
            .getOrElse { context.getSharedPreferences("dual_screen_module", Context.MODE_PRIVATE) }
        shared.edit().putBoolean("enabled", config.getBoolean("enabled"))
            .putInt(MusicDisplayOptions.BRIGHT_KEY, config.getInt(MusicDisplayOptions.BRIGHT_KEY))
            .putInt(MusicDisplayOptions.DIM_KEY, config.getInt(MusicDisplayOptions.DIM_KEY)).commit()
        context.sendBroadcast(Intent(CONFIG_ACTION).setPackage(HOST_PACKAGE).putExtras(config), android.Manifest.permission.DUMP)
    }
}
