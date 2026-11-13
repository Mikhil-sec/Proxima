package mu.proxima.app.ui.components

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono

/** True when the user turned animations off (Developer options or accessibility). Ambient motion must stop. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The one filled button style. Black text on a saturated fill (white on crimson fails contrast at small sizes).
 * Shrinks slightly under the finger so a stressed user feels the press land.
 */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** White for neutral actions; crimson only for sending an alert. Green is reserved for meaning. */
    color: Color = Px.Text,
    glyph: Glyph? = null,
    height: Dp = 72.dp,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val fill by animateColorAsState(if (enabled) color else Px.SurfaceHi, label = "fill")
    val ink = if (enabled) Px.Black else Px.Dim
    Row(
        modifier
            .scale(scale)
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(fill)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, ink, 26.dp)
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = grotesk(20, FontWeight.Bold, ink))
    }
}

/** Secondary choice: visibly quieter than [PrimaryAction], still a full 48dp target. */
@Composable
fun QuietAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Px.Text) {
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = grotesk(17, FontWeight.Medium, color))
    }
}

/** Small outlined status badge for 3-letter machine states (SIM, UWB, GPS). */
@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = mono(11, FontWeight.Bold, color))
    }
}

@Composable
fun SimBadge(modifier: Modifier = Modifier) = Badge("SIM", Px.Amber, modifier)

/** "6 phones in range": the civilian-facing proof that the mesh is alive. */
@Composable
fun MeshStatus(peerCount: Int, modifier: Modifier = Modifier) {
    val alive = peerCount > 0
    val color = if (alive) Px.Green else Px.Amber
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            when (peerCount) {
                0 -> "Looking for phones"
                1 -> "1 phone in range"
                else -> "$peerCount phones in range"
            },
            style = grotesk(14, FontWeight.Medium, if (alive) Px.Text else Px.Amber),
        )
    }
}

/** Verified vs unverified is never colour alone: glyph + word. */
@Composable
fun VerifiedMark(valid: Boolean, modifier: Modifier = Modifier) {
    val color = if (valid) Px.Green else Px.Amber
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(if (valid) Glyph.Check else Glyph.Alert, color, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(if (valid) "Verified" else "Unverified", style = grotesk(13, FontWeight.Medium, color))
    }
}

/** Two-state switch drawn in the palette (no Material Switch). */
@Composable
fun FieldSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, label = "knob")
    val track = if (checked) Px.Amber else Px.Line
    Canvas(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(role = Role.Switch) { onChange(!checked) },
    ) {
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val r = size.height / 2 - 4.dp.toPx()
        val x = size.height / 2 + knob * (size.width - size.height)
        drawCircle(if (checked) Px.Black else Px.Dim, r, androidx.compose.ui.geometry.Offset(x, size.height / 2))
    }
}

/** A vertical severity rail that starts every incident row. Encodes severity, nothing else. */
@Composable
fun Rail(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.width(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

/** Thin divider between *different* groups only. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Px.Line))
}

/** Back control for civilian sub-screens: a 48dp target with a drawn arrow, no app bar chrome. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Go back", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Back, Px.Text, 24.dp, description = "Back")
    }
}
