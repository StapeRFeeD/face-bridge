package io.github.staperfeed.facebridge

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.staperfeed.facebridge.PicoStyle as P
import io.github.staperfeed.facebridge.PicoStyle.Type as T
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Step-by-step personal calibration in the PICO OS 5 look (like Motion Tracker's setup flow):
 * one centered column — segmented progress, the mascot inside a circular progress ring, step text,
 * live preview and OS 5 rounded-rectangle buttons — so everything sits on the axis straight ahead.
 *
 * Each step: "get ready" countdown, then a hold during which the native engine records
 * (start = one beep, end = two beeps, so eyes-closed steps need no screen).
 * Step 1 records the resting face (lower bounds); all others extend the per-shape maxima (upper bounds).
 */
class CalibrationActivity : Activity() {

    private data class Step(
        val title: String, val hint: String, val face: FaceView.Expr,
        val holdMs: Long = 3500, val readyMs: Long = 3000, val neutral: Boolean = false,
    )

    private val steps = listOf(
        Step("Relax", "Neutral face, look straight ahead", FaceView.Expr(), holdMs = 5000, readyMs = 4000, neutral = true),
        Step("Smile", "Widest smile, teeth can show · keep your jaw closed", FaceView.Expr(smile = 1f)),
        Step("Open wide", "Drop your jaw all the way", FaceView.Expr(jaw = 1f)),
        Step("Kiss", "Pucker your lips forward", FaceView.Expr(pucker = 1f)),
        Step("Puff", "Fill your cheeks with air", FaceView.Expr(puff = 1f)),
        Step("Brows up", "Raise your eyebrows high", FaceView.Expr(brow = 1f, wide = 0.4f)),
        Step("Frown", "Pull your brows down and together", FaceView.Expr(brow = -1f, frown = 0.6f)),
        Step("Wide eyes", "Open your eyes as wide as possible", FaceView.Expr(wide = 1f, brow = 0.4f)),
        Step("Look left", "Only your eyes, head still", FaceView.Expr(lookX = -1f), holdMs = 2500),
        Step("Look right", "Only your eyes, head still", FaceView.Expr(lookX = 1f), holdMs = 2500),
        Step("Look up", "Only your eyes, head still", FaceView.Expr(lookY = 1f), holdMs = 2500),
        Step("Look down", "Only your eyes, head still", FaceView.Expr(lookY = -1f), holdMs = 2500),
        Step("Close your eyes", "Close at the beep · open at the double beep", FaceView.Expr(blinkL = 1f, blinkR = 1f), holdMs = 3500),
        Step("Squint", "Narrow your eyes", FaceView.Expr(blinkL = 0.6f, blinkR = 0.6f, smile = 0.3f)),
        Step("Jaw left", "Slide your jaw to the left", FaceView.Expr(jawX = -1f, jaw = 0.15f), holdMs = 2500),
        Step("Jaw right", "Slide your jaw to the right", FaceView.Expr(jawX = 1f, jaw = 0.15f), holdMs = 2500),
        Step("Tongue", "Stick your tongue out", FaceView.Expr(jaw = 0.5f, tongue = 1f)),
    )

    // PICO OS 5 surfaces / accent (system dialogs, Motion Tracker)
    private val os5Card = Color.parseColor("#171717")
    private val os5Blue = Color.parseColor("#3D8BFF")
    private val os5Gray = Color.parseColor("#3A3A3A")
    private val fg = P.text
    private val muted = P.textSecondary

    private val ui = Handler(Looper.getMainLooper())
    private val live = FloatArray(52)

    private lateinit var counter: TextView
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var phaseText: TextView
    private lateinit var ring: RingView
    private lateinit var segments: SegmentBar
    private lateinit var target: FaceView
    private lateinit var you: FaceView
    private lateinit var youLabel: TextView
    private lateinit var pauseButton: TextView

    private var index = 0
    private var holding = false
    private var paused = false
    private var stepStart = 0L
    private var expressionsStarted = false
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (done) return
            updateLive()
            if (!paused) advance()
            ui.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        UiSound.init(this)
        OscBridgeService.acquireForceTracking()   // calibrate even without VRChat
        if (!OscBridgeService.isRunning) OscBridgeService.start(this, NativeBridge.SOURCE_AUTO, true)
        NativeBridge.nativeCalibrationPhase(Calibration.PHASE_IDLE)
        enterStep(0)
        ui.post(tick)
        if (intent.getBooleanExtra(Screenshots.EXTRA, false)) {
            // Relax: get ready (2 s) and hold (7 s); Smile: hold (13.5 s). Then cancel without saving.
            ui.postDelayed({ Screenshots.capture(this, "calibration-1-get-ready") }, 2_000)
            ui.postDelayed({ Screenshots.capture(this, "calibration-2-hold") }, 7_000)
            ui.postDelayed({ Screenshots.capture(this, "calibration-3-smile"); finish() }, 13_500)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!done && !paused) togglePause()
    }

    override fun onDestroy() {
        OscBridgeService.releaseForceTracking()
        ui.removeCallbacksAndMessages(null)
        if (!done) NativeBridge.nativeCalibrationPhase(Calibration.PHASE_IDLE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = P.dp(this, v.toFloat())

    private fun buildUi(): View {
        // Single centered column on the rounded panel (window itself is transparent): everything the user
        // needs to look at sits on the axis straight ahead.
        val root = FrameLayout(this).apply { background = P.smooth(this@CalibrationActivity, P.window, P.R_WINDOW) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(24), dp(40), dp(24))
        }
        counter = P.text(this, "", T.LABEL_S, muted).apply { gravity = Gravity.CENTER }
        col.addView(counter)
        segments = SegmentBar(this, steps.size)
        col.addView(segments, LinearLayout.LayoutParams(dp(420), dp(6)).apply { topMargin = dp(10) })

        val art = FrameLayout(this)
        ring = RingView(this)
        art.addView(ring, FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))
        target = FaceView(this)
        art.addView(target, FrameLayout.LayoutParams(dp(196), dp(196), Gravity.CENTER))
        col.addView(art, LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(16) })

        title = P.text(this, "", T.HEADLINE, fg).apply { textSize = 30f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        col.addView(title)
        hint = P.text(this, "", T.BODY, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        col.addView(hint)
        phaseText = P.text(this, "", T.TITLE, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        col.addView(phaseText)

        // Live preview pill (tracking status)
        val liveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = P.smooth(this@CalibrationActivity, os5Card, 999f)
            setPadding(dp(8), dp(6), dp(18), dp(6))
        }
        you = FaceView(this)
        liveRow.addView(you, LinearLayout.LayoutParams(dp(36), dp(36)))
        youLabel = P.text(this, "", T.BODY_S, muted).apply { setPadding(dp(10), 0, 0, 0) }
        liveRow.addView(youLabel)
        col.addView(liveRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        pauseButton = os5Button("Pause", primary = true) { togglePause() }
        buttons.addView(pauseButton)
        buttons.addView(os5Button("Skip", primary = false) { skip() }, marginLeft())
        buttons.addView(os5Button("Cancel", primary = false) { cancel() }, marginLeft())
        col.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    private fun marginLeft() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) }

    /** PICO OS 5 button: filled rounded rectangle (12 dp), blue primary / gray secondary. */
    private fun os5Button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = t
        P.styleText(this, T.LABEL, fg)
        textSize = 16f
        gravity = Gravity.CENTER
        minWidth = dp(120)
        minHeight = dp(44)
        setPadding(dp(24), 0, dp(24), 0)
        isClickable = true
        background = P.stateful(this@CalibrationActivity,
            if (primary) os5Blue else os5Gray,
            if (primary) Color.parseColor("#5A9CFF") else Color.parseColor("#454545"),
            if (primary) Color.parseColor("#1B88EE") else Color.parseColor("#4F4F4F"), 12f)
        setOnClickListener { onClick() }
        Motion.pressFeedback(this)
    }

    /** Circular progress ring around the target face. */
    private inner class RingView(c: Context) : View(c) {
        var progress = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        var color = muted
            set(v) { field = v; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#2A2A2A") }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val w = dp(6).toFloat()
            track.strokeWidth = w; arc.strokeWidth = w; arc.color = color
            val s = min(width, height) - w
            box.set((width - s) / 2f, (height - s) / 2f, (width + s) / 2f, (height + s) / 2f)
            canvas.drawOval(box, track)
            if (progress > 0f) canvas.drawArc(box, -90f, 360f * progress, false, arc)
        }
    }

    /** Thin segmented progress: done = blue, current = white, upcoming = gray. */
    private inner class SegmentBar(c: Context, private val n: Int) : View(c) {
        var current = 0
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val gap = dp(4).toFloat()
            val w = (width - gap * (n - 1)) / n
            for (i in 0 until n) {
                paint.color = when { i < current -> os5Blue; i == current -> fg; else -> Color.parseColor("#333333") }
                val x = i * (w + gap)
                r.set(x, 0f, x + w, height.toFloat())
                canvas.drawRoundRect(r, height / 2f, height / 2f, paint)
            }
        }
    }

    // ------------------------------------------------------------------ flow

    /** Step change: old text slides/fades out quickly, new text fades in with the gentle curve. */
    private fun swapText(v: TextView, t: String) {
        if (v.text == t) return
        v.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(Motion.FEEDBACK_MS).setInterpolator(Motion.exit).withEndAction {
            v.text = t
            v.translationY = dp(6).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(Motion.PAGE_MS).setInterpolator(Motion.gentle).start()
        }.start()
    }

    private fun enterStep(i: Int) {
        index = i
        holding = false
        stepStart = SystemClock.uptimeMillis()
        val s = steps[i]
        counter.text = "STEP ${i + 1} OF ${steps.size}"
        segments.current = i
        swapText(title, s.title)
        swapText(hint, s.hint)
        target.show(s.face)
        ring.progress = 0f
        ring.color = muted
    }

    private fun advance() {
        val s = steps[index]
        val elapsed = SystemClock.uptimeMillis() - stepStart
        if (!holding) {
            val left = s.readyMs - elapsed
            val secs = ((left + 999) / 1000).coerceAtLeast(1)
            phaseText.setTextColor(muted)
            phaseText.text = "Get ready · $secs"
            ring.color = muted
            ring.progress = (elapsed.toFloat() / s.readyMs).coerceIn(0f, 1f)
            if (left <= 0) startHold()
        } else {
            val held = elapsed - s.readyMs
            ring.color = os5Blue
            ring.progress = (held.toFloat() / s.holdMs).coerceIn(0f, 1f)
            if (held >= s.holdMs) endHold()
        }
    }

    private fun startHold() {
        holding = true
        val s = steps[index]
        NativeBridge.nativeCalibrationPhase(when {
            s.neutral -> Calibration.PHASE_NEUTRAL
            !expressionsStarted -> Calibration.PHASE_EXPRESSIONS.also { expressionsStarted = true }
            else -> Calibration.PHASE_EXPRESSIONS_CONTINUE
        })
        UiSound.play(UiSound.Kind.STEP_START)
        phaseText.setTextColor(os5Blue)
        phaseText.text = "Hold…"
    }

    private fun endHold() {
        NativeBridge.nativeCalibrationPhase(Calibration.PHASE_IDLE)  // pause, keep data
        UiSound.play(UiSound.Kind.STEP_END)
        nextStep()
    }

    private fun nextStep() {
        if (index + 1 < steps.size) enterStep(index + 1) else finishCalibration()
    }

    private fun skip() {
        if (done) return
        if (holding) NativeBridge.nativeCalibrationPhase(Calibration.PHASE_IDLE)
        if (steps[index].neutral) {
            Toast.makeText(this, "The relaxed step is required", Toast.LENGTH_SHORT).show()
            enterStep(index)
            return
        }
        if (paused) togglePause()
        nextStep()
    }

    private fun togglePause() {
        if (done) return
        paused = !paused
        if (paused) {
            if (holding) NativeBridge.nativeCalibrationPhase(Calibration.PHASE_IDLE)
            pauseButton.text = "Resume"
            phaseText.setTextColor(muted)
            phaseText.text = "Paused"
            } else {
            // Restart the current step from its "get ready" phase.
            pauseButton.text = "Pause"
            enterStep(index)
        }
    }

    private fun cancel() {
        done = true
        NativeBridge.nativeCalibrationPhase(Calibration.PHASE_IDLE)
        finish()
    }

    private fun finishCalibration() {
        done = true
        val data = NativeBridge.nativeCalibrationFinish()
        if (data == null) {
            Toast.makeText(this, "Not enough tracking data. Wear the headset and try again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val previous = Calibration.load(this)
        Calibration.save(this, Calibration.Values(data, true, System.currentTimeMillis(), previous.range))
        // Resting offsets are now removed, so a small deadzone is enough. Strength sliders are left as they are.
        Filters.save(this, Filters.load(this).copy(deadzone = 0.03f))
        swapText(title, "Done")
        swapText(hint, "Calibration saved")
        phaseText.text = ""
        counter.text = ""
        segments.current = steps.size
        ring.color = os5Blue
        ring.progress = 1f
        target.show(FaceView.Expr(smile = 0.8f))
        UiSound.play(UiSound.Kind.SUCCESS)
        ui.postDelayed({ finish() }, 1500)
    }

    private fun updateLive() {
        if (NativeBridge.nativeGetLiveShapes(live)) {
            you.set(FaceView.Expr.fromArkit(live))
            youLabel.text = "You — tracking"
            youLabel.setTextColor(muted)
        } else {
            youLabel.text = OscBridgeService.sessionProblem ?: "Waiting for tracking…"
            youLabel.setTextColor(P.amber)
        }
    }
}
