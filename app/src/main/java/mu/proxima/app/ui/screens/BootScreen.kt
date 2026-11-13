package mu.proxima.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.components.SimLabel
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.label
import mu.proxima.app.ui.missingPermissions
import mu.proxima.app.ui.theme.spacing

/** Why we ask, in the user's terms. One row per group, not per Android permission string. */
private data class PermissionGroup(val sym: Sym, val title: String, val why: String, val perms: List<String>)

private fun group(perms: List<String>): List<PermissionGroup> {
    fun pick(vararg keys: String) = perms.filter { p -> keys.any { p.contains(it) } }
    val nearby = pick("BLUETOOTH", "NEARBY_WIFI", "WIFI_STATE", "CHANGE_WIFI")
    val location = pick("LOCATION")
    val mic = pick(Manifest.permission.RECORD_AUDIO)
    val alerts = pick("POST_NOTIFICATIONS")
    val known = (nearby + location + mic + alerts).toSet()
    return listOfNotNull(
        nearby.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Sym.Mesh, "Nearby phones", "Pass alerts from phone to phone without mobile signal.", it) },
        location.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Sym.Location, "Location", "So help can find you, and you can find your child.", it) },
        mic.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Sym.Mic, "Microphone", "Describe an emergency by voice. Nothing is recorded.", it) },
        alerts.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Sym.Hazard, "Alerts", "Wake you when your child is found.", it) },
        (perms - known).takeIf { it.isNotEmpty() }?.let { PermissionGroup(Sym.Settings, "Other", "Needed by the mesh.", it) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BootScreen(graph: ProximaGraph, onDone: () -> Unit, onDev: () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(missingPermissions(context, graph.requiredPermissions)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions(context, graph.requiredPermissions)
        asked = true
        if (missing.isEmpty()) onDone()
    }
    val groups = group(missing)

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = MaterialTheme.spacing.xl)) {
            Spacer(Modifier.height(MaterialTheme.spacing.x5))
            Text(
                "Proxima",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Text("Help travels from phone to phone, even with no signal.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(MaterialTheme.spacing.md))
            Text(graph.capabilities.tier.label(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (graph.simulation) {
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                SimLabel()
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xxxl))
            Text("To work, this phone needs:", style = MaterialTheme.typography.titleMedium)
            groups.forEach { g ->
                ListItem(
                    headlineContent = { Text(g.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(g.why) },
                    leadingContent = { SymbolIcon(g.sym, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (asked && missing.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "Some access is still off. Proxima will work with fewer features. You can turn it on later in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.spacing.lg))
            }
            LargeButton(if (asked) "Try again" else "Allow access", { launcher.launch(missing.toTypedArray()) })
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
                Text(if (asked) "Continue with fewer features" else "Not now")
            }
        }
    }
}
