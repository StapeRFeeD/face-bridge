package io.github.staperfeed.facebridge

import android.content.Context

/** Persisted gains, shared by the activity (sliders) and the service (applied on start). */
object Tuning {
    private const val PREFS = "tuning"
    private const val KEY_FACE = "face_gain"
    private const val KEY_EYE = "eye_gain"
    private const val KEY_EYE_OSC = "eye_osc"
    private const val KEY_JAW = "jaw_gain"
    private const val KEY_MUTE_VISEMES = "mute_visemes"

    const val DEFAULT_FACE_GAIN = 1.5f
    const val DEFAULT_EYE_GAIN = 1.5f
    const val DEFAULT_JAW_GAIN = 1.3f

    data class Values(val faceGain: Float, val eyeGain: Float, val eyeOsc: Boolean, val jawGain: Float = DEFAULT_JAW_GAIN, val muteVisemes: Boolean = true)

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Values(
            p.getFloat(KEY_FACE, DEFAULT_FACE_GAIN),
            p.getFloat(KEY_EYE, DEFAULT_EYE_GAIN),
            p.getBoolean(KEY_EYE_OSC, true),
            p.getFloat(KEY_JAW, DEFAULT_JAW_GAIN),
            p.getBoolean(KEY_MUTE_VISEMES, true),
        )
    }

    /** Saves and applies immediately (the native engine lives in the same process). */
    fun save(context: Context, v: Values) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_FACE, v.faceGain)
            .putFloat(KEY_EYE, v.eyeGain)
            .putBoolean(KEY_EYE_OSC, v.eyeOsc)
            .putFloat(KEY_JAW, v.jawGain)
            .putBoolean(KEY_MUTE_VISEMES, v.muteVisemes)
            .apply()
        apply(v)
    }

    fun apply(v: Values) {
        NativeBridge.nativeSetTuning(v.faceGain, v.eyeGain, v.eyeOsc, v.jawGain)
        OscQueryClient.muteVisemes = v.muteVisemes
        if (!v.muteVisemes) OscQueryClient.restoreVisemes()
    }
}
