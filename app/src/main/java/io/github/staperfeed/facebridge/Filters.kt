package io.github.staperfeed.facebridge

import android.content.Context

/** Persisted crosstalk rules / deadzone / smoothing (native: filters.inc). */
object Filters {
    const val MOUTH_CLOSED_CLAMP = 1 shl 0
    const val LIP_SUCK_LIMITER = 1 shl 1
    const val FROWN_JAW = 1 shl 2
    const val UPPER_UP_PRESS = 1 shl 3
    const val SMILE_ROLL = 1 shl 4
    const val STRETCH_SMILE = 1 shl 5
    const val TONGUE_JAW = 1 shl 6
    const val BROW_DOWN_CURVE = 1 shl 7
    const val TONGUE_HOLD = 1 shl 8
    const val ONE_EURO = 1 shl 9

    const val DEFAULT_FLAGS = MOUTH_CLOSED_CLAMP or LIP_SUCK_LIMITER or FROWN_JAW or UPPER_UP_PRESS or
        SMILE_ROLL or TONGUE_JAW or TONGUE_HOLD or ONE_EURO
    const val DEFAULT_DEADZONE = 0.08f
    const val DEFAULT_SMOOTHING = 0.3f

    /** UI labels, in display order. */
    val RULES = listOf(
        MOUTH_CLOSED_CLAMP to "MouthClosed ≤ JawOpen (VRCFT)",
        LIP_SUCK_LIMITER to "Lip suck limited by lip raise (VRCFT)",
        FROWN_JAW to "Damp frown while jaw is open",
        UPPER_UP_PRESS to "Suppress upper-lip raise while lips pressed",
        SMILE_ROLL to "Suppress smile while lower lip rolled in",
        STRETCH_SMILE to "Damp smile/upper lip during stretch (aggressive)",
        BROW_DOWN_CURVE to "Brow-down only when strong (aggressive)",
        TONGUE_JAW to "Tongue only with mouth open",
        TONGUE_HOLD to "Hold tongue 120 ms (anti-flicker)",
        ONE_EURO to "One Euro smoothing",
    )

    data class Values(val flags: Int, val deadzone: Float, val smoothing: Float)

    private const val PREFS = "filters"

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Values(
            p.getInt("flags", DEFAULT_FLAGS),
            p.getFloat("deadzone", DEFAULT_DEADZONE),
            p.getFloat("smoothing", DEFAULT_SMOOTHING),
        )
    }

    fun save(context: Context, v: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("flags", v.flags).putFloat("deadzone", v.deadzone).putFloat("smoothing", v.smoothing)
            .apply()
        apply(v)
    }

    fun apply(v: Values) = NativeBridge.nativeSetFilters(v.flags, v.deadzone, v.smoothing)
}
