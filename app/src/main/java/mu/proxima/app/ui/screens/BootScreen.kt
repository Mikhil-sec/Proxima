package mu.proxima.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.components.Badge
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.components.QuietAction
import mu.proxima.app.ui.components.SimBadge
import mu.proxima.app.ui.label
import mu.proxima.app.ui.missingPermissions
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk

/** Why we ask, in the user's terms. One row per group, not per Android permission string. */
private data class PermissionGroup(val glyph: Glyph, val title: String, val why: String, val perms: List<String>)

private fun group(perms: List<String>): List<PermissionGroup> {
    fun pick(vararg keys: String) = perms.filter { p -> keys.any { p.contains(it) } }
    val nearby = pick("BLUETOOTH", "NEARBY_WIFI", "WIFI_STATE", "CHANGE_WIFI")
    val location = pick("LOCATION")
    val mic = pick(Manifest.permission.RECORD_AUDIO)
    val camera = pick(Manifest.permission.CAMERA)
    val alerts = pick("POST_NOTIFICATIONS")
    val known = (nearby + location + mic + camera + alerts).toSet()
    return listOfNotNull(
        nearby.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Glyph.Mesh, "Nearby phones", "Pass alerts from phone to phone without mobile signal.", it) },
        location.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Glyph.Scan, "Location", "So help can find you, and you can find your child.", it) },
        mic.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Glyph.Mic, "Microphone", "Describe an emergency by voice. Nothing is recorded.", it) },
        camera.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Glyph.Wristband, "Camera", "Scan a child's wristband code.", it) },
        alerts.takeIf { it.isNotEmpty() }?.let { PermissionGroup(Glyph.Alert, "Alerts", "Wake you when your child is found.", it) },
        (perms - known).takeIf { it.isNotEmpty() }?.let { PermissionGroup(Glyph.Check, "Other", "Needed by the mesh.", it) },
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
    val caps = graph.capabilities

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(
            "Proxima",
            style = grotesk(44),
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
        )
        Spacer(Modifier.height(8.dp))
        Text("Help travels from phone to phone, even with no signal.", style = grotesk(19, FontWeight.Medium, Px.Dim))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(caps.tier.label(), style = grotesk(14, FontWeight.Medium))
            if (caps.hasUwb) Badge("UWB", Px.Green)
            if (graph.simulation) SimBadge()
        }

        Spacer(Modifier.height(40.dp))
        Text("To work, this phone needs:", style = grotesk(18, FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        groups.forEach { g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                GlyphIcon(g.glyph, if (asked) Px.Amber else Px.Text, 24.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(g.title, style = grotesk(18, FontWeight.Bold))
                    Text(g.why, style = grotesk(15, FontWeight.Medium, Px.Dim))
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (asked && missing.isNotEmpty()) {
            Text(
                "Some access is still off. Proxima will work with fewer features. You can turn it on later in Settings.",
                style = grotesk(15, FontWeight.Medium, Px.Amber),
            )
            Spacer(Modifier.height(16.dp))
        }
        PrimaryAction(if (asked) "Try again" else "Allow access", { launcher.launch(missing.toTypedArray()) })
        QuietAction(if (asked) "Continue with fewer features" else "Not now", onDone, Modifier.fillMaxWidth(), color = Px.Dim)
        Spacer(Modifier.height(8.dp))
    }
}
