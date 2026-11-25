package io.github.staperfeed.facebridge

import android.content.Context

/** Battery settings. "Only while VRChat runs" keeps PICO's tracking cameras off otherwise. */
object Power {
    private const val PREFS = "power"

    fun onlyWithVrchat(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("only_vrchat", true)

    fun setOnlyWithVrchat(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("only_vrchat", on).apply()
    }
}
