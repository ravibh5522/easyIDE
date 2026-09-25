package dev.easyide.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import dev.easyide.app.extensions.adapters.SvgCap
import dev.easyide.app.extensions.adapters.SvgClip
import dev.easyide.app.extensions.adapters.SvgGradient
import dev.easyide.app.extensions.adapters.SvgIcon
import dev.easyide.app.extensions.adapters.SvgJoin
import dev.easyide.app.extensions.adapters.SvgKind
import dev.easyide.app.extensions.adapters.SvgShape

/**
 * Draws a parsed [SvgIcon] into a square ARGB bitmap of [px] pixels, at the pixel size the row
 * needs (so a 16dp icon on a 2.5x screen is rasterised at 40px, not scaled up from a thumbnail).
 * Colours are the icon's own: nothing is tinted. The view box is fitted to the square and centred,
 * as `preserveAspectRatio="xMidYMid meet"` does.
 */
object SvgRaster {

    fun render(icon: SvgIcon, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = minOf(px / icon.width, px / icon.height)
        canvas.translate((px - icon.width * scale) / 2f, (px - icon.height * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-icon.minX, -icon.minY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (shape in icon.shapes) draw(canvas, paint, shape)
        return bitmap
    }

    private fun matrixOf(m: FloatArray) = Matrix().apply { setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)) }

    private fun draw(canvas: Canvas, paint: Paint, s: SvgShape) {
        val path = pathOf(s) ?: return
        path.fillType = if (s.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val m = s.transform
        val matrix = matrixOf(m)
        val box = RectF().also { path.computeBounds(it, true) }
        path.transform(matrix)
        val saved = canvas.save()
        for (clip in s.clips) canvas.clipPath(clipPath(clip))
        paintShape(canvas, paint, s, path, box, matrix, m)
        canvas.restoreToCount(saved)
    }

    private fun clipPath(clip: SvgClip): Path {
        val union = Path()
        for (c in clip.shapes) pathOf(c)?.let { p -> p.transform(matrixOf(c.transform)); union.addPath(p) }
        return union
    }

    private fun paintShape(canvas: Canvas, paint: Paint, s: SvgShape, path: Path, box: RectF, matrix: Matrix, m: FloatArray) {
        // A stroke scales with the transform, as it does in SVG.
        val scale = Math.sqrt((m[0] * m[3] - m[1] * m[2]).toDouble().coerceAtLeast(0.0)).toFloat()
        val filled = s.kind != SvgKind.LINE && s.kind != SvgKind.POLYLINE
        val gradient = s.fillGradient
        if (gradient != null && filled && box.width() > 0f && box.height() > 0f) {
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.shader = shaderOf(gradient, box, matrix)
            paint.alpha = (255 * s.gradientAlpha).toInt().coerceIn(0, 255)
            canvas.drawPath(path, paint)
        } else if (s.fill != 0 && filled) {
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = s.fill
            canvas.drawPath(path, paint)
        }
        if (s.stroke != 0) {
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = s.stroke
            paint.strokeWidth = s.strokeWidth * scale
            paint.strokeCap = when (s.cap) { SvgCap.BUTT -> Paint.Cap.BUTT; SvgCap.ROUND -> Paint.Cap.ROUND; SvgCap.SQUARE -> Paint.Cap.SQUARE }
            paint.strokeJoin = when (s.join) { SvgJoin.MITER -> Paint.Join.MITER; SvgJoin.ROUND -> Paint.Join.ROUND; SvgJoin.BEVEL -> Paint.Join.BEVEL }
            canvas.drawPath(path, paint)
        }
    }

    /** The gradient's space is the shape's box (fractions) or user space, then `gradientTransform`, then the shape's transform. */
    private fun shaderOf(g: SvgGradient, box: RectF, shape: Matrix): Shader {
        val local = Matrix(shape)
        if (g.boxUnits) local.preConcat(Matrix().apply { setScale(box.width(), box.height()); postTranslate(box.left, box.top) })
        local.preConcat(matrixOf(g.transform))
        val c = g.coords
        val shader = if (g.radial) RadialGradient(c[0], c[1], c[2].coerceAtLeast(MIN_RADIUS), g.colors, g.offsets, Shader.TileMode.CLAMP)
        else LinearGradient(c[0], c[1], c[2], c[3], g.colors, g.offsets, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(local)
        return shader
    }

    private const val MIN_RADIUS = 1e-4f

    private fun pathOf(s: SvgShape): Path? {
        val n = s.n
        val p = Path()
        when (s.kind) {
            // I/O boundary: the path data is file content; a bad `d` draws nothing.
            SvgKind.PATH -> return try { PathParser.createPathFromPathData(s.pathData) } catch (e: RuntimeException) { null }
            SvgKind.RECT -> if (n[2] > 0f && n[3] > 0f) p.addRoundRect(RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]), n[4], n[5], Path.Direction.CW)
            SvgKind.CIRCLE -> p.addCircle(n[0], n[1], n[2], Path.Direction.CW)
            SvgKind.ELLIPSE -> p.addOval(RectF(n[0] - n[2], n[1] - n[3], n[0] + n[2], n[1] + n[3]), Path.Direction.CW)
            SvgKind.LINE -> { p.moveTo(n[0], n[1]); p.lineTo(n[2], n[3]) }
            SvgKind.POLYGON, SvgKind.POLYLINE -> {
                for (i in 0 until n.size / 2) if (i == 0) p.moveTo(n[0], n[1]) else p.lineTo(n[2 * i], n[2 * i + 1])
                if (s.kind == SvgKind.POLYGON) p.close()
            }
        }
        return p
    }
}
