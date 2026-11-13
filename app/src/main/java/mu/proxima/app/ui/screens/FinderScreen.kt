package mu.proxima.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.LostFound
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackIcon
import mu.proxima.app.ui.components.Fact
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.components.SeverityBadge
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SimLabel
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.components.sym
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.RankedHelpPoint

/**
 * Finder: "I'm with someone who is lost." No hardware: pick the matching lost-person alert;
 * your location then goes, sealed, to that guardian's phone only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinderScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav) {
    val sharing = lostFound.sharingFor
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sharing != null) "" else "Found someone?") },
                navigationIcon = { BackIcon(nav::back) },
                actions = { if (graph.simulation) SimLabel(Modifier.padding(end = MaterialTheme.spacing.lg)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sharing != null) {
                SharingView(graph, lostFound, sharing, onDone = { lostFound.stopSharing(); nav.reset(Screen.Home) })
            } else {
                PickView(graph, lostFound)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.PickView(graph: ProximaGraph, lostFound: LostFound) {
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    val lost = lostFound.othersOpenLost(incidents).sortedByDescending { it.lastUpdatedMs }
    var confirm by remember { mutableStateOf<IncidentRecord?>(null) }
    var failed by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = MaterialTheme.spacing.lg)) {
        Text(
            "If they match an alert below, tap it. Their guardian is told where you are, and nobody else.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failed) {
            Spacer(Modifier.height(MaterialTheme.spacing.md))
            Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    "That alert reached this phone without a signature, so we can't reach its guardian. Stay with them and go to the nearest police post.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(MaterialTheme.spacing.md),
                )
            }
        }
    }
    Spacer(Modifier.height(MaterialTheme.spacing.sm))
    if (lost.isEmpty()) {
        NoLostAlerts(graph, me)
    } else {
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(lost, key = { _, r -> r.incident.id }) { i, r ->
                LostRow(r, me, now) { confirm = r }
                if (i < lost.lastIndex) HorizontalDivider()
            }
        }
    }

    val pick = confirm
    if (pick != null) {
        ModalBottomSheet(onDismissRequest = { confirm = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xxl)) {
                Text("Are you with them now?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text("“${pick.incident.summary}”", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(MaterialTheme.spacing.md))
                Text(
                    "Your location will go to their guardian's phone every few seconds until you stop. Nobody else can read it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xxl))
                LargeButton("Yes, I'm with them", {
                    failed = !lostFound.startSharing(pick)
                    confirm = null
                })
                TextButton(onClick = { confirm = null }, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
                    Text("Not them")
                }
                Spacer(Modifier.height(MaterialTheme.spacing.lg))
            }
        }
    }
}

@Composable
private fun LostRow(r: IncidentRecord, me: GeoPoint?, now: Long, onClick: () -> Unit) {
    val i = r.incident
    val distance = if (me != null && i.location != null) GeoMath.distanceM(me, i.location!!) else null
    val s = MaterialTheme.semantic
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "I'm with this person", onClick = onClick),
        headlineContent = { Text(incidentTitle(i.type, i.subtype), style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column {
                Text(i.summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                i.landmark?.let { Text("Near $it") }
            }
        },
        leadingContent = { SeverityBadge(Sym.LostPerson, s.cautionContainer, s.onCautionContainer, null) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(ageLabel(r.lastUpdatedMs, now), style = MaterialTheme.typography.labelLarge)
                distance?.let { Text(distanceLabel(it), style = MaterialTheme.typography.labelMedium) }
            }
        },
    )
}

/** Empty state still gives direction: stay visible, and where the nearest police are. */
@Composable
private fun ColumnScope.NoLostAlerts(graph: ProximaGraph, me: GeoPoint?) {
    var police by remember { mutableStateOf<RankedHelpPoint?>(null) }
    LaunchedEffect(me) { police = me?.let { runCatching { graph.helpPoints.nearest(it, setOf(HelpCategory.POLICE), 1).firstOrNull() }.getOrNull() } }
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(MaterialTheme.spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SymbolIcon(Sym.LostPerson, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = MaterialTheme.sizes.iconLarge)
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        Text("Nobody has been reported lost near you yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(
            "Stay with them somewhere safe and visible. If their guardian reports them, the alert appears here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        police?.let { p ->
            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            ListItem(
                overlineContent = { Text("Nearest police") },
                headlineContent = { Text(p.point.name) },
                supportingContent = { Text(p.point.source) },
                leadingContent = { SymbolIcon(p.point.category.sym(), null) },
                trailingContent = { Text(distanceLabel(p.distanceM), style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}

/** Finder after confirming: one message, one action. No looping motion; the timer shows it is still running. */
@Composable
private fun ColumnScope.SharingView(graph: ProximaGraph, lostFound: LostFound, lost: IncidentRecord, onDone: () -> Unit) {
    val now = rememberNow()
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val secs = ((now - lostFound.sharingSinceMs) / 1000).coerceAtLeast(0)

    Column(Modifier.weight(1f).padding(horizontal = MaterialTheme.spacing.xl)) {
        SymbolIcon(Sym.Location, description = null, tint = MaterialTheme.semantic.safe, size = MaterialTheme.sizes.iconLarge)
        Spacer(Modifier.height(MaterialTheme.spacing.lg))
        Text("Sharing your location with their guardian", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(
            "Stay where you are. Your location goes only to their guardian's phone, every few seconds.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.xxl))
        Fact("Looking for", lost.incident.summary)
        Fact("Sharing for", "%d:%02d".format(secs / 60, secs % 60))
        Fact("Phones around", peers.size.toString())
    }
    Column(Modifier.padding(horizontal = MaterialTheme.spacing.xl)) {
        LargeButton("Their guardian is here", onDone, sym = Sym.Done)
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) { Text("Stop sharing") }
    }
}
