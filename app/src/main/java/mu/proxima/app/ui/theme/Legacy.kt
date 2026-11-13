package mu.proxima.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import mu.proxima.app.R
import mu.proxima.core.model.Severity

/**
 * TEMPORARY: the pre-overhaul "Field Terminal" helpers, kept only so screens compile during Phase 1.
 * Phase 2 of the ui-android overhaul removes every use and deletes this file. Old look: git tag ui-v1-field-terminal.
 */
object Px {
    val Black = Color(0xFF05070A)
    val Surface = Color(0xFF0F141C)
    val SurfaceHi = Color(0xFF161D28)
    val Line = Color(0xFF232C3A)
    val Green = Color(0xFF00FF9D)
    val Amber = Color(0xFFFFB800)
    val Crimson = Color(0xFFFF2A55)
    val Text = Color(0xFFE6EDF3)
    val Dim = Color(0xFF7D8590)
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

val Grotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

fun Severity.color(): Color = when (this) {
    Severity.CRITICAL -> Px.Crimson
    Severity.HIGH -> Color(0xFFFF6A3D)
    Severity.MEDIUM -> Px.Amber
    Severity.LOW -> Px.Dim
}

/** Monospace telemetry text: numbers, ids, dBm, hops, timestamps. */
fun mono(size: Int = 12, weight: FontWeight = FontWeight.Normal, color: Color = Px.Dim) =
    TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = 0.5.sp)

/** Civilian-register headline text. */
fun grotesk(size: Int, weight: FontWeight = FontWeight.Bold, color: Color = Px.Text) =
    TextStyle(fontFamily = Grotesk, fontSize = size.sp, fontWeight = weight, color = color, lineHeight = (size * 1.2).sp)
