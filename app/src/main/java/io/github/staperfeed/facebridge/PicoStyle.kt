package io.github.staperfeed.facebridge

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * PICO design guidelines (developer.picoxr.com/document/spatial-design) applied to a dark panel.
 * See docs/pico-design/README.md for the sources of every value.
 *
 * Shape: window radius 32 dp; inner surfaces concentric (32 − 8 inset = 24 dp); selection 16 dp;
 * buttons fully rounded (pills); all corners use continuous-curvature smoothing ([SmoothRect]).
 * Type: PICO Sans (system font) with the guideline scale — Headline Bold, Title/Label Semibold,
 * Body Medium — including line height and letter spacing; nothing below 12 sp.
 * States: hover and pressed change the background (animated), disabled = 30 % opacity.
 */
object PicoStyle {
    // Surfaces (dark adaptation of the light glass material)
    val window = Color.parseColor("#121212")
    val surface = Color.parseColor("#1E1E1E")
    val surfaceHover = Color.parseColor("#262626")
    val surfacePressed = Color.parseColor("#2C2C2C")
    val selected = Color.parseColor("#2C2C2C")
    val divider = Color.parseColor("#2A2A2A")
    val track = Color.parseColor("#3A3A3A")

    // Text
    val text = Color.parseColor("#F2F2F2")
    val textSecondary = Color.parseColor("#A6A6A6")
    val textTertiary = Color.parseColor("#6E6E6E")

    // Roles
    val primaryBg = Color.parseColor("#F2F2F2")      // dark-theme inverse of the charcoal primary pill
    val primaryFg = Color.parseColor("#121212")
    val auxBg = Color.parseColor("#2E2E2E")
    val auxHover = Color.parseColor("#383838")
    val auxPressed = Color.parseColor("#434343")
    val accessible = Color.parseColor("#B9E86A")     // "accessible" role (start/play); softened lime for dark scenes
    val blue = Color.parseColor("#3D8BFF")           // switches, slider fill
    val green = Color.parseColor("#4CD07D")
    val amber = Color.parseColor("#F5B841")
    val danger = Color.parseColor("#FF6B5E")

