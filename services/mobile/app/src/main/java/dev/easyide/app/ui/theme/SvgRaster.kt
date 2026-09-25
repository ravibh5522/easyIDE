package dev.easyide.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import dev.easyide.app.extensions.adapters.SvgCap
import dev.easyide.app.extensions.adapters.SvgIcon
import dev.easyide.app.extensions.adapters.SvgJoin
import dev.easyide.app.extensions.adapters.SvgKind
import dev.easyide.app.extensions.adapters.SvgShape

/**
 * Draws a parsed [SvgIcon] into a square ARGB bitmap of [px] pixels, at the pixel size the row
 * needs (so a 16dp icon on a 2.5x screen is rasterised at 40px, not scaled up from a thumbnail).
 * Colours are the icon's own: nothing is tinted.
 */
object SvgRaster {

    fun render(icon: SvgIcon, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(px / icon.width, px / icon.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (shape in icon.shapes) draw(canvas, paint, shape)
        return bitmap
    }

    private fun draw(canvas: Canvas, paint: Paint, s: SvgShape) {
        val path = pathOf(s) ?: return
        path.fillType = if (s.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val m = s.transform
        val matrix = Matrix().apply { setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)) }
        path.transform(matrix)
        // A stroke scales with the transform, as it does in SVG.
        val scale = Math.sqrt((m[0] * m[3] - m[1] * m[2]).toDouble().coerceAtLeast(0.0)).toFloat()
        if (s.fill != 0 && s.kind != SvgKind.LINE && s.kind != SvgKind.POLYLINE) {
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
