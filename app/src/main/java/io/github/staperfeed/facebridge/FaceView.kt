package io.github.staperfeed.facebridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Cute 2D mascot that acts out an expression (same family as the launcher icon's face glyph): round light
 * face on a soft blue glow, big glossy oval eyes, short rounded brows, pink cheeks, simple cartoon mouth.
 * Deliberately stylized (no nose/teeth/realistic shading) so it reads as friendly, not uncanny.
 * Driven by [Expr]; animates between expressions; used as the calibration prompt and as the live preview.
 */
class FaceView(context: Context) : View(context) {

    /** All values 0..1 except lookX/lookY/brow/jawX which are -1..1. */
    data class Expr(
        val blinkL: Float = 0f, val blinkR: Float = 0f, val wide: Float = 0f,
        val lookX: Float = 0f, val lookY: Float = 0f, val brow: Float = 0f,
        val smile: Float = 0f, val frown: Float = 0f, val jaw: Float = 0f, val pucker: Float = 0f,
        val puff: Float = 0f, val tongue: Float = 0f, val jawX: Float = 0f,
    ) {
        fun lerp(o: Expr, t: Float): Expr {
            fun l(a: Float, b: Float) = a + (b - a) * t
            return Expr(l(blinkL, o.blinkL), l(blinkR, o.blinkR), l(wide, o.wide), l(lookX, o.lookX), l(lookY, o.lookY),
                l(brow, o.brow), l(smile, o.smile), l(frown, o.frown), l(jaw, o.jaw), l(pucker, o.pucker),
                l(puff, o.puff), l(tongue, o.tongue), l(jawX, o.jawX))
        }

        companion object {
            /** From 52 raw ARKit-order shapes (see native Ak enum). */
            fun fromArkit(a: FloatArray): Expr {
                fun c(v: Float) = v.coerceIn(0f, 1f)
                val lookX = ((a[10] - a[9]) + (a[2] - a[3])) / 2f   // right eye out / left eye in = look right
                val lookY = ((a[4] - a[1]) + (a[11] - a[8])) / 2f
                return Expr(
                    blinkL = c(a[0]), blinkR = c(a[7]), wide = c((a[6] + a[13]) / 2f),
                    lookX = (lookX * 1.6f).coerceIn(-1f, 1f), lookY = (lookY * 1.6f).coerceIn(-1f, 1f),
                    brow = ((a[43] + (a[44] + a[45]) / 2f) / 2f - (a[41] + a[42]) / 2f).coerceIn(-1f, 1f),
                    smile = c((a[23] + a[24]) / 2f), frown = c((a[25] + a[26]) / 2f), jaw = c(a[17]),
                    pucker = c(max(a[20], a[19])), puff = c(a[46]), tongue = c(a[51]),
                    jawX = (a[16] - a[15]).coerceIn(-1f, 1f),
                )
            }
        }
    }

    // Palette (matches the launcher icon)
    private val faceTop = Color.parseColor("#F4F8FF")
    private val faceBottom = Color.parseColor("#C9DBFF")
    private val ink = Color.parseColor("#1F2A4D")
    private val cheek = Color.parseColor("#FF9FB4")
    private val mouthIn = Color.parseColor("#3B2D52")
    private val tongueColor = Color.parseColor("#FF8FA6")

    private var current = Expr()
    private var from = Expr()
    private var target = Expr()
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()
    private var shaderSize = -1
    private var glowShader: Shader? = null
    private var faceShader: Shader? = null

    /** Animates towards [e] with the PICO gentle curve. */
    fun show(e: Expr, durationMs: Long = Motion.PAGE_MS) {
        animator?.cancel()
        from = current
        target = e
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Motion.gentle
            addUpdateListener { current = from.lerp(target, it.animatedValue as Float); invalidate() }
            start()
        }
    }

    /** Sets immediately (live preview). Light smoothing keeps it calm at ~23 fps. */
    fun set(e: Expr) {
        current = current.lerp(e, 0.5f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()   // a running animator holds the view (and its activity) until it ends
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val e = current
        val s = width / 200f                       // design space: 200 x 200
        canvas.save()
        canvas.scale(s, s)
        if (shaderSize != width) {                 // shaders depend only on the design space; build once
            shaderSize = width
            glowShader = RadialGradient(100f, 102f, 98f, intArrayOf(Color.argb(110, 70, 140, 255), Color.argb(0, 70, 140, 255)),
                null, Shader.TileMode.CLAMP)
            faceShader = LinearGradient(0f, 30f, 0f, 175f, faceTop, faceBottom, Shader.TileMode.CLAMP)
        }

        // Soft blue glow behind the face
        fill.shader = glowShader
        canvas.drawCircle(100f, 102f, 98f, fill)

        // Round face; cheeks puff out, a little squash when the jaw opens
        val puff = e.puff * 10f
        val drop = e.jaw * 6f
        oval.set(36f - puff, 34f - drop * 0.3f, 164f + puff, 168f + drop)
        fill.shader = faceShader
        canvas.drawOval(oval, fill)
        fill.shader = null

        // Cheeks
        val cheekAlpha = (70 + 120 * e.smile + 110 * e.puff).toInt().coerceAtMost(220)
        fill.color = Color.argb(cheekAlpha, Color.red(cheek), Color.green(cheek), Color.blue(cheek))
        val cr = 11f + 5f * e.puff
        oval.set(52f - puff - cr, 122f - cr * 0.6f, 52f - puff + cr, 122f + cr * 0.6f); canvas.drawOval(oval, fill)
        oval.set(148f + puff - cr, 122f - cr * 0.6f, 148f + puff + cr, 122f + cr * 0.6f); canvas.drawOval(oval, fill)

        drawEye(canvas, 72f, e.blinkL, e)
        drawEye(canvas, 128f, e.blinkR, e)

        // Brows: short rounded strokes; raise, lower, and tilt inward when frowning
        val by = 66f - e.brow * 9f - e.wide * 4f
        val tilt = if (e.brow < 0) -e.brow * 6f else 0f
        line.color = ink
        line.strokeWidth = 5f
        canvas.drawLine(62f, by, 82f, by + tilt, line)
        canvas.drawLine(118f, by + tilt, 138f, by, line)

        drawMouth(canvas, e)
        canvas.restore()
    }

    private fun drawEye(canvas: Canvas, x: Float, blink: Float, e: Expr) {
        val y = 98f
        val closed = blink.coerceIn(0f, 1f)
        val happy = e.smile > 0.2f && closed > 0.4f            // squint while smiling → "^ ^"
        if (closed > 0.85f || happy) {
            line.color = ink
            line.strokeWidth = 4.5f
            path.reset()
            if (happy) { path.moveTo(x - 10f, y + 3f); path.quadTo(x, y - 9f, x + 10f, y + 3f) }      // ^
            else { path.moveTo(x - 10f, y); path.quadTo(x, y + 7f, x + 10f, y) }                    // closed ‿
            canvas.drawPath(path, line)
            return
        }
        val rx = 9.5f + 2.5f * e.wide
        val ry = (14f + 4f * e.wide) * (1f - 0.8f * closed)
        val ex = x + e.lookX * 3.5f
        val ey = y - e.lookY * 3.5f
        fill.color = ink
        oval.set(ex - rx, ey - ry, ex + rx, ey + ry)
        canvas.drawOval(oval, fill)
        // Two highlights (glossy, cartoon)
        fill.color = Color.WHITE
        val hr = min(4.2f, ry * 0.45f)
        canvas.drawCircle(ex - rx * 0.32f + e.lookX * 1.5f, ey - ry * 0.38f - e.lookY * 1.5f, hr, fill)
        fill.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(ex + rx * 0.3f, ey + ry * 0.35f, hr * 0.45f, fill)
    }

    private fun drawMouth(canvas: Canvas, e: Expr) {
        val cx = 100f + e.jawX * 12f
        val cy = 136f
        line.color = ink
        line.strokeWidth = 4.5f

        // Kiss: small "o" with a rosy ring
        if (e.pucker > 0.5f && e.jaw < 0.5f) {
            val r = 5f + (1f - e.pucker) * 5f
            fill.color = mouthIn
            canvas.drawCircle(cx, cy + 2f, r, fill)
            line.color = cheek
            line.strokeWidth = 3.5f
            canvas.drawCircle(cx, cy + 2f, r + 1.5f, line)
            return
        }

        val open = e.jaw > 0.12f || e.tongue > 0.3f || e.smile > 0.75f
        val half = 13f + 13f * e.smile - 5f * e.frown - 6f * e.pucker
        if (!open) {
            // Simple curve: smile ⌣ or frown ⌢
            val bend = 7f * e.smile - 7f * e.frown + 2f
            path.reset()
            path.moveTo(cx - half, cy)
            path.quadTo(cx, cy + bend * 1.6f, cx + half, cy)
            canvas.drawPath(path, line)
            return
        }
        // Open mouth: "D" shape when laughing, rounded oval when the jaw drops
        val depth = 8f + 22f * e.jaw + 10f * e.smile
        path.reset()
        if (e.jaw < 0.35f && e.smile > 0.5f) {
            path.moveTo(cx - half, cy - 2f)
            path.lineTo(cx + half, cy - 2f)
            path.quadTo(cx + half, cy + depth, cx, cy + depth)
            path.quadTo(cx - half, cy + depth, cx - half, cy - 2f)
        } else {
            val w = max(10f, half * 0.75f)
            oval.set(cx - w, cy - 4f, cx + w, cy - 4f + depth + 4f)
            path.addOval(oval, Path.Direction.CW)
        }
        path.close()
        fill.color = mouthIn
        canvas.drawPath(path, fill)
        val b = path.let { val r = RectF(); it.computeBounds(r, true); r }
        // Grin (smile without a dropped jaw): show the upper teeth, no tongue
        if (e.jaw < 0.25f && e.tongue <= 0.3f) {
            canvas.save()
            canvas.clipPath(path)
            fill.color = Color.WHITE
            canvas.drawRect(b.left, b.top, b.right, b.top + b.height() * 0.42f, fill)
            canvas.restore()
            return
        }
        // Tongue: rests inside when the jaw is open, pokes out when stuck out
        canvas.save()
        if (e.tongue <= 0.3f) canvas.clipPath(path)
        fill.color = tongueColor
        val tw = min(12f, (b.width()) * 0.38f)
        val tBottom = b.bottom + e.tongue * 14f
        oval.set(b.centerX() - tw, tBottom - tw * 1.3f, b.centerX() + tw, tBottom + tw * 0.2f)
        canvas.drawOval(oval, fill)
        canvas.restore()
    }
}