    const val DISABLED_ALPHA = 0.3f
    const val R_WINDOW = 32f
    const val R_SURFACE = 24f
    const val R_SELECTION = 16f

    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).roundToInt()
    fun font(weight: Int): Typeface = Typeface.create(Typeface.DEFAULT, weight, false)

    // ------------------------------------------------------------------ typography (guideline scale)

    enum class Type(val sp: Float, val lineSp: Float, val trackingPx: Float, val weight: Int) {
        HEADLINE(24f, 30f, -0.48f, 700),
        TITLE_L(20f, 26f, 0.12f, 600),
        TITLE(16f, 20f, 0.32f, 600),
        TITLE_S(14f, 18f, 0.28f, 600),
        LABEL(14f, 18f, 0.28f, 600),
        LABEL_S(12f, 16f, 0.24f, 600),
        BODY(16f, 20f, 0.30f, 500),
        BODY_MULTI(16f, 24f, 0.30f, 500),
        BODY_S(14f, 18f, 0.26f, 500),
        BODY_S_MULTI(14f, 22f, 0.26f, 500),
        BODY_T(12f, 16f, 0.22f, 500),
    }

    fun styleText(v: TextView, type: Type, color: Int) {
        val c = v.context
        v.textSize = type.sp
        v.typeface = font(type.weight)
        v.letterSpacing = type.trackingPx / type.sp            // px at 1× → em
        v.setLineHeight(dp(c, type.lineSp))
        v.setTextColor(color)
        v.includeFontPadding = false
    }

    fun text(c: Context, t: String, type: Type, color: Int = text) = TextView(c).apply {
        this.text = t
        styleText(this, type, color)
    }

    // ------------------------------------------------------------------ surfaces & states

    fun smooth(c: Context, color: Int, radiusDp: Float) = SmoothRect(color, dp(c, radiusDp).toFloat())

    /** Background that changes on hover/press with animated fades (PICO "Hover"/"Pressed" states). */
    fun stateful(c: Context, normal: Int, hover: Int, pressed: Int, radiusDp: Float): Drawable =
        StateListDrawable().apply {
            setEnterFadeDuration(Motion.FEEDBACK_MS.toInt())
            setExitFadeDuration(Motion.SMALL_MS.toInt())
            addState(intArrayOf(android.R.attr.state_pressed), smooth(c, pressed, radiusDp))
            addState(intArrayOf(android.R.attr.state_hovered), smooth(c, hover, radiusDp))
            addState(intArrayOf(android.R.attr.state_focused), smooth(c, hover, radiusDp))
            addState(intArrayOf(), if (Color.alpha(normal) == 0) ColorDrawable(Color.TRANSPARENT) else smooth(c, normal, radiusDp))
        }

    /** Rounded group container for settings rows (list section). */
    fun group(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = smooth(c, surface, R_SURFACE)
        setPadding(dp(c, 20f), dp(c, 4f), dp(c, 20f), dp(c, 4f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(c, 24f) }
    }

    /** 1 dp separator between list items in the same group. */
    fun dividerView(c: Context) = View(c).apply {
        setBackgroundColor(divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1f))
    }

    fun sectionLabel(c: Context, t: String) = text(c, t, Type.LABEL, textSecondary).apply {
        setPadding(dp(c, 4f), 0, 0, dp(c, 12f))
    }

    fun setEnabledLook(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.animate().alpha(if (enabled) 1f else DISABLED_ALPHA).setDuration(Motion.SMALL_MS).setInterpolator(Motion.fast).start()
    }

    // ------------------------------------------------------------------ buttons (fully rounded, by role)

    enum class Role { PRIMARY, AUXILIARY, ACCESSIBLE, DANGER, BORDERLESS }

    /** Regular = 40 dp, Max = 48 dp high; both have a minimum width. */
    fun button(c: Context, label: String, role: Role, large: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        this.text = label
        styleText(this, Type.LABEL, this@PicoStyle.text)
        textSize = if (large) 16f else 14f
        gravity = Gravity.CENTER
        minWidth = dp(c, if (large) 128f else 96f)
        minHeight = dp(c, if (large) 48f else 40f)
        setPadding(dp(c, if (large) 28f else 20f), 0, dp(c, if (large) 28f else 20f), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        Motion.pressFeedback(this)
        applyRole(this, role)
    }

    fun applyRole(b: TextView, role: Role) {
        val c = b.context
        val pill = 999f
        when (role) {
            Role.PRIMARY -> { b.setTextColor(primaryFg); b.background = stateful(c, primaryBg, Color.WHITE, Color.parseColor("#D6D6D6"), pill) }
            Role.ACCESSIBLE -> { b.setTextColor(primaryFg); b.background = stateful(c, accessible, Color.parseColor("#C8F07E"), Color.parseColor("#A2D157"), pill) }
            Role.AUXILIARY -> { b.setTextColor(text); b.background = stateful(c, auxBg, auxHover, auxPressed, pill) }
            Role.DANGER -> { b.setTextColor(danger); b.background = stateful(c, auxBg, auxHover, auxPressed, pill) }
            Role.BORDERLESS -> { b.setTextColor(text); b.background = stateful(c, Color.TRANSPARENT, auxBg, auxHover, pill) }
        }
    }

    // ------------------------------------------------------------------ controls

    /** Switch: blue track when on, white thumb. (System click sound only — no extra interface sounds.) */
    fun switch(c: Context, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(c).apply {
        isChecked = initial
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, Color.parseColor("#D9D9D9")))
        trackTintList = ColorStateList(states, intArrayOf(blue, Color.parseColor("#4A4A4A")))
        trackTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        minimumWidth = dp(c, 40f)      // hot zone ≥ 40 × 36 dp
        minimumHeight = dp(c, 36f)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** Slider: rounded track, blue fill, circular white knob. */
    fun seekBar(c: Context) = SeekBar(c).apply {
        val h = dp(c, 6f)
        val bg = GradientDrawable().apply { setColor(track); cornerRadius = h / 2f }
        val fill = GradientDrawable().apply { setColor(blue); cornerRadius = h / 2f }
        progressDrawable = LayerDrawable(arrayOf(bg, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
        minHeight = h; maxHeight = h
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setSize(dp(c, 22f), dp(c, 22f))
        }
        splitTrack = false
        setPadding(dp(c, 12f), dp(c, 10f), dp(c, 12f), dp(c, 10f))
    }

    // ------------------------------------------------------------------ dialog alert

    /**
     * Dialog Alert: centered over the window, parent dimmed, 32 dp smoothed corners, title + text,
     * pill actions (auxiliary + primary/danger). Used as the secondary confirmation for risky actions.
     */
    fun confirm(c: Context, title: String, message: String, confirmLabel: String, dangerous: Boolean, onConfirm: () -> Unit) {
        val d = Dialog(c)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = smooth(c, Color.parseColor("#262626"), R_WINDOW)
            setPadding(dp(c, 28f), dp(c, 28f), dp(c, 28f), dp(c, 24f))
        }
        box.addView(text(c, title, Type.TITLE_L, text))
        box.addView(text(c, message, Type.BODY_S_MULTI, textSecondary).apply { setPadding(0, dp(c, 10f), 0, dp(c, 24f)) })
        val actions = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(button(c, "Cancel", Role.AUXILIARY) { d.dismiss() })
        actions.addView(button(c, confirmLabel, if (dangerous) Role.DANGER else Role.PRIMARY) { d.dismiss(); onConfirm() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(c, 12f) })
        box.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.setContentView(box, ViewGroup.LayoutParams(dp(c, 420f), ViewGroup.LayoutParams.WRAP_CONTENT))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.55f)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        d.show()
        box.alpha = 0f; box.scaleX = 0.96f; box.scaleY = 0.96f
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.PAGE_MS).setInterpolator(Motion.gentle).start()
    }
}
