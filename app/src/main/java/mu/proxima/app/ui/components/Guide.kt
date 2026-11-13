package mu.proxima.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.ProtocolCard

/**
 * A vetted protocol card, with the AI lane's hands-free guide (#30) as a switch.
 * Off: every step, numbered. On: one step at a time, read aloud, moved on by voice ("next", "back", "repeat", "stop")
 * or by the buttons when voice isn't available. The AI only picks the card and the step; every word shown is the card's.
 * The guide stops when this leaves the screen, so it never holds the microphone against an SOS.
 */
@Composable
fun GuidedSteps(graph: ProximaGraph, card: ProtocolCard, modifier: Modifier = Modifier) {
    val state by graph.guide.state.collectAsStateWithLifecycle()
    // Started from here. The guide may switch cards by voice (CPR to recovery position) and is still ours.
    var mine by remember { mutableStateOf(false) }
    val on = mine && state.active
    DisposableEffect(Unit) { onDispose { if (mine) graph.guide.stop() } }
    fun set(want: Boolean) {
        if (want) { mine = true; graph.guide.start(card) } else { graph.guide.stop(); mine = false }
    }

    Column(modifier) {
        Text(if (on) state.cardTitle ?: card.title else card.title, style = MaterialTheme.typography.titleLarge)
        ListItem(
            modifier = Modifier.toggleable(value = on, role = Role.Switch, onValueChange = ::set),
            headlineContent = { Text("Hands-free", style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text("Reads each step aloud. Say \"next\", \"back\", \"repeat\" or \"stop\".") },
            leadingContent = { SymbolIcon(Sym.ReadAloud, null) },
            trailingContent = { Switch(checked = on, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (on) CurrentStep(graph) else AllSteps(card)
        Spacer(Modifier.height(MaterialTheme.spacing.xs))
        Text(
            "Source: ${(if (on) state.source else null) ?: card.source}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AllSteps(card: ProtocolCard) {
    card.steps.forEachIndexed { n, step ->
        // Number on the first line of the step, not centred against a five-line paragraph.
        Row(Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
            Text(
                "${n + 1}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(MaterialTheme.spacing.xxxl),
            )
            Text(step.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** One step, large, for someone kneeling next to a casualty with the phone on the ground. */
@Composable
private fun CurrentStep(graph: ProximaGraph) {
    val state by graph.guide.state.collectAsStateWithLifecycle()
    val count = state.stepCount.coerceAtLeast(1)
    val index = state.stepIndex.coerceIn(0, count - 1)

    // A voice command switched the card: say so, it changes what you're doing.
    state.notice?.let { notice ->
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(notice, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(MaterialTheme.spacing.md))
        }
        Spacer(Modifier.height(MaterialTheme.spacing.md))
    }
    Text(
        "Step ${index + 1} of $count",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(MaterialTheme.spacing.xs))
    Text(
        state.stepText.orEmpty(),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(MaterialTheme.spacing.lg))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)) {
        OutlinedButton(
            onClick = graph.guide::back,
            enabled = index > 0,
            modifier = Modifier.weight(1f).height(MaterialTheme.sizes.buttonLarge),
        ) { Text("Back") }
        Button(
            onClick = graph.guide::next,
            enabled = index < count - 1,
            modifier = Modifier.weight(1f).height(MaterialTheme.sizes.buttonLarge),
        ) { Text("Next") }
    }
    Spacer(Modifier.height(MaterialTheme.spacing.md))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SymbolIcon(Sym.Mic, null, size = MaterialTheme.sizes.iconSmall, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(MaterialTheme.spacing.sm))
        Text(
            when {
                !state.listening -> "Not listening. Use the buttons."
                state.heard != null -> "Heard \"${state.heard}\""
                else -> "Listening"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
