package mu.proxima.app.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing

/** True when the user turned animations off (Developer options or accessibility). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The large (56 dp) M3 [Button] for the one main action on a civilian screen.
 * [colors] lets the SOS flow use the critical role. A haptic confirms the press.
 */
@Composable
fun LargeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sym: Sym? = null,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClick() },
        modifier = modifier.fillMaxWidth().heightIn(min = MaterialTheme.sizes.buttonLarge),
        enabled = enabled,
        colors = colors,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (sym != null) {
            SymbolIcon(sym, description = null)
            Spacer(Modifier.width(MaterialTheme.spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun criticalButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.semantic.critical,
    contentColor = MaterialTheme.semantic.onCritical,
)

@Composable
fun safeButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.semantic.safe,
    contentColor = MaterialTheme.semantic.onSafe,
)

/** Back navigation for every sub-screen's TopAppBar. */
@Composable
fun BackIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) { SymbolIcon(Sym.Back, description = "Back") }
}

/** Small tonal label for machine states. Defaults to the caution container ("this is not the real thing"). */
@Composable
fun StateLabel(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.semantic.cautionContainer,
    content: Color = MaterialTheme.semantic.onCautionContainer,
) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
        )
    }
}

@Composable
fun SimLabel(modifier: Modifier = Modifier) = StateLabel("Simulation", modifier)

/** "6 phones nearby": the civilian-facing proof that the mesh is alive. Dot colour + words, never colour alone. */
@Composable
fun MeshStatus(peerCount: Int, modifier: Modifier = Modifier) {
    val alive = peerCount > 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(MaterialTheme.sizes.statusDot).clip(CircleShape)
                .background(if (alive) MaterialTheme.semantic.safe else MaterialTheme.semantic.caution),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.sm))
        Text(
            when (peerCount) { 0 -> "Looking for phones"; 1 -> "1 phone nearby"; else -> "$peerCount phones nearby" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Verified vs not: icon + word, never colour alone. */
@Composable
fun VerifiedMark(valid: Boolean, modifier: Modifier = Modifier) {
    val color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.semantic.caution
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SymbolIcon(if (valid) Sym.Verified else Sym.Error, description = null, tint = color, size = MaterialTheme.sizes.iconSmall)
        Spacer(Modifier.width(MaterialTheme.spacing.xs))
        Text(if (valid) "Verified" else "Not verified", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Round tonal badge with a symbol: leading element of incident rows. Severity container + on-container. */
@Composable
fun SeverityBadge(sym: Sym, container: Color, content: Color, description: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.size(MaterialTheme.sizes.touchTarget).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        SymbolIcon(sym, description = description, tint = content)
    }
}
