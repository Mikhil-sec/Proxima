package mu.proxima.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.components.Fact
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.spacing

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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xxl)) {
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
            )
            if (sim != graph.simulation) {
                LargeButton(if (sim) "Restart with simulation on" else "Restart with simulation off", { restartWithSimulation(context, sim) })
            }
            val other = if (nav.current == Screen.Command) Screen.Home else Screen.Command
            TextButton(onClick = { nav.reset(other); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
                Text(if (other == Screen.Command) "Open Command Center" else "Open phone view")
            }
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
        }
    }
}
