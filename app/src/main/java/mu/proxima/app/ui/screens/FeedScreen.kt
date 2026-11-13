package mu.proxima.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.LostFound
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackIcon
import mu.proxima.app.ui.components.Fact
import mu.proxima.app.ui.components.LargeButton
import mu.proxima.app.ui.components.NearestHelp
import mu.proxima.app.ui.components.SeverityBadge
import mu.proxima.app.ui.components.SimLabel
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.components.VerifiedMark
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.label
import mu.proxima.app.ui.sym
import mu.proxima.app.ui.theme.container
import mu.proxima.app.ui.theme.fill
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.ProtocolCard

/** Ticks once a second so relative ages ("45s") stay true without each row running its own timer. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav) {
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val open = incidents.civilianVisible().sortedWith(
        compareByDescending<IncidentRecord> { it.incident.severity }.thenByDescending { it.lastUpdatedMs },
    )
    val now = rememberNow()
    var selected by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts near you") },
                navigationIcon = { BackIcon(nav::back) },
                actions = { if (graph.simulation) SimLabel(Modifier.padding(end = MaterialTheme.spacing.lg)) },
            )
        },
    ) { padding ->
        if (open.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(MaterialTheme.spacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SymbolIcon(Sym.Mesh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = MaterialTheme.sizes.iconLarge)
                Spacer(Modifier.height(MaterialTheme.spacing.md))
                Text("No alerts near you", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(
                    "Alerts from phones around you appear here, even with no mobile signal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "Most urgent first. Tap one for details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm),
                    )
                }
                itemsIndexed(open, key = { _, r -> r.incident.id }) { i, record ->
                    Column(Modifier.animateItem()) {
                        IncidentRow(record, me, now) { selected = record.incident.id }
                        if (i < open.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }

    val current = incidents.firstOrNull { it.incident.id == selected }
    if (current != null) {
        val answerable = current.incident.type == IncidentType.LOST_PERSON && !current.resolved && !lostFound.isMine(current)
        IncidentSheet(
            graph, current, me, onDismiss = { selected = null },
            canResolve = current.incident.type != IncidentType.LOST_PERSON || lostFound.isMine(current),
            onWithThem = if (answerable) ({ if (lostFound.startSharing(current)) { selected = null; nav.go(Screen.Finder) } }) else null,
        )
    }
}

@Composable
fun IncidentRow(record: IncidentRecord, me: GeoPoint?, now: Long, onClick: () -> Unit) {
    val i = record.incident
    val distance = if (me != null && i.location != null) GeoMath.distanceM(me, i.location!!) else null
    val (container, content) = i.severity.container()
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        overlineContent = { Text("${i.severity.label()}, ${i.type.label().lowercase()}") },
        headlineContent = { Text(incidentTitle(i.type, i.subtype), style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column {
                Text(i.summary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    VerifiedMark(record.signatureValid)
                    if (i.reportCount > 1) Text("${i.reportCount} reports", style = MaterialTheme.typography.labelMedium)
                    Text(
                        when (record.hopsTravelled) { 0 -> "From you"; 1 -> "1 hop"; else -> "${record.hopsTravelled} hops" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        leadingContent = { SeverityBadge(i.type.sym(), container, content, description = null) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(ageLabel(record.lastUpdatedMs, now), style = MaterialTheme.typography.labelLarge)
                distance?.let { Text(distanceLabel(it), style = MaterialTheme.typography.labelMedium) }
            }
        },
    )
}

/** Incident detail: what it is, who sorted it, what to do, where help is. Bottom sheet, never a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentSheet(
    graph: ProximaGraph,
    record: IncidentRecord,
    me: GeoPoint?,
    onDismiss: () -> Unit,
    canResolve: Boolean = true,
    onWithThem: (() -> Unit)? = null,
) {
    val i = record.incident
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var protocol by remember { mutableStateOf<ProtocolCard?>(null) }
    LaunchedEffect(i.id) {
        protocol = runCatching { (i.protocolId?.let { graph.protocols.byId(it) }) ?: graph.protocols.bestFor(i.type, i.subtype) }.getOrNull()
    }
    val (fill, onFill) = i.severity.fill()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.xxl)) {
            item {
                Surface(color = fill, contentColor = onFill, shape = MaterialTheme.shapes.small) {
                    Text(
                        i.severity.label(),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
                    )
                }
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(incidentTitle(i.type, i.subtype), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(i.summary, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(MaterialTheme.spacing.lg))
                i.landmark?.let { Fact("Where", it) }
                i.peopleCount?.let { Fact("People", it.toString()) }
                Fact("Sorted by", i.triagedBy.label())
                Fact("Signature", if (record.signatureValid) "Verified" else "Not verified, treat with care")
                Fact("Travelled", when (record.hopsTravelled) { 0 -> "Sent from this phone"; 1 -> "1 hop"; else -> "${record.hopsTravelled} hops" })
                if (i.reportCount > 1) Fact("Reports", "${i.reportCount} people reported this")
            }
            protocol?.let { card ->
                item {
                    Spacer(Modifier.height(MaterialTheme.spacing.lg))
                    Text(card.title, style = MaterialTheme.typography.titleLarge)
                }
                itemsIndexed(card.steps.take(4)) { n, step ->
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
                item {
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    Text("Source: ${card.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Spacer(Modifier.height(MaterialTheme.spacing.lg))
                NearestHelp(graph, me ?: i.location, i.type.helpCategories)
                Spacer(Modifier.height(MaterialTheme.spacing.xxl))
                if (onWithThem != null) {
                    LargeButton("I'm with this person", onWithThem, sym = Sym.Person)
                    Spacer(Modifier.height(MaterialTheme.spacing.sm))
                }
                if (!record.resolved && canResolve) {
                    OutlinedButton(
                        onClick = {
                            graph.scope.launch { graph.pipeline.resolve(i.id) }
                            scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
                        },
                        modifier = Modifier.fillMaxWidth().height(MaterialTheme.sizes.touchTarget),
                    ) { Text("Mark as resolved") }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            }
        }
    }
}
