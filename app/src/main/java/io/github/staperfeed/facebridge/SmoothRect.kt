package io.github.staperfeed.facebridge

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rounded rectangle with continuous-curvature ("smoothed", squircle-like) corners — the same
 * construction as Figma's corner smoothing: each corner is a short circular arc blended into the
 * straight edges by two cubic Béziers, so curvature ramps up instead of jumping at the tangent point.
 * PICO's design language uses rounded, "warm and natural" shapes; a plain circular arc shows a visible
 * kink where it meets the edge on large radii (32 dp windows), the smoothed corner does not.
 *
 * smoothing 0 = plain rounded rect, 0.6 = Figma/iOS default. When the radius is limited by the size
 * (pill shapes) smoothing is reduced automatically, so a pill stays a true stadium.
 */
class SmoothRect(color: Int, var radius: Float, var smoothing: Float = 0.6f) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val path = Path()

    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidateSelf() }

    override fun onBoundsChange(bounds: Rect) {
        build(path, bounds.left.toFloat(), bounds.top.toFloat(), bounds.width().toFloat(), bounds.height().toFloat(), radius, smoothing)
    }

    override fun draw(canvas: Canvas) {
        if (path.isEmpty) onBoundsChange(bounds)
        canvas.drawPath(path, paint)
    }

    override fun getOutline(outline: Outline) {
        // Convex path outline: correct elevation shadows that follow the smoothed shape.
        if (path.isEmpty) onBoundsChange(bounds)
        @Suppress("DEPRECATION")
        outline.setConvexPath(path)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Builds the smoothed rounded-rect path into [out]. */
        fun build(out: Path, x: Float, y: Float, w: Float, h: Float, radiusIn: Float, smoothingIn: Float) {
            out.reset()
            if (w <= 0f || h <= 0f) return
            val budget = min(w, h) / 2f
            val r = min(radiusIn, budget)
            if (r <= 0f) { out.addRect(x, y, x + w, y + h, Path.Direction.CW); return }
            var s = smoothingIn.coerceIn(0f, 1f)
            var p = (1f + s) * r
            if (p > budget) { s = max(0f, min(s, budget / r - 1f)); p = budget }

            val arcMeasure = 90f * (1f - s)
            val arcSection = sin(Math.toRadians(arcMeasure / 2.0)).toFloat() * r * sqrt(2f)
            val angleAlpha = (90f - arcMeasure) / 2f
            val p3ToP4 = r * tan(Math.toRadians(angleAlpha / 2.0)).toFloat()
            val angleBeta = 45f * s
            val c = p3ToP4 * cos(Math.toRadians(angleBeta.toDouble())).toFloat()
            val d = c * tan(Math.toRadians(angleBeta.toDouble())).toFloat()
            val b = (p - arcSection - c - d) / 3f
            val a = 2f * b

            // Corners clockwise from top-right. K = corner point, u = incoming edge direction, v = outgoing.
            val corners = arrayOf(
                floatArrayOf(x + w, y, 1f, 0f, 0f, 1f),      // top-right
                floatArrayOf(x + w, y + h, 0f, 1f, -1f, 0f),  // bottom-right
                floatArrayOf(x, y + h, -1f, 0f, 0f, -1f),     // bottom-left
                floatArrayOf(x, y, 0f, -1f, 1f, 0f),          // top-left
            )
            for ((i, k) in corners.withIndex()) {
                fun g(lx: Float, ly: Float) = PointF(k[0] + k[2] * lx + k[4] * ly, k[1] + k[3] * lx + k[5] * ly)
                val start = g(-p, 0f)
                if (i == 0) out.moveTo(start.x, start.y) else out.lineTo(start.x, start.y)
                val e1 = PointF(-p + a + b + c, d)
                val e2 = PointF(e1.x + arcSection, e1.y + arcSection)
                g(-p + a, 0f).let { c1 -> g(-p + a + b, 0f).let { c2 -> g(e1.x, e1.y).let { e -> out.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) } } }
                if (arcMeasure > 0.01f) arcAsBezier(out, g(-r, r), g(e1.x, e1.y), g(e2.x, e2.y), r, arcMeasure)
                else g(e2.x, e2.y).let { out.lineTo(it.x, it.y) }
                g(0f, e2.y + c).let { c3 -> g(0f, e2.y + b + c).let { c4 -> g(0f, p).let { e -> out.cubicTo(c3.x, c3.y, c4.x, c4.y, e.x, e.y) } } }
            }
            out.close()
        }

        /** Circular arc from p1 to p2 around center c as one cubic (sweep <= 90°). */
        private fun arcAsBezier(out: Path, c: PointF, p1: PointF, p2: PointF, r: Float, sweepDeg: Float) {
            val k = (4.0 / 3.0 * tan(Math.toRadians(sweepDeg / 4.0)) * r).toFloat()
            fun tangent(pt: PointF, toward: PointF): PointF {
                var tx = -(pt.y - c.y); var ty = pt.x - c.x
                val len = sqrt(tx * tx + ty * ty)
                tx /= len; ty /= len
                if (tx * (toward.x - pt.x) + ty * (toward.y - pt.y) < 0) { tx = -tx; ty = -ty }
                return PointF(tx, ty)
            }
            val t1 = tangent(p1, p2)
            val t2 = tangent(p2, p1)
            out.cubicTo(p1.x + t1.x * k, p1.y + t1.y * k, p2.x + t2.x * k, p2.y + t2.y * k, p2.x, p2.y)
        }
    }
}
