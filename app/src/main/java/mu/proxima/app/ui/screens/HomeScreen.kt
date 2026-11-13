package mu.proxima.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.VoiceCapture
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.MeshStatus
import mu.proxima.app.ui.components.Rail
import mu.proxima.app.ui.components.SimBadge
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.label
import mu.proxima.app.ui.subtypeLabel
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.color
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Incidents a civilian should see: open, and not a guardian-only sighting. */
fun List<IncidentRecord>.civilianVisible() =
    filter { !it.resolved && it.incident.type != IncidentType.SIGHTING && it.incident.type != IncidentType.ALL_CLEAR }

@Composable
fun HomeScreen(graph: ProximaGraph, capture: VoiceCapture, nav: Nav, onDev: () -> Unit) {
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val open = incidents.civilianVisible()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TopLine(graph.simulation, peers.size, onDev)
        Spacer(Modifier.height(28.dp))
        Text("Need help?", style = grotesk(34))
        Spacer(Modifier.height(6.dp))
        Text("Hold the red button and say what's happening.", style = grotesk(18, FontWeight.Medium, Px.Dim))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            SosRing(capture, onRelease = { nav.go(Screen.Report(fromHold = true)) })
        }

        ReportRow(onPick = { nav.go(Screen.Report(hint = it)) })
        Spacer(Modifier.height(20.dp))
        AlertsStrip(open, onClick = { nav.go(Screen.Feed) })
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            LinkCell(Glyph.Scan, "I found someone", Px.Text, Modifier.weight(1f)) { nav.go(Screen.Finder) }
            LinkCell(Glyph.Wristband, "My wristbands", Px.Dim, Modifier.weight(1f)) { nav.go(Screen.Wristbands) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Wordmark (long-press = hidden developer sheet), mesh status, SIM badge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopLine(simulation: Boolean, peerCount: Int, onDev: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Proxima",
            style = grotesk(20),
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
        )
        Spacer(Modifier.weight(1f))
        MeshStatus(peerCount)
        if (simulation) {
            Spacer(Modifier.width(12.dp))
            SimBadge()
        }
    }
}

/**
 * The SOS ring. Press and hold: listens while held, the voice draws itself around the ring,
 * release sends you to the report. A quick tap also starts listening (for people who can't hold).
 */
@Composable
private fun SosRing(capture: VoiceCapture, onRelease: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    var held by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (held) 0.94f else 1f, tween(160), label = "press")

    val breathe = if (reducedMotion) 0f else {
        val t = rememberInfiniteTransition(label = "breathe")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "b").value
    }

    val measurer = rememberTextMeasurer()
    val sos = remember(measurer) { measurer.measure("SOS", grotesk(40, FontWeight.Bold, Px.Black).copy(letterSpacing = 2.sp)) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(236.dp)
                .semantics {
                    contentDescription = "Hold to call for help"
                    role = Role.Button
                    onClick { capture.start(); onRelease(); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        held = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        capture.start()
                        val t0 = System.currentTimeMillis()
                        val released = tryAwaitRelease()
                        held = false
                        if (!released) { capture.cancel(); return@detectTapGestures }
                        // A long hold means "I'm done talking"; a quick tap keeps listening on the next screen.
                        if (System.currentTimeMillis() - t0 > 450) capture.stop()
                        onRelease()
                    })
                },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val core = size.minDimension * 0.33f * press

            if (held) {
                // Voice ring: recent levels as radial ticks around the core.
                val levels = capture.levels
                val n = 56
                for (i in 0 until n) {
                    val lv = if (levels.isEmpty()) 0.05f else levels[(levels.size - 1 - i % levels.size).coerceAtLeast(0)]
                    val a = (i.toFloat() / n) * 2f * PI.toFloat() - PI.toFloat() / 2
                    val r0 = core + 10.dp.toPx()
                    val r1 = r0 + 6.dp.toPx() + lv * 34.dp.toPx()
                    drawLine(
                        Px.Crimson.copy(alpha = 0.55f + 0.45f * lv),
                        Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                        Offset(c.x + cos(a) * r1, c.y + sin(a) * r1),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            } else {
                // Breathing halo: two rings drifting outwards and fading.
                for (k in 0..1) {
                    val phase = (breathe + k * 0.5f) % 1f
                    drawCircle(
                        Px.Crimson.copy(alpha = 0.32f * (1f - phase)),
                        radius = core + 8.dp.toPx() + phase * (size.minDimension / 2 - core - 8.dp.toPx()),
                        center = c,
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            drawCircle(Px.Crimson, core, c)
            drawText(sos, topLeft = Offset(c.x - sos.size.width / 2f, c.y - sos.size.height / 2f))
        }
        // Label sits under the ring rather than on it: the ring stays a clean target.
        Text(
            if (held) "Listening. Let go when you're done." else "Hold to talk",
            style = grotesk(16, FontWeight.Medium, if (held) Px.Text else Px.Dim),
        )
    }
}

/** Secondary destinations: glyph + one-line label, quieter than the report row, full-width halves. */
@Composable
private fun LinkCell(glyph: Glyph, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, color, 20.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = grotesk(16, FontWeight.Medium, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One segmented control, three report types. Grouped by a single outline, not three cards. */
@Composable
private fun ReportRow(onPick: (IncidentType) -> Unit) {
    val items = listOf(
        Triple(IncidentType.MEDICAL, Glyph.Medical, "Medical"),
        Triple(IncidentType.HAZARD, Glyph.Hazard, "Hazard"),
        Triple(IncidentType.LOST_PERSON, Glyph.Person, "Lost person"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Px.Line, RoundedCornerShape(20.dp)),
    ) {
        items.forEachIndexed { i, (type, glyph, label) ->
            if (i > 0) Box(Modifier.padding(vertical = 18.dp).width(1.dp).fillMaxHeight().background(Px.Line))
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClickLabel = "Report $label") { onPick(type) }
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlyphIcon(glyph, Px.Text, 28.dp)
                Spacer(Modifier.height(10.dp))
                Text(label, style = grotesk(16, FontWeight.Medium))
            }
        }
    }
}

/** The latest alerts around you, one line. Empty state still points somewhere. */
@Composable
private fun AlertsStrip(open: List<IncidentRecord>, onClick: () -> Unit) {
    val top = open.maxWithOrNull(compareBy<IncidentRecord> { it.incident.severity }.thenBy { it.incident.createdAtMs })
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rail(top?.incident?.severity?.color() ?: Px.Line, Modifier.fillMaxHeight())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (open.size) {
                    0 -> "No alerts near you"
                    1 -> "1 alert near you"
                    else -> "${open.size} alerts near you"
                },
                style = grotesk(17, FontWeight.Bold, if (open.isEmpty()) Px.Dim else Px.Text),
            )
            if (top != null) {
                Text(
                    subtypeLabel(top.incident.subtype) ?: top.incident.type.label(),
                    style = grotesk(15, FontWeight.Medium, Px.Dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (top != null) Text(ageLabel(top.lastUpdatedMs), style = mono(13))
    }
}
