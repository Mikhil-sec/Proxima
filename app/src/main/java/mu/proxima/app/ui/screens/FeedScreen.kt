package mu.proxima.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackButton
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.components.Rail
import mu.proxima.app.ui.components.SimBadge
import mu.proxima.app.ui.components.VerifiedMark
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.label
import mu.proxima.app.ui.subtypeLabel
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.color
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.ProtocolCard
import mu.proxima.core.model.RankedHelpPoint

/** Ticks once a second so relative ages ("45s") stay true without each row running its own timer. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(periodMs); now = System.currentTimeMillis() } }
    return now
}

@Composable
fun FeedScreen(graph: ProximaGraph, nav: Nav) {
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val open = incidents.civilianVisible().sortedWith(
        compareByDescending<IncidentRecord> { it.incident.severity }.thenByDescending { it.lastUpdatedMs },
    )
    val now = rememberNow()
    var selected by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(nav::back)
            Spacer(Modifier.weight(1f))
            if (graph.simulation) SimBadge(Modifier.padding(end = 12.dp))
        }
        Text("Alerts near you", style = grotesk(30), modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            if (open.isEmpty()) "Nothing reported around you right now." else "Most urgent first. Tap one for details.",
            style = grotesk(16, FontWeight.Medium, Px.Dim),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        if (open.isEmpty()) {
            EmptyFeed()
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
                items(open, key = { it.incident.id }) { record ->
                    IncidentRow(record, me, now, Modifier.animateItem()) { selected = record.incident.id }
                }
            }
        }
    }

    val current = incidents.firstOrNull { it.incident.id == selected }
    if (current != null) IncidentSheet(graph, current, me, onDismiss = { selected = null })
}

@Composable
private fun EmptyFeed() {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
        GlyphIcon(Glyph.Mesh, Px.Dim, 40.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Alerts from phones around you appear here, even with no mobile signal.",
            style = grotesk(18, FontWeight.Medium, Px.Dim),
        )
    }
}

@Composable
fun IncidentRow(record: IncidentRecord, me: GeoPoint?, now: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val i = record.incident
    val distance = if (me != null && i.location != null) GeoMath.distanceM(me, i.location!!) else null
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .alpha(if (record.resolved) 0.45f else 1f),
    ) {
        Rail(i.severity.color(), Modifier.fillMaxHeight())
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    subtypeLabel(i.subtype) ?: i.type.label(),
                    style = grotesk(20),
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(ageLabel(record.lastUpdatedMs, now), style = mono(13, color = Px.Text))
                    distance?.let { Text(distanceLabel(it), style = mono(13)) }
                }
            }
            Text(
                "${i.type.label()}, ${i.severity.label().lowercase()}",
                style = grotesk(14, FontWeight.Medium, i.severity.color()),
            )
            Spacer(Modifier.height(6.dp))
            Text(i.summary, style = grotesk(16, FontWeight.Medium, Px.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                VerifiedMark(record.signatureValid)
                if (i.reportCount > 1) Text("${i.reportCount} reports", style = grotesk(13, FontWeight.Medium, Px.Dim))
                Text(
                    when (record.hopsTravelled) { 0 -> "From you"; 1 -> "1 hop"; else -> "${record.hopsTravelled} hops" },
                    style = grotesk(13, FontWeight.Medium, Px.Dim),
                )
            }
        }
    }
}

/** Incident detail: what it is, who sorted it, what to do, where help is. Bottom sheet, never a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentSheet(graph: ProximaGraph, record: IncidentRecord, me: GeoPoint?, onDismiss: () -> Unit) {
    val i = record.incident
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var protocol by remember { mutableStateOf<ProtocolCard?>(null) }
    var help by remember { mutableStateOf<List<RankedHelpPoint>>(emptyList()) }
    LaunchedEffect(i.id) {
        protocol = (i.protocolId?.let { graph.protocols.byId(it) }) ?: graph.protocols.bestFor(i.type, i.subtype)
        val from = me ?: i.location
        if (from != null) help = graph.helpPoints.nearest(from, i.type.helpCategories, 2)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Px.Surface,
        contentColor = Px.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Px.Line))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(i.type.label(), style = grotesk(15, FontWeight.Medium, i.severity.color()))
            Text(subtypeLabel(i.subtype) ?: i.type.label(), style = grotesk(28))
            Spacer(Modifier.height(8.dp))
            Text(i.summary, style = grotesk(17, FontWeight.Medium))
            Spacer(Modifier.height(20.dp))
            Fact("Severity", i.severity.label())
            i.landmark?.let { Fact("Where", it) }
            i.peopleCount?.let { Fact("People", it.toString()) }
            Fact("Sorted by", i.triagedBy.label())
            Fact("Signature", if (record.signatureValid) "Verified" else "Not verified, treat with care")
            Fact("Travelled", if (record.hopsTravelled == 0) "Sent from this phone" else "${record.hopsTravelled} hops")
            if (i.reportCount > 1) Fact("Reports", "${i.reportCount} people reported this")

            protocol?.let { card ->
                Spacer(Modifier.height(20.dp))
                Text(card.title, style = grotesk(20))
                Spacer(Modifier.height(8.dp))
                card.steps.take(4).forEachIndexed { n, step ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text("${n + 1}", style = mono(16, FontWeight.Bold, Px.Text), modifier = Modifier.width(28.dp))
                        Text(step.text, style = grotesk(17, FontWeight.Medium))
                    }
                }
                Text("Source: ${card.source}", style = grotesk(13, FontWeight.Medium, Px.Dim))
            }

            if (help.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Nearest help", style = grotesk(20))
                help.forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.point.name, style = grotesk(17, FontWeight.Medium))
                            Text(h.point.source, style = grotesk(12, FontWeight.Medium, Px.Dim))
                        }
                        Text(distanceLabel(h.distanceM), style = mono(15, FontWeight.Bold, Px.Text))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            if (!record.resolved) {
                PrimaryAction("Mark as resolved", onClick = {
                    graph.scope.launch { graph.pipeline.resolve(i.id) }
                    scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
                })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
