package mu.proxima.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.RankedHelpPoint

/** Beyond this, a walking arrow misleads (Ganga Talao: the nearest clinic is ~8.5 km). Show distance and place. */
private const val WALKABLE_M = 2_000.0

fun HelpCategory.sym(): Sym = when (this) {
    HelpCategory.POLICE -> Sym.Police
    HelpCategory.FIRE_STATION -> Sym.Fire
    HelpCategory.ASSEMBLY_POINT, HelpCategory.MEETING_POINT -> Sym.Location
    else -> Sym.Hospital
}

/**
 * Nearest real help points from the Mauritius dataset (OSM). Clinical categories only, chosen by incident type.
 * Close by: a direction arrow that turns with the phone. Far away: no arrow, a plain distance.
 * Renders nothing while loading or when the dataset has nothing for these categories.
 */
@Composable
fun NearestHelp(graph: ProximaGraph, from: GeoPoint?, categories: Set<HelpCategory>, modifier: Modifier = Modifier) {
    var help by remember { mutableStateOf<List<RankedHelpPoint>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(from, categories) {
        runCatching { if (from == null || categories.isEmpty()) emptyList() else graph.helpPoints.nearest(from, categories, 2) }
            .onSuccess { help = it; failed = false }
            .onFailure { failed = true }
    }
    val heading by graph.locate.headingDeg.collectAsStateWithLifecycle()
    if (failed) {
        Text(
            "Nearby help points couldn't load on this phone. Ask a steward for the nearest first aid post.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(horizontal = MaterialTheme.spacing.lg),
        )
        return
    }
    // Loading: render nothing rather than a spinner; the dataset is local and answers in milliseconds.
    val list = help ?: return
    if (list.isEmpty()) return

    Column(modifier) {
        Text("Nearest help", style = MaterialTheme.typography.titleLarge)
        list.forEach { h ->
            val walkable = h.distanceM <= WALKABLE_M
            ListItem(
                headlineContent = { Text(h.point.name, style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text("${h.point.category.label()}, ${h.point.source}", style = MaterialTheme.typography.bodySmall) },
                leadingContent = {
                    if (walkable && heading != null) {
                        val rel = GeoMath.relativeBearingDeg(h.bearingDeg, heading!!.toDouble()).toFloat()
                        SymbolIcon(Sym.Direction, description = "Direction to ${h.point.name}", modifier = Modifier.rotate(rel), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        SymbolIcon(h.point.category.sym(), description = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = { Text(distanceLabel(h.distanceM), style = MaterialTheme.typography.titleMedium) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (list.first().distanceM > WALKABLE_M) {
            Text(
                "Help is ${distanceLabel(list.first().distanceM)} away. Tell people around you; your alert keeps spreading phone to phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semantic.caution,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg),
            )
        }
    }
}
