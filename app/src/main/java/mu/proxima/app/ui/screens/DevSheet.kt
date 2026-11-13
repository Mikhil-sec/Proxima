package mu.proxima.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.components.FieldSwitch
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.components.QuietAction
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk

/** Simulation is read once when the graph is built, so switching it means a clean restart. */
private fun restartWithSimulation(context: Context, on: Boolean) {
    context.getSharedPreferences("proxima", Context.MODE_PRIVATE).edit(commit = true) { putBoolean("simulation", on) }
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    (context as? Activity)?.finishAffinity()
    Runtime.getRuntime().exit(0)
}

/** Hidden developer sheet (long-press the wordmark). Not for festival-goers; for the team and the demo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevSheet(graph: ProximaGraph, nav: Nav, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val caps = graph.capabilities
    val triage by graph.triage.capability.collectAsState()
    var sim by remember { mutableStateOf(graph.simulation) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Px.Surface,
        contentColor = Px.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Px.Line))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("Developer", style = grotesk(24))
            Spacer(Modifier.height(16.dp))
            Fact("Device", caps.model)
            Fact("Role", caps.tier.label())
            Fact("Memory", "%.1f GB".format(caps.totalRamGb), valueMono = true)
            Fact("UWB", if (caps.hasUwb) "yes" else "no")
            Fact("Compass", if (caps.hasCompass) "yes" else "no")
            Fact("Triage", triage.name.lowercase().replace('_', ' '))
            Fact("Node", graph.mesh.selfNodeId, valueMono = true)

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Simulation", style = grotesk(18, FontWeight.Bold))
                    Text("Fake phones and incidents. Must be off for the final demo.", style = grotesk(14, FontWeight.Medium, Px.Dim))
                }
                FieldSwitch(sim, { sim = it })
            }
            Spacer(Modifier.height(20.dp))
            if (sim != graph.simulation) {
                PrimaryAction(if (sim) "Restart with simulation on" else "Restart with simulation off", { restartWithSimulation(context, sim) }, color = Px.Amber)
            }
            val other = if (nav.current == Screen.Command) Screen.Home else Screen.Command
            QuietAction(
                if (other == Screen.Command) "Open Command Center" else "Open phone view",
                onClick = { nav.reset(other); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
