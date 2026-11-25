package io.github.staperfeed.facebridge

import android.content.Context

/** Persisted per-expression calibration: lower[52] + upper[52] in ARKit order (native: calibration.inc). */
object Calibration {
    private const val PREFS = "calibration"
    const val PHASE_IDLE = 0
    const val DEFAULT_RANGE = 0.5f
    const val PHASE_NEUTRAL = 1
    const val PHASE_EXPRESSIONS = 2
    const val PHASE_EXPRESSIONS_CONTINUE = 3

    data class Values(val data: FloatArray?, val enabled: Boolean, val timeMs: Long, val range: Float = DEFAULT_RANGE)

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val data = p.getString("data", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            ?.takeIf { it.size == 104 }
        return Values(data, p.getBoolean("enabled", true) && data != null, p.getLong("time", 0L), p.getFloat("range", DEFAULT_RANGE))
    }

    fun save(context: Context, v: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("data", v.data?.joinToString(","))
            .putBoolean("enabled", v.enabled)
            .putLong("time", v.timeMs)
            .putFloat("range", v.range)
            .apply()
        apply(v)
    }

    fun apply(v: Values) {
        NativeBridge.nativeSetCalibrationRange(v.range)
        NativeBridge.nativeSetCalibration(v.data, v.enabled)
    }
}
