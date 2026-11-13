package mu.proxima.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.LostFound
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackButton
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.components.QuietAction
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.RankedHelpPoint

/**
 * Finder: "I'm with someone who is lost." No hardware: pick the matching lost-person alert;
 * your location then goes, sealed, to that guardian's phone only.
 */
@Composable
fun FinderScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav) {
    val sharing = lostFound.sharingFor
    if (sharing != null) {
        SharingView(graph, lostFound, sharing, onDone = { lostFound.stopSharing(); nav.reset(Screen.Home) })
    } else {
        PickView(graph, lostFound, nav)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickView(graph: ProximaGraph, lostFound: LostFound, nav: Nav) {
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    val lost = lostFound.othersOpenLost(incidents).sortedByDescending { it.lastUpdatedMs }
    var confirm by remember { mutableStateOf<IncidentRecord?>(null) }
    var failed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(nav::back)
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Found someone?", style = grotesk(30))
            Spacer(Modifier.height(6.dp))
            Text(
                "If they match an alert below, tap it. Their guardian is told where you are, and nobody else.",
                style = grotesk(17, FontWeight.Medium, Px.Dim),
            )
            if (failed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "That alert reached this phone without a signature, so we can't reach its guardian. Stay with them and go to the nearest police post.",
                    style = grotesk(15, FontWeight.Medium, Px.Amber),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (lost.isEmpty()) {
            NoLostAlerts(graph, me)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(lost, key = { it.incident.id }) { r -> LostRow(r, me, now) { confirm = r } }
            }
        }
    }

    val pick = confirm
    if (pick != null) {
        ModalBottomSheet(
            onDismissRequest = { confirm = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Px.Surface,
            contentColor = Px.Text,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { SheetHandle() },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
                Text("Are you with them now?", style = grotesk(26))
                Spacer(Modifier.height(8.dp))
                Text("“${pick.incident.summary}”", style = grotesk(18, FontWeight.Medium))
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your location will go to their guardian's phone every few seconds until you stop. Nobody else can read it.",
                    style = grotesk(15, FontWeight.Medium, Px.Dim),
                )
                Spacer(Modifier.height(24.dp))
                PrimaryAction("Yes, I'm with them", {
                    failed = !lostFound.startSharing(pick)
                    confirm = null
                })
                QuietAction("Not them", { confirm = null }, Modifier.fillMaxWidth(), color = Px.Dim)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun SheetHandle() {
    Box(Modifier.padding(vertical = 12.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Px.Line))
}

@Composable
private fun LostRow(r: IncidentRecord, me: GeoPoint?, now: Long, onClick: () -> Unit) {
    val i = r.incident
    val distance = if (me != null && i.location != null) GeoMath.distanceM(me, i.location!!) else null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "I'm with this person", onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        GlyphIcon(Glyph.Person, Px.Amber, 28.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(incidentTitle(i.type, i.subtype), style = grotesk(20))
            Spacer(Modifier.height(4.dp))
            Text(i.summary, style = grotesk(17, FontWeight.Medium))
            i.landmark?.let { Text("Last seen $it", style = grotesk(14, FontWeight.Medium, Px.Dim)) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(ageLabel(r.lastUpdatedMs, now), style = mono(13, color = Px.Text))
            distance?.let { Text(distanceLabel(it), style = mono(13)) }
        }
    }
}

/** Empty state still gives direction: stay visible, and where the nearest police are. */
@Composable
private fun ColumnScope.NoLostAlerts(graph: ProximaGraph, me: GeoPoint?) {
    var police by remember { mutableStateOf<RankedHelpPoint?>(null) }
    LaunchedEffect(me) { police = me?.let { graph.helpPoints.nearest(it, setOf(HelpCategory.POLICE), 1).firstOrNull() } }
    Column(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 24.dp)) {
        GlyphIcon(Glyph.Mesh, Px.Dim, 40.dp)
        Spacer(Modifier.height(16.dp))
        Text("Nobody has been reported lost near you yet.", style = grotesk(20))
        Spacer(Modifier.height(8.dp))
        Text(
            "Stay with them somewhere safe and visible. If their guardian reports them, the alert appears here.",
            style = grotesk(17, FontWeight.Medium, Px.Dim),
        )
        police?.let { p ->
            Spacer(Modifier.height(24.dp))
            Text("Nearest police", style = grotesk(15, FontWeight.Medium, Px.Dim))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.point.name, style = grotesk(18, FontWeight.Bold), modifier = Modifier.weight(1f))
                Text(distanceLabel(p.distanceM), style = mono(16, FontWeight.Bold, Px.Text))
            }
            Text(p.point.source, style = grotesk(12, FontWeight.Medium, Px.Dim))
        }
    }
}

/** Finder after confirming: one message, one action. A slow green pulse says "still sending". */
@Composable
private fun SharingView(graph: ProximaGraph, lostFound: LostFound, lost: IncidentRecord, onDone: () -> Unit) {
    val now = rememberNow()
    val reducedMotion = rememberReducedMotion()
    val pulse = if (reducedMotion) 0f else {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "p").value
    }
    val secs = ((now - lostFound.sharingSinceMs) / 1000).coerceAtLeast(0)

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(48.dp))
        Canvas(Modifier.size(72.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Px.Green.copy(alpha = 0.4f * (1f - pulse)), radius = size.minDimension / 2 * (0.4f + 0.6f * pulse), center = c, style = Stroke(2.dp.toPx()))
            drawCircle(Px.Green, radius = size.minDimension * 0.16f, center = c)
        }
        Spacer(Modifier.height(20.dp))
        Text("Their guardian has been told", style = grotesk(32))
        Spacer(Modifier.height(10.dp))
        Text(
            "Stay where you are. Your location goes only to their guardian's phone, every few seconds.",
            style = grotesk(18, FontWeight.Medium, Px.Dim),
        )
        Spacer(Modifier.height(28.dp))
        Fact("Looking for", lost.incident.summary)
        Fact("Sharing for", "%d:%02d".format(secs / 60, secs % 60), valueMono = true)
        Fact("Phones around", graph.mesh.peers.collectAsStateWithLifecycle().value.size.toString(), valueMono = true)
        Spacer(Modifier.weight(1f))
        PrimaryAction("Their guardian is here", onDone, glyph = Glyph.Check)
        QuietAction("Stop sharing", onDone, Modifier.fillMaxWidth(), color = Px.Dim)
        Spacer(Modifier.height(8.dp))
    }
}
