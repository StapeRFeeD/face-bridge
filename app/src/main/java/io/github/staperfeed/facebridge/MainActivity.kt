package io.github.staperfeed.facebridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.staperfeed.facebridge.PicoStyle as P
import io.github.staperfeed.facebridge.PicoStyle.Type as T
import io.github.staperfeed.facebridge.PicoStyle.Role
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Control panel following the PICO design guidelines on a dark panel: Side Navigation (inner panel,
 * 272/316 dp, spring-animated selection) + content with grouped lists, pill buttons by role, guideline
 * type scale, gentle page transitions, interface sounds. Developer page unlocks with 7 taps on the title.
 * Opens as a PICO "near" panel, so it also works over a running VRChat.
 */
class MainActivity : Activity() {

    companion object {
        private const val PERM_FACE = "com.picovr.permission.FACE_TRACKING"
        private const val PERM_EYE = "com.picovr.permission.EYE_TRACKING"
        private const val REQ_PERMS = 42
        const val EXTRA_AUTOSTART = "autostart"
        private const val PAGE_STATUS = 0
        private const val PAGE_CALIBRATION = 1
        private const val PAGE_STRENGTH = 2
        private const val PAGE_FILTERING = 3
        private const val PAGE_DEVELOPER = 4
    }

    private val ui = Handler(Looper.getMainLooper())
    private val stats = LongArray(NativeBridge.STAT_COUNT)
    private var lastFrames = 0L
    private var lastSampleAt = 0L
    private var titleTaps = 0
    private var lastTitleTap = 0L
    private var shownCalibrationTime = 0L
    private var page = -1
    private var stateColor = P.textTertiary

