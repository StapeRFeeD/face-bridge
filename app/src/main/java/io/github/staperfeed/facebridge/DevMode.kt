package io.github.staperfeed.facebridge

import android.content.Context

/** Developer mode: unlocks debug UI and the per-second diagnostic log. */
object DevMode {
    @Volatile var enabled: Boolean = false
        private set

    fun load(context: Context): Boolean {
        enabled = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("dev", false)
        return enabled
    }

    fun set(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("dev", on).apply()
    }
}
