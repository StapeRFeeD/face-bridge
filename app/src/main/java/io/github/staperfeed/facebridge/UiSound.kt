package io.github.staperfeed.facebridge

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Calibration audio cues only (hold start, hold end, done). They are functional — on the
 * "close your eyes" step the beep is the only signal — so they stay; general interface sounds
 * were removed because they doubled the system click sounds. Synthesized by tools/make_sounds.py.
 */
object UiSound {
    enum class Kind { SUCCESS, STEP_START, STEP_END }

    private var pool: SoundPool? = null
    private val ids = HashMap<Kind, Int>()

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build()
        val c = context.applicationContext
        ids[Kind.SUCCESS] = p.load(c, R.raw.ui_success, 1)
        ids[Kind.STEP_START] = p.load(c, R.raw.ui_step_start, 1)
        ids[Kind.STEP_END] = p.load(c, R.raw.ui_step_end, 1)
        pool = p
    }

    fun play(kind: Kind) {
        val id = ids[kind] ?: return
        pool?.play(id, 1f, 1f, 1, 0, 1f)
    }
}
