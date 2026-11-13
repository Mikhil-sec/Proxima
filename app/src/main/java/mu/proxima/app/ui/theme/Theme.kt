package mu.proxima.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import mu.proxima.app.R
import mu.proxima.core.model.Severity

/** "Field Terminal" palette (docs/lanes/UI.md). Nothing outside this object picks colours. */
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

private val ProximaColors = darkColorScheme(
    primary = Px.Green,
    onPrimary = Px.Black,
    secondary = Px.Amber,
    onSecondary = Px.Black,
    tertiary = Px.Green,
    error = Px.Crimson,
    onError = Px.Black,
    background = Px.Black,
    onBackground = Px.Text,
    surface = Px.Surface,
    onSurface = Px.Text,
    surfaceVariant = Px.SurfaceHi,
    onSurfaceVariant = Px.Dim,
    surfaceContainerLowest = Px.Black,
    surfaceContainerLow = Px.Surface,
    surfaceContainer = Px.Surface,
    surfaceContainerHigh = Px.SurfaceHi,
    surfaceContainerHighest = Px.SurfaceHi,
    outline = Px.Line,
    outlineVariant = Px.Line,
)

private val ProximaType = Typography(
    displayLarge = grotesk(44),
    headlineLarge = grotesk(32),
    headlineMedium = grotesk(26),
    titleLarge = grotesk(22),
    titleMedium = grotesk(18, FontWeight.Medium),
    bodyLarge = grotesk(18, FontWeight.Medium),
    bodyMedium = grotesk(16, FontWeight.Medium),
    bodySmall = grotesk(14, FontWeight.Medium, Px.Dim),
    // Labels stay in Grotesk: mono is reserved for machine data (see the anti-slop checklist).
    labelLarge = grotesk(16, FontWeight.Bold),
    labelMedium = grotesk(14, FontWeight.Medium, Px.Dim),
    labelSmall = grotesk(12, FontWeight.Medium, Px.Dim),
)

@Composable
fun ProximaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ProximaColors, typography = ProximaType, content = content)
}
