package mu.proxima.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.LostFound
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackButton
import mu.proxima.app.ui.components.Badge
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.components.QuietAction
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
import mu.proxima.core.model.FixSource
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.Proximity
import mu.proxima.core.model.RadarFix
import kotlin.math.cos
import kotlin.math.sin

private fun reunite(graph: ProximaGraph, nav: Nav, lostId: String) {
    graph.scope.launch { graph.pipeline.resolve(lostId, "Reunited") }
    nav.reset(Screen.Home)
}

/** Guardian: the moment that matters most. One message, one action, good news in green. */
@Composable
fun FoundAlertScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav, lostId: String) {
    val notice = lostFound.notices[lostId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    val distance = me?.let { m -> notice.location?.let { GeoMath.distanceM(m, it) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(56.dp))
        GlyphIcon(Glyph.Check, Px.Green, 64.dp, description = "Found")
        Spacer(Modifier.height(24.dp))
        Text("Someone is with your child", style = grotesk(38))
        Spacer(Modifier.height(12.dp))
        notice.landmark?.let { Text(it, style = grotesk(24, FontWeight.Medium)) }
        Spacer(Modifier.height(28.dp))
        distance?.let { Fact("Distance", distanceLabel(it), valueMono = true) }
        Fact("Last update", ageLabel(notice.atMs, now), valueMono = true)
        Spacer(Modifier.height(16.dp))
        Text(
            "Only your phone can read this. Their location is encrypted to you.",
            style = grotesk(15, FontWeight.Medium, Px.Dim),
        )
        Spacer(Modifier.weight(1f))
        PrimaryAction("Show me the way", { nav.go(Screen.Radar(lostId)) }, color = Px.Green)
        QuietAction("We're together now", { reunite(graph, nav, lostId) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

private fun proximityLabel(p: Proximity) = when (p) {
    Proximity.FAR -> "Keep going"
    Proximity.NEAR -> "Getting close"
    Proximity.CLOSE -> "Very close. Look around."
    Proximity.HERE -> "You're there"
}

/** Smallest "nice" radar range that fits the target with a margin. */
private fun rangeFor(d: Double): Double =
    listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0).firstOrNull { it >= d * 1.15 } ?: (d * 1.2)

/**
 * Radar to the person who is with your child. The terminal register: sweep, rings, a blip, big mono distance.
 * Up = where the phone points (relative bearing); falls back to north-up, then to a distance ring (signal only).
 */
@Composable
fun RadarScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav, lostId: String) {
    val notice = lostFound.notices[lostId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val fixes = remember(lostId) { graph.locate.track(graph.pipeline.trackFinder(notice, "Your child")) }
    val fix by fixes.collectAsStateWithLifecycle(initialValue = null)

    // Proximity haptics: ticks get faster as you close in.
    val proximity = fix?.proximity
    LaunchedEffect(proximity) {
        val period = when (proximity) { Proximity.FAR -> 2_400L; Proximity.NEAR -> 1_200L; Proximity.CLOSE -> 500L; Proximity.HERE -> 200L; null -> return@LaunchedEffect }
        while (true) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); delay(period) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(nav::back)
            Text("Finding your child", style = grotesk(18), modifier = Modifier.weight(1f).padding(start = 8.dp))
            fix?.let { Badge(it.source.label().uppercase(), if (it.source == FixSource.SIMULATED) Px.Amber else Px.Green, Modifier.padding(end = 12.dp)) }
        }
        RadarScope(fix, reducedMotion, Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp))

        Column(Modifier.padding(horizontal = 20.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val parts = fix?.distanceM?.let { distanceLabel(it).split(' ') }
                Text(parts?.first() ?: "--", style = mono(64, FontWeight.Bold, Px.Text))
                parts?.getOrNull(1)?.let { Text(it, style = mono(24, FontWeight.Bold, Px.Text), modifier = Modifier.padding(bottom = 10.dp)) }
                fix?.accuracyM?.let { Text("±${it.toInt()} m", style = mono(14), modifier = Modifier.padding(bottom = 12.dp)) }
            }
            Text(
                fix?.let { proximityLabel(it.proximity) } ?: "Getting a fix",
                style = grotesk(24, FontWeight.Bold, if (fix?.proximity == Proximity.HERE) Px.Green else Px.Text),
            )
            notice.landmark?.let { Text(it, style = grotesk(17, FontWeight.Medium, Px.Dim)) }
            if (fix != null && fix?.relativeBearingDeg == null && fix?.bearingDeg != null) {
                Text("No compass: the map is north-up.", style = grotesk(14, FontWeight.Medium, Px.Amber))
            }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            PrimaryAction("We're together", { reunite(graph, nav, lostId) }, color = Px.Green, glyph = Glyph.Check)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RadarScope(fix: RadarFix?, reducedMotion: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val sweep = if (reducedMotion) -1f else {
        val t = rememberInfiniteTransition(label = "sweep")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "s").value
    }
    val heading = fix?.relativeBearingDeg != null
    val angle = fix?.relativeBearingDeg ?: fix?.bearingDeg
    val d = fix?.distanceM
    val range = d?.let(::rangeFor) ?: 100.0

    Canvas(
        modifier.semantics {
            contentDescription = if (d != null) "Target ${distanceLabel(d)} away" else "Looking for a fix"
        },
    ) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        // Rings + labels (the only text on the scope).
        for (k in 1..4) drawCircle(Px.Line, r * k / 4f, c, style = Stroke(1.dp.toPx()))
        val diag = 0.7071f
        for (k in listOf(2, 4)) {
            val rr = r * k / 4f
            drawText(measurer, distanceLabel(range * k / 4), c + Offset(rr * diag + 4.dp.toPx(), rr * diag - 14.dp.toPx()), mono(10))
        }
        drawLine(Px.Line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(Px.Line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)

        // Sweep: a fading wedge of lines behind the beam.
        if (sweep >= 0f) {
            for (k in 0 until 24) {
                val a = Math.toRadians((sweep - k * 1.5).toDouble())
                drawLine(
                    Px.Green.copy(alpha = 0.28f * (1f - k / 24f)), c,
                    Offset(c.x + sin(a).toFloat() * r, c.y - cos(a).toFloat() * r), strokeWidth = 2.dp.toPx(),
                )
            }
        }

        // Up marker: a triangle for "ahead" when we have a heading, otherwise an N.
        if (heading) {
            val tip = Offset(c.x, c.y - r - 2.dp.toPx())
            val p = Path().apply { moveTo(tip.x, tip.y); lineTo(tip.x - 7.dp.toPx(), tip.y + 12.dp.toPx()); lineTo(tip.x + 7.dp.toPx(), tip.y + 12.dp.toPx()); close() }
            drawPath(p, Px.Text)
        } else {
            drawText(measurer, "N", Offset(c.x - 4.dp.toPx(), c.y - r + 2.dp.toPx()), mono(12, FontWeight.Bold, Px.Text))
        }

        if (d != null && angle != null) {
            val rr = (d / range).toFloat().coerceIn(0f, 1f) * r
            val a = Math.toRadians(angle)
            val at = Offset(c.x + sin(a).toFloat() * rr, c.y - cos(a).toFloat() * rr)
            drawLine(Px.Green.copy(alpha = 0.7f), c, at, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Px.Green.copy(alpha = 0.25f), 16.dp.toPx(), at)
            drawCircle(Px.Green, 7.dp.toPx(), at)
        } else if (d != null) {
            // Signal strength only: we know roughly how far, not which way.
            drawCircle(Px.Green.copy(alpha = 0.6f), (d / range).toFloat().coerceIn(0f, 1f) * r, c, style = Stroke(3.dp.toPx()))
        }
        drawCircle(Px.Text, 4.dp.toPx(), c)
    }
}
