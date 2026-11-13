package mu.proxima.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn line glyphs. One stroke weight, one grammar, so the app does not look like a stock icon pack.
 * All drawn in a 24-unit box and scaled.
 */
enum class Glyph { Medical, Hazard, Person, Check, Alert, Wristband, Scan, Mesh, Close, Back, Mic, Keyboard }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 24.dp, description: String? = null, modifier: Modifier = Modifier) {
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Canvas(m.size(size)) { drawGlyph(glyph, color) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    when (glyph) {
        Glyph.Medical -> {
            val path = Path().apply {
                moveTo(9f * u, 3f * u); lineTo(15f * u, 3f * u); lineTo(15f * u, 9f * u); lineTo(21f * u, 9f * u)
                lineTo(21f * u, 15f * u); lineTo(15f * u, 15f * u); lineTo(15f * u, 21f * u); lineTo(9f * u, 21f * u)
                lineTo(9f * u, 15f * u); lineTo(3f * u, 15f * u); lineTo(3f * u, 9f * u); lineTo(9f * u, 9f * u); close()
            }
            drawPath(path, color, style = stroke)
        }
        Glyph.Hazard -> {
            val path = Path().apply { moveTo(12f * u, 3f * u); lineTo(22f * u, 20.5f * u); lineTo(2f * u, 20.5f * u); close() }
            drawPath(path, color, style = stroke)
            line(12f, 9.5f, 12f, 14f)
            drawCircle(color, 1.3f * u, p(12f, 17.2f))
        }
        Glyph.Person -> {
            drawCircle(color, 3.6f * u, p(12f, 6.5f), style = stroke)
            val body = Path().apply {
                moveTo(4.5f * u, 21f * u)
                cubicTo(4.5f * u, 15f * u, 8f * u, 12.5f * u, 12f * u, 12.5f * u)
                cubicTo(16f * u, 12.5f * u, 19.5f * u, 15f * u, 19.5f * u, 21f * u)
            }
            drawPath(body, color, style = stroke)
        }
        Glyph.Check -> {
            val path = Path().apply { moveTo(4f * u, 12.5f * u); lineTo(9.5f * u, 18f * u); lineTo(20f * u, 6.5f * u) }
            drawPath(path, color, style = stroke)
        }
        Glyph.Alert -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            line(12f, 7f, 12f, 13f)
            drawCircle(color, 1.3f * u, p(12f, 16.6f))
        }
        Glyph.Wristband -> {
            drawRoundRect(color, p(3f, 7f), Size(18f * u, 10f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * u), style = stroke)
            drawRect(color, p(9f, 9.5f), Size(6f * u, 5f * u), style = Stroke(1.6f * u))
        }
        Glyph.Scan -> {
            // Viewfinder corners + scan line
            listOf(3f to 3f, 21f to 3f, 3f to 21f, 21f to 21f).forEach { (x, y) ->
                val dx = if (x < 12f) 5f else -5f
                val dy = if (y < 12f) 5f else -5f
                line(x, y, x + dx, y); line(x, y, x, y + dy)
            }
            line(7f, 12f, 17f, 12f)
        }
        Glyph.Mesh -> {
            val pts = listOf(p(5f, 6f), p(19f, 5f), p(12f, 13f), p(4f, 19f), p(19f, 19f))
            listOf(0 to 2, 1 to 2, 2 to 3, 2 to 4, 3 to 4).forEach { (a, b) ->
                drawLine(color.copy(alpha = 0.6f), pts[a], pts[b], strokeWidth = 1.4f * u)
            }
            pts.forEach { drawCircle(color, 2.2f * u, it) }
        }
        Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.Back -> { line(20f, 12f, 4.5f, 12f); line(4.5f, 12f, 11f, 5.5f); line(4.5f, 12f, 11f, 18.5f) }
        Glyph.Mic -> {
            drawRoundRect(color, p(8.5f, 2.5f), Size(7f * u, 12f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.5f * u), style = stroke)
            val cup = Path().apply {
                moveTo(5f * u, 11f * u); cubicTo(5f * u, 19f * u, 19f * u, 19f * u, 19f * u, 11f * u)
            }
            drawPath(cup, color, style = stroke)
            line(12f, 18.2f, 12f, 21.5f)
        }
        Glyph.Keyboard -> {
            drawRoundRect(color, p(2.5f, 6f), Size(19f * u, 12f * u),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = stroke)
            listOf(6.5f, 10f, 13.5f, 17f).forEach { x -> drawCircle(color, 1.1f * u, p(x, 10f)) }
            line(8f, 14.2f, 16f, 14.2f)
        }
    }
}