    private val navRows = ArrayList<LinearLayout>()
    private val pages = ArrayList<View>()
    private lateinit var devNav: View
    private lateinit var navHighlight: View
    private lateinit var navColumn: FrameLayout

    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var stateSub: TextView
    private lateinit var headsetValue: TextView
    private lateinit var avatarValue: TextView
    private lateinit var outputValue: TextView
    private lateinit var startButton: TextView
    private lateinit var permGroup: View
    private lateinit var calSubtitle: TextView
    private lateinit var calSwitch: Switch
    private lateinit var devStats: TextView
    private lateinit var sourceGroup: RadioGroup

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevMode.load(this)
        val restore = savedInstanceState?.getInt("page", PAGE_STATUS) ?: PAGE_STATUS
        setContentView(buildUi())
        navColumn.post { selectPage(if (restore == PAGE_DEVELOPER && !DevMode.enabled) PAGE_STATUS else restore, animate = false) }
        requestTrackingPermissions()
        handleAutostart(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", page.coerceAtLeast(0))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the calibration screen: rebuild so every page shows current values.
        if (Calibration.load(this).timeMs != shownCalibrationTime) { recreate(); return }
        updatePermissionGroup()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)   // delayed status updates must not outlive (and pin) the activity
        super.onDestroy()
    }

    /**
     * adb shell am start -n io.github.staperfeed.facebridge/.AdbControl --ei autostart <source>
     * source: 0/1 PICO tracking service, 3 test pattern; -1 stops the service.
     * Only honoured through the DUMP-protected .AdbControl alias: MainActivity itself is exported (launcher),
     * so any app could otherwise switch the face cameras on or stop the bridge.
     */
    private fun handleAutostart(intent: Intent?) {
        if (intent?.component?.className != "$packageName.AdbControl") return
        // adb: --ez calibrate true opens the calibration screen (CalibrationActivity is not exported).
        val shots = intent?.getBooleanExtra(Screenshots.EXTRA, false) == true
        intent?.removeExtra(Screenshots.EXTRA)
        if (intent?.getBooleanExtra("calibrate", false) == true) {
            intent.removeExtra("calibrate")
            startActivity(Intent(this, CalibrationActivity::class.java).putExtra(Screenshots.EXTRA, shots))
            return
        }
        if (shots) { captureAllPages(); return }
        if (intent == null || !intent.hasExtra(EXTRA_AUTOSTART)) return
        val source = intent.getIntExtra(EXTRA_AUTOSTART, NativeBridge.SOURCE_AUTO)
        if (source < 0) OscBridgeService.stop(this)
        else OscBridgeService.start(this, source.coerceIn(0, 3), intent.getBooleanExtra(OscBridgeService.EXTRA_RESPECT_AVATAR, true))
        intent.removeExtra(EXTRA_AUTOSTART)
        finish()  // scripted start: don't keep a window in front of VRChat
    }

    /** Saves every page as a PNG (see [Screenshots]); returns to the page that was open. */
    private fun captureAllPages() {
        val names = listOf("status", "calibration", "strength", "filtering", "developer")
        val pages = names.indices.filter { it != PAGE_DEVELOPER || DevMode.enabled }
        val back = page.coerceAtLeast(PAGE_STATUS)
        fun step(i: Int) {
            if (i == pages.size) { selectPage(back, animate = false); toast("Screenshots saved"); return }
            selectPage(pages[i], animate = false)
            updateStatus()
            ui.postDelayed({ Screenshots.capture(this, "page-${pages[i] + 1}-${names[pages[i]]}"); step(i + 1) }, 900)
        }
        ui.postDelayed({ step(0) }, 600)
    }

    // ------------------------------------------------------------------ layout

    private fun dp(v: Int) = P.dp(this, v.toFloat())

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // The window itself is transparent; this is the panel: 32 dp smoothed corners (guideline window radius).
            background = P.smooth(this@MainActivity, P.window, P.R_WINDOW)
            setPadding(dp(8), dp(8), dp(8), dp(8))   // 8 dp inset → inner radius 32 − 8 = 24 dp (concentric)
        }
        // Side Navigation width: 316 dp on wide windows (≥ 1280 dp), otherwise 272 dp.
        val widthDp = resources.configuration.screenWidthDp
        root.addView(buildSidebar(), LinearLayout.LayoutParams(dp(if (widthDp >= 1200) 316 else 272), ViewGroup.LayoutParams.MATCH_PARENT))

        val content = FrameLayout(this)
        listOf(buildStatusPage(), buildCalibrationPage(), buildStrengthPage(), buildFilteringPage(), buildDeveloperPage())
            .forEach { p -> pages.add(p); p.visibility = View.GONE; content.addView(p) }
        root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    private fun buildSidebar(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = P.smooth(this@MainActivity, P.surface, P.R_SURFACE)
            setPadding(dp(8), dp(24), dp(8), dp(16))
        }
        panel.addView(P.text(this, "Face Bridge", T.HEADLINE).apply {
            setPadding(dp(16), 0, 0, dp(6))
            setOnClickListener { onTitleTap() }
        })
        panel.addView(P.text(this, "Face & eye tracking for VRChat", T.BODY_S, P.textSecondary).apply {
            setPadding(dp(16), 0, 0, dp(24))
        })

        // Rows sit on a FrameLayout so one highlight view can slide between them (spring).
        navColumn = FrameLayout(this)
        navHighlight = View(this).apply { background = P.smooth(this@MainActivity, P.selected, P.R_SELECTION); alpha = 0f }
        navColumn.addView(navHighlight, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = listOf(
            Triple(PAGE_STATUS, "Status", R.drawable.ic_nav_status),
            Triple(PAGE_CALIBRATION, "Calibration", R.drawable.ic_nav_calibration),
            Triple(PAGE_STRENGTH, "Strength", R.drawable.ic_nav_strength),
            Triple(PAGE_FILTERING, "Filtering", R.drawable.ic_nav_filter),
            Triple(PAGE_DEVELOPER, "Developer", R.drawable.ic_nav_dev),
        )
        for ((id, label, icon) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                isClickable = true
                isFocusable = true
                background = P.stateful(this@MainActivity, android.graphics.Color.TRANSPARENT, P.surfaceHover, P.surfacePressed, P.R_SELECTION)
                setOnClickListener { if (page != id) selectPage(id, animate = true) }
                addView(ImageView(this@MainActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(P.text(this@MainActivity, label, T.BODY).apply { setPadding(dp(14), 0, 0, 0) })
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))  // 56 dp hotspot
            navRows.add(row)
            if (id == PAGE_DEVELOPER) devNav = row
        }
        devNav.visibility = if (DevMode.enabled) View.VISIBLE else View.GONE
        navColumn.addView(list)
        panel.addView(navColumn)
        panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        panel.addView(P.text(this, "Version " + packageManager.getPackageInfo(packageName, 0).versionName, T.BODY_T, P.textTertiary).apply {
            setPadding(dp(16), 0, 0, 0)
        })
        return panel
    }

    private fun selectPage(id: Int, animate: Boolean) {
        val previous = page
        page = id
        // Page transition: outgoing fades out fast, incoming fades/slides in with the gentle curve.
        pages.forEachIndexed { i, v ->
            when {
                i == id -> {
                    v.visibility = View.VISIBLE
                    if (animate) {
                        v.alpha = 0f; v.translationY = dp(12).toFloat()
                        v.animate().alpha(1f).translationY(0f).setDuration(Motion.PAGE_MS).setInterpolator(Motion.gentle).start()
                    } else { v.alpha = 1f; v.translationY = 0f }
                }
                i == previous && animate -> v.animate().alpha(0f).setDuration(Motion.FEEDBACK_MS).setInterpolator(Motion.exit)
                    .withEndAction { if (page != i) v.visibility = View.GONE }.start()
                else -> v.visibility = View.GONE
            }
        }
        // Selection highlight slides to the row with a spring (direct response to the user's action).
        val row = navRows[id]
        val targetY = row.top.toFloat()
        if (!animate || navHighlight.alpha == 0f) {
            navHighlight.translationY = targetY
            navHighlight.animate().alpha(1f).setDuration(Motion.SMALL_MS).start()
        } else {
            navHighlight.animate().translationY(targetY).setDuration(Motion.SPRING_MS).setInterpolator(Motion.spring).start()
        }
        navRows.forEachIndexed { i, r ->
            val sel = i == id
            (r.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (sel) P.text else P.textSecondary)
            val label = r.getChildAt(1) as TextView
            P.styleText(label, if (sel) T.TITLE else T.BODY, if (sel) P.text else P.textSecondary)
        }
    }

    /** Scrollable page with a headline. */
    private fun pageShell(title: String, subtitle: String? = null, body: LinearLayout.() -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(32), dp(40), dp(32))
        }
        col.addView(P.text(this, title, T.HEADLINE))
        if (subtitle != null) col.addView(P.text(this, subtitle, T.BODY_S_MULTI, P.textSecondary).apply { setPadding(0, dp(8), 0, 0) })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(28)))
        col.body()
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    /** List item: label (+ caption) on the leading side, control on the trailing side. */
    private fun row(parent: LinearLayout, label: String, caption: String? = null, control: View? = null): TextView? {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(P.text(this, label, T.BODY))
        var captionView: TextView? = null
        if (caption != null) {
            captionView = P.text(this, caption, T.BODY_S, P.textSecondary).apply { setPadding(0, dp(4), dp(16), 0) }
            texts.addView(captionView)
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (control != null) r.addView(control)
        if (parent.childCount > 0) parent.addView(P.dividerView(this))
        parent.addView(r)
        return captionView
    }

    private fun valueRow(parent: LinearLayout, label: String): TextView {
        val v = P.text(this, "—", T.BODY, P.textSecondary).apply { gravity = Gravity.END }
        row(parent, label, null, v)
        return v
    }

    private inner class Slider(
        parent: LinearLayout, name: String, initial: Float,
        private val min: Float, max: Float, private val step: Float, private val fmt: String,
        caption: String? = null, onChange: (Float) -> Unit,
    ) {
        private val value = P.text(this@MainActivity, "", T.BODY, P.textSecondary)
        private val bar = P.seekBar(this@MainActivity)

        init {
            if (parent.childCount > 0) parent.addView(P.dividerView(this@MainActivity))
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, dp(10))
            }
            val head = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(P.text(this@MainActivity, name, T.BODY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(value)
            box.addView(head)
            if (caption != null) box.addView(P.text(this@MainActivity, caption, T.BODY_S, P.textSecondary).apply { setPadding(0, dp(4), 0, 0) })
            bar.max = ((max - min) / step).roundToInt()
            set(initial)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    value.text = fmt.format(v)
                    if (fromUser) onChange(v)
                }
                // Knob grows slightly while dragged (direct manipulation feedback, spring release).
                override fun onStartTrackingTouch(b: SeekBar) {
                    value.animate().scaleX(1.12f).scaleY(1.12f).setDuration(Motion.FEEDBACK_MS).setInterpolator(Motion.fast).start()
                    P.styleText(value, T.TITLE, P.text)
                }
                override fun onStopTrackingTouch(b: SeekBar) {
                    value.animate().scaleX(1f).scaleY(1f).setDuration(Motion.SPRING_MS).setInterpolator(Motion.spring).start()
                    P.styleText(value, T.BODY, P.textSecondary)
                }
            })
            value.pivotX = 0f
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); leftMargin = -dp(12); rightMargin = -dp(12)
            })
            parent.addView(box)
        }

        fun set(v: Float) {
            bar.progress = ((v - min) / step).roundToInt().coerceIn(0, bar.max)
            value.text = fmt.format(v)
        }
    }

    private fun buttons(parent: LinearLayout, vararg b: TextView) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        b.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) leftMargin = dp(12)
            })
        }
        parent.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20) })
    }

    // ------------------------------------------------------------------ pages

    private fun buildStatusPage() = pageShell("Status") {
        permGroup = P.group(this@MainActivity).apply {
            row(this, "Tracking permission needed", "Allow eye and face tracking so the bridge can read the headset sensors.",
                P.button(this@MainActivity, "Allow", Role.PRIMARY) { requestTrackingPermissions() })
        }
        addView(permGroup)

        // Hero: state + Start/Stop
        val hero = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = P.smooth(this@MainActivity, P.surface, P.R_SURFACE)
            setPadding(dp(24), dp(24), dp(20), dp(24))
        }
        stateDot = View(this@MainActivity).apply { background = P.smooth(this@MainActivity, P.textTertiary, 999f) }
        hero.addView(stateDot, LinearLayout.LayoutParams(dp(12), dp(12)))
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        stateText = P.text(this@MainActivity, "Stopped", T.TITLE_L)
        stateSub = P.text(this@MainActivity, "", T.BODY_S, P.textSecondary).apply { setPadding(0, dp(6), 0, 0) }
        texts.addView(stateText)
        texts.addView(stateSub)
        hero.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        startButton = P.button(this@MainActivity, "Start", Role.ACCESSIBLE, large = true) { toggleService() }
        hero.addView(startButton)
        addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(28) })

        addView(P.sectionLabel(this@MainActivity, "Connection"))
        val g = P.group(this@MainActivity)
        headsetValue = valueRow(g, "Headset")
        avatarValue = valueRow(g, "Avatar")
        outputValue = valueRow(g, "Output")
        addView(g)

        addView(P.sectionLabel(this@MainActivity, "General"))
        val g2 = P.group(this@MainActivity)
        row(g2, "Track only while VRChat runs", "Saves battery: the headset's face cameras stay off until VRChat is open and the avatar uses face tracking",
            P.switch(this@MainActivity, Power.onlyWithVrchat(this@MainActivity)) { Power.setOnlyWithVrchat(this@MainActivity, it) })
        addView(g2)
    }

    private fun buildCalibrationPage() = pageShell("Calibration",
        "Records your resting face and full range of expressions, so each one maps to your own face.") {
        val g = P.group(this@MainActivity)
        calSubtitle = row(g, "Personal calibration", " ", null)!!
        calSwitch = P.switch(this@MainActivity, false) { on ->
            val c = Calibration.load(this@MainActivity)
            Calibration.save(this@MainActivity, c.copy(enabled = on && c.data != null))
            updateCalibrationText()
        }
        row(g, "Use my calibration", null, calSwitch)
        Slider(g, "Range", Calibration.load(this@MainActivity).range, 0f, 1f, 0.05f, "%.2f",
            "0 only removes resting offsets · 1 stretches each expression to your recorded maximum") {
            Calibration.save(this@MainActivity, Calibration.load(this@MainActivity).copy(range = it))
        }
        addView(g)
        buttons(this,
            P.button(this@MainActivity, "Calibrate", Role.PRIMARY, large = true) {
                startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
            },
            P.button(this@MainActivity, "Reset", Role.DANGER, large = true) {
                if (Calibration.load(this@MainActivity).data == null) return@button
                P.confirm(this@MainActivity, "Reset calibration?",
                    "Your recorded resting face and expression ranges will be deleted. You can calibrate again at any time.",
                    "Reset", dangerous = true) {
                    Calibration.save(this@MainActivity, Calibration.Values(null, false, 0L, Calibration.load(this@MainActivity).range))
                    updateCalibrationText()
                }
            })
        addView(P.text(this@MainActivity, "A guided session of 17 steps, about 2 minutes. Works over a running VRChat.", T.BODY_S, P.textSecondary))
        updateCalibrationText()
    }

    private val tuning by lazy { Tuning.load(this) }
    private var tFace = 0f
    private var tEye = 0f
    private var tJaw = 0f
    private var tEyeOsc = true
    private var tMuteVisemes = true
    private fun persistTuning() = Tuning.save(this, Tuning.Values(tFace, tEye, tEyeOsc, tJaw, tMuteVisemes))

    private fun buildStrengthPage(): View {
        tFace = tuning.faceGain; tEye = tuning.eyeGain; tJaw = tuning.jawGain; tEyeOsc = tuning.eyeOsc; tMuteVisemes = tuning.muteVisemes
        return pageShell("Strength", "How strongly your expressions drive the avatar.") {
            val g = P.group(this@MainActivity)
            Slider(g, "Face", tFace, 0.5f, 3f, 0.1f, "%.1f×") { tFace = it; persistTuning() }
            Slider(g, "Jaw", tJaw, 0.5f, 3f, 0.1f, "%.1f×") { tJaw = it; persistTuning() }
            Slider(g, "Eyes", tEye, 0.5f, 3f, 0.1f, "%.1f×") { tEye = it; persistTuning() }
            addView(g)
        }
    }

    private fun buildFilteringPage(): View {
        val fv = Filters.load(this)
        var flags = fv.flags
        var deadzone = fv.deadzone
        var smoothing = fv.smoothing
        fun persistFilters() = Filters.save(this, Filters.Values(flags, deadzone, smoothing))
        return pageShell("Filtering", "Keeps expressions clean and steady.") {
            val g = P.group(this@MainActivity)
            row(g, "Turn off avatar visemes", "While the mouth is tracked, on avatars with a VisemesEnable toggle",
                P.switch(this@MainActivity, tMuteVisemes) { tMuteVisemes = it; persistTuning() })
            Slider(g, "Smoothing", smoothing, 0f, 1f, 0.05f, "%.2f") { smoothing = it; persistFilters() }
            Slider(g, "Deadzone", deadzone, 0f, 0.3f, 0.01f, "%.2f") { deadzone = it; persistFilters() }
            addView(g)

            addView(P.sectionLabel(this@MainActivity, "Crosstalk rules"))
            val rules = P.group(this@MainActivity)
            for ((bit, name) in Filters.RULES) {
                row(rules, name, null, P.switch(this@MainActivity, flags and bit != 0) { on ->
                    flags = if (on) flags or bit else flags and bit.inv()
                    persistFilters()
                })
            }
            addView(rules)
            buttons(this, P.button(this@MainActivity, "Reset to defaults", Role.AUXILIARY) {
                P.confirm(this@MainActivity, "Reset filtering?", "Smoothing, deadzone and all crosstalk rules return to their defaults.",
                    "Reset", dangerous = false) {
                    Filters.save(this@MainActivity, Filters.Values(Filters.DEFAULT_FLAGS, Filters.DEFAULT_DEADZONE, Filters.DEFAULT_SMOOTHING))
                    recreate()
                }
            })
        }
    }

    private fun buildDeveloperPage() = pageShell("Developer", "Diagnostics. Tap “Face Bridge” 7 times to hide this page.") {
        addView(P.sectionLabel(this@MainActivity, "Data source"))
        val g = P.group(this@MainActivity)
        sourceGroup = RadioGroup(this@MainActivity).apply {
            setPadding(0, dp(8), 0, dp(8))
            for ((id, name) in listOf(
                NativeBridge.SOURCE_PICO to "PICO tracking service", NativeBridge.SOURCE_TEST to "Test pattern",
            )) {
                addView(RadioButton(this@MainActivity).apply {
                    this.id = id
                    text = name
                    P.styleText(this, T.BODY, P.text)
                    buttonTintList = ColorStateList.valueOf(P.blue)
                    minHeight = dp(48)   // Option container height 48 dp
                })
            }
            check(if (OscBridgeService.lastSource == NativeBridge.SOURCE_TEST) NativeBridge.SOURCE_TEST else NativeBridge.SOURCE_PICO)
            setOnCheckedChangeListener { _, id ->
                if (OscBridgeService.isRunning) NativeBridge.nativeSetSource(id)
            }
        }
        g.addView(sourceGroup)
        addView(g)

        val g2 = P.group(this@MainActivity)
        row(g2, "OSC eye tracking", "/tracking/eye/* for avatars using VRChat eye tracking",
            P.switch(this@MainActivity, tuning.eyeOsc) { tEyeOsc = it; persistTuning() })
        addView(g2)
        buttons(this,
            P.button(this@MainActivity, "Send test chat message", Role.AUXILIARY) {
                if (OscBridgeService.isRunning) NativeBridge.nativeSendChatbox("Face Bridge test") else toast("Start the bridge first")
            },
            P.button(this@MainActivity, "Turn off developer mode", Role.BORDERLESS) { setDevMode(false) })

        addView(P.sectionLabel(this@MainActivity, "Live counters"))
        val g3 = P.group(this@MainActivity)
        devStats = P.text(this@MainActivity, "", T.BODY_T, P.textSecondary).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(14), 0, dp(14))
        }
        g3.addView(devStats)
        addView(g3)

        addView(P.sectionLabel(this@MainActivity, "adb"))
        val g4 = P.group(this@MainActivity)
        g4.addView(P.text(this@MainActivity,
            "adb shell dumpsys deviceidle whitelist +io.github.staperfeed.facebridge\n" +
                "adb shell am start-foreground-service -n io.github.staperfeed.facebridge/.OscBridgeService " +
                "-a io.github.staperfeed.facebridge.START --ei source 0\n" +
                "adb logcat -s VrcftBridge", T.BODY_T, P.textSecondary).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(14), 0, dp(14))
        })
        addView(g4)
    }

    // ------------------------------------------------------------------ actions

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun onTitleTap() {
        val now = System.currentTimeMillis()
        titleTaps = if (now - lastTitleTap < 1500) titleTaps + 1 else 1
        lastTitleTap = now
        if (titleTaps >= 7) {
            titleTaps = 0
            setDevMode(!DevMode.enabled)
        } else if (titleTaps >= 4) {
            toast(if (DevMode.enabled) "${7 - titleTaps} more to leave developer mode" else "${7 - titleTaps} more for developer mode")
        }
    }

    private fun setDevMode(on: Boolean) {
        DevMode.set(this, on)
        devNav.visibility = if (on) View.VISIBLE else View.GONE
        navColumn.post {
            if (!on && page == PAGE_DEVELOPER) selectPage(PAGE_STATUS, animate = true)
            if (on) selectPage(PAGE_DEVELOPER, animate = true)
        }
        toast(if (on) "Developer mode on" else "Developer mode off")
    }

    private fun toggleService() {
        if (OscBridgeService.isRunning) OscBridgeService.stop(this)
        else OscBridgeService.start(this, if (DevMode.enabled) sourceGroup.checkedRadioButtonId else NativeBridge.SOURCE_AUTO, true)
        ui.postDelayed({ updateStatus() }, 300)
    }

    private fun requestTrackingPermissions() {
        val missing = arrayOf(PERM_FACE, PERM_EYE).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        updatePermissionGroup()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionGroup()
    }

    private fun updatePermissionGroup() {
        val ok = arrayOf(PERM_FACE, PERM_EYE).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (::permGroup.isInitialized) permGroup.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun updateCalibrationText() {
        val c = Calibration.load(this)
        shownCalibrationTime = c.timeMs
        calSubtitle.text = if (c.data == null) "Not calibrated yet"
        else "Calibrated " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.timeMs))
        P.setEnabledLook(calSwitch, c.data != null)
        calSwitch.isChecked = c.enabled
    }

    // ------------------------------------------------------------------ status

    private fun updateStatus() {
        NativeBridge.nativeGetStats(stats)
        val now = System.currentTimeMillis()
        val frames = stats[NativeBridge.STAT_FACE_FRAMES]
        val fps = if (lastSampleAt != 0L) (frames - lastFrames) * 1000.0 / (now - lastSampleAt).coerceAtLeast(1) else 0.0
        lastFrames = frames
        lastSampleAt = now

        val running = OscBridgeService.isRunning
        val sinceFace = stats[NativeBridge.STAT_MS_SINCE_FACE]
        val tracking = running && sinceFace in 0..1000
        val vrchat = OscQueryClient.connected
        val validity = stats[NativeBridge.STAT_FACE_VALIDITY].toInt()

        val (color, state, sub) = when {
            !running -> Triple(P.textTertiary, "Stopped", "Start to stream your face to VRChat")
            OscBridgeService.standbyReason != null && !tracking ->
                Triple(P.blue, "Standing by", "${OscBridgeService.standbyReason} · face cameras off to save battery")
            tracking && vrchat -> Triple(P.green, "Streaming to VRChat", "Expressions are live on your avatar")
            tracking -> Triple(P.amber, "Waiting for VRChat", "Turn on OSC in VRChat’s Action Menu")
            else -> Triple(P.amber, OscBridgeService.sessionProblem ?: "Starting tracking…", "The headset sensors start when you wear it")
        }
        // Status color change is animated.
        Motion.colorTo(stateColor, color) { (stateDot.background as SmoothRect).color = it }
        stateColor = color
        if (stateText.text != state) {
            stateText.alpha = 0f
            stateText.text = state
            stateText.animate().alpha(1f).setDuration(Motion.SMALL_MS).setInterpolator(Motion.fast).start()
        }
        stateSub.text = sub

        headsetValue.text = when {
            !running -> "—"
            OscBridgeService.standbyReason != null && !tracking -> "Standby · ${OscBridgeService.standbyReason}"
            tracking -> "Tracking · %.0f fps".format(fps) +
                (if (validity and 1 == 0) " · eyes lost" else "") + (if (validity and 2 == 0) " · face lost" else "")
            else -> OscBridgeService.sessionProblem ?: "Connecting…"
        }
        avatarValue.text = when {
            !running -> "—"
            !vrchat -> "VRChat not found"
            OscQueryClient.paramCount > 0 -> "${OscQueryClient.avatarName} · ${OscQueryClient.paramCount} params"
            OscQueryClient.nativeFaceTracking -> "${OscQueryClient.avatarName} · native"
            else -> "${OscQueryClient.avatarName} · no face tracking"
        }
        outputValue.text = if (running) "127.0.0.1:9000" else "—"

        val label = if (running) "Stop" else "Start"
        if (startButton.text != label) {
            startButton.text = label
            P.applyRole(startButton, if (running) Role.AUXILIARY else Role.ACCESSIBLE)
        }

        if (DevMode.enabled) {
            devStats.text = buildString {
                appendLine("blob sent ${stats[NativeBridge.STAT_SENT]}  errors ${stats[NativeBridge.STAT_SEND_ERRORS]}")
                appendLine("frames $frames  no-data ${stats[NativeBridge.STAT_NO_DATA]}  last ${if (sinceFace < 0) "never" else "$sinceFace ms"}")
                appendLine("ring ${if (stats[NativeBridge.STAT_RING_ATTACHED] == 1L) "mapped" else "not mapped"}  validity eye=${validity and 1} face=${(validity shr 1) and 1}")
                appendLine("source ${stats[NativeBridge.STAT_ACTIVE_SOURCE]}  avatarFT ${stats[NativeBridge.STAT_AVATAR_FT]}  standby ${OscBridgeService.standbyReason ?: "-"}")
                appendLine("visemes ${OscQueryClient.visemeParams.joinToString(" ") { it.substringAfterLast('/') }}")
                append(NativeBridge.nativeBackendInfo())
            }
        }
    }
}
