package mu.proxima.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.components.Fact
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.Peer
import mu.proxima.mesh.MeshDemoControls

/** Simulation is read once when the graph is built, so switching it means a full restart. */
private fun restartWithSimulation(context: Context, on: Boolean) {
    context.getSharedPreferences("proxima", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("simulation", on) }
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    (context as? Activity)?.finishAffinity()
    Runtime.getRuntime().exit(0)
}

/** Hidden developer sheet (long-press the app name). For the team and the demo, not for festival-goers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevSheet(graph: ProximaGraph, nav: Nav, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val caps = graph.capabilities
    val triage by graph.triage.capability.collectAsState()
    var sim by remember { mutableStateOf(graph.simulation) }
    val rows = ListItemDefaults.colors(containerColor = Color.Transparent)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = MaterialTheme.spacing.xxl)) {
            Text("Developer", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(MaterialTheme.spacing.md))
            Fact("Device", caps.model)
            Fact("Role", caps.tier.label())
            Fact("Memory", "%.1f GB".format(caps.totalRamGb))
            Fact("UWB", if (caps.hasUwb) "Yes" else "No")
            Fact("Compass", if (caps.hasCompass) "Yes" else "No")
            Fact("Triage", triage.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
            Fact("Node", graph.mesh.selfNodeId)
            ListItem(
                headlineContent = { Text("Simulation", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("Fake phones and incidents. Must be off for the final demo.") },
                trailingContent = { Switch(checked = sim, onCheckedChange = { sim = it }) },
                colors = rows,
            )
            if (sim != graph.simulation) {
                LargeButton(if (sim) "Restart with simulation on" else "Restart with simulation off", { restartWithSimulation(context, sim) })
            }
            // MESH's acoustic SOS (P2), off by default.
            val acoustic by MeshDemoControls.acoustic.collectAsStateWithLifecycle()
            ListItem(
                headlineContent = { Text("Sound fallback", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("With no phone in range, urgent alerts go out as chirps and this phone listens for them. Uses the microphone.") },
                trailingContent = { Switch(checked = acoustic, onCheckedChange = { MeshDemoControls.acoustic.value = it }) },
                colors = rows,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            DemoChain(graph)
            val other = if (nav.current == Screen.Command) Screen.Home else Screen.Command
            TextButton(onClick = { nav.reset(other); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
                Text(if (other == Screen.Command) "Open Command Center" else "Open phone view")
            }
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
        }
    }
}

/**
 * Issue #8: force a relay chain on stage when every phone is in one room. Ticked phones are the only ones this
 * phone keeps Nearby links to (MeshDemoControls.allowedNeighbours). Node ids change per launch, so pick from the
 * phones in range instead of typing ids.
 */
@Composable
private fun DemoChain(graph: ProximaGraph) {
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val allowed by MeshDemoControls.allowedNeighbours.collectAsStateWithLifecycle()
    // An unticked phone drops its link and can fall out of mesh.peers: remember every phone seen while the sheet
    // is open, so it can be ticked again.
    val seen = remember { mutableStateMapOf<String, Peer>() }
    LaunchedEffect(peers) { peers.forEach { seen[it.nodeId] = it } }
    val rows = ListItemDefaults.colors(containerColor = Color.Transparent)

    Text("Demo chain", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(MaterialTheme.spacing.xs))
    Text(
        "Keep links only to the phones you tick, so alerts have to hop. For A to B to C: A ticks B, B ticks A and C, C ticks B.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ListItem(
        headlineContent = { Text("Link to every phone", style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(if (allowed == null) "Normal mesh" else "Only the phones ticked below") },
        trailingContent = {
            Switch(
                checked = allowed == null,
                // Turning the chain on starts from the phones linked right now, so nothing drops until you untick one.
                onCheckedChange = { all ->
                    MeshDemoControls.allowedNeighbours.value = if (all) null else peers.filter { it.direct }.map { it.nodeId }.toSet()
                },
            )
        },
        colors = rows,
    )
    val chain = allowed ?: return
    val ids = (seen.keys + chain).sorted()
    if (ids.isEmpty()) {
        Text(
            "No phones in range yet. Open Proxima on the other phones and keep them close.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.md),
        )
        return
    }
    ids.forEach { id ->
        val peer = seen[id]
        val ticked = id in chain
        ListItem(
            modifier = Modifier.toggleable(
                value = ticked,
                role = Role.Checkbox,
                onValueChange = { on -> MeshDemoControls.allowedNeighbours.value = if (on) chain + id else chain - id },
            ),
            headlineContent = { Text(id, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Text(
                    when {
                        peer == null -> "Not in range"
                        peer.direct -> "${peer.tier.label()}, linked"
                        else -> "${peer.tier.label()}, heard through another phone"
                    },
                )
            },
            trailingContent = { Checkbox(checked = ticked, onCheckedChange = null) },
            colors = rows,
        )
    }
}
