package mu.proxima.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.rememberTextMeasurer
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
import mu.proxima.app.ui.components.StateLabel
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.components.safeButtonColors
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
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

/** Guardian: the moment that matters most. One message, one action, good news in the safe role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoundAlertScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav, lostId: String) {
    val notice = lostFound.notices[lostId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    val distance = me?.let { m -> notice.location?.let { GeoMath.distanceM(m, it) } }

    Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { BackIcon(nav::back) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = MaterialTheme.spacing.xl)) {
            SymbolIcon(Sym.Done, description = null, tint = MaterialTheme.semantic.safe, size = MaterialTheme.sizes.iconLarge)
            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            Text("Someone is with your child", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(MaterialTheme.spacing.md))
            notice.landmark?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            distance?.let { Fact("Distance", distanceLabel(it)) }
            Fact("Last update", ageLabel(notice.atMs, now))
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            Text(
                "Only your phone can read this. Their location is encrypted to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            LargeButton("Show me the way", { nav.go(Screen.Radar(lostId)) }, sym = Sym.Way)
            TextButton(onClick = { reunite(graph, nav, lostId) }, modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.sm)) {
                Text("We're together now")
            }
        }
    }
}

private fun proximityLabel(p: Proximity) = when (p) {
    Proximity.FAR -> "Keep going"
    Proximity.NEAR -> "Getting close"
    Proximity.CLOSE -> "Very close. Look around."
    Proximity.HERE -> "You're there"
}

/** Smallest round radar range that fits the target with a margin. */
private fun rangeFor(d: Double): Double =
    listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0).firstOrNull { it >= d * 1.15 } ?: (d * 1.2)

/**
 * Radar to the person who is with your child. Up = where the phone points (relative bearing); falls back to
 * north-up, then to a distance ring when only signal strength is known. Static drawing; it moves only when a
 * new fix arrives. Haptic ticks speed up as you close in (functional feedback, not decoration).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(graph: ProximaGraph, lostFound: LostFound, nav: Nav, lostId: String) {
    val notice = lostFound.notices[lostId] ?: run { LaunchedEffect(Unit) { nav.back() }; return }
    val haptics = LocalHapticFeedback.current
    val fixes = remember(lostId) { graph.locate.track(graph.pipeline.trackFinder(notice, "Your child")) }
    val fix by fixes.collectAsStateWithLifecycle(initialValue = null)

    val proximity = fix?.proximity
    LaunchedEffect(proximity) {
        val period = when (proximity) {
            Proximity.FAR -> 2_400L; Proximity.NEAR -> 1_200L; Proximity.CLOSE -> 500L; Proximity.HERE -> 200L
            null -> return@LaunchedEffect
        }
        while (true) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); delay(period) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finding your child") },
                navigationIcon = { BackIcon(nav::back) },
                actions = {
                    fix?.let {
                        if (it.source == FixSource.SIMULATED) StateLabel("Simulated")
                        else StateLabel(it.source.label(), container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.padding(end = MaterialTheme.spacing.lg))
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = MaterialTheme.spacing.xl)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RadarScope(fix, Modifier.widthIn(max = MaterialTheme.sizes.radar).fillMaxWidth().aspectRatio(1f))
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xxl))
            val parts = fix?.distanceM?.let { distanceLabel(it).split(' ') }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Text(parts?.first() ?: "--", style = MaterialTheme.typography.displayLarge)
                parts?.getOrNull(1)?.let { Text(it, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = MaterialTheme.spacing.sm)) }
                fix?.accuracyM?.let {
                    Text("±${it.toInt()} m", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = MaterialTheme.spacing.md))
                }
            }
            Text(
                fix?.let { proximityLabel(it.proximity) } ?: "Getting a fix",
                style = MaterialTheme.typography.headlineSmall,
                color = if (fix?.proximity == Proximity.HERE) MaterialTheme.semantic.safe else MaterialTheme.colorScheme.onSurface,
            )
            notice.landmark?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (fix != null && fix?.relativeBearingDeg == null && fix?.bearingDeg != null) {
                Text("No compass on this phone: the map is north-up.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.semantic.caution)
            }
            Spacer(Modifier.weight(1f))
            LargeButton("We're together", { reunite(graph, nav, lostId) }, sym = Sym.Done, colors = safeButtonColors())
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
        }
    }
}

@Composable
private fun RadarScope(fix: RadarFix?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val ring = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val target = MaterialTheme.colorScheme.primary
    val targetHalo = MaterialTheme.colorScheme.primaryContainer
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = ink)
    val hairline = MaterialTheme.sizes.hairline
    val stroke = MaterialTheme.sizes.strokeBold
    val pad = MaterialTheme.spacing.xs
    val dot = MaterialTheme.sizes.statusDot
    val heading = fix?.relativeBearingDeg != null
    val angle = fix?.relativeBearingDeg ?: fix?.bearingDeg
    val d = fix?.distanceM
    val range = d?.let(::rangeFor) ?: 100.0

    Canvas(modifier.semantics { contentDescription = if (d != null) "Your child is ${distanceLabel(d)} away" else "Looking for a fix" }) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - dot.toPx()
        for (k in 1..4) drawCircle(ring, r * k / 4f, c, style = Stroke(hairline.toPx()))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), hairline.toPx())
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), hairline.toPx())
        val diag = 0.7071f
        for (k in listOf(2, 4)) {
            val rr = r * k / 4f
            drawText(measurer, distanceLabel(range * k / 4), c + Offset(rr * diag + pad.toPx(), rr * diag - pad.toPx() * 4), labelStyle)
        }

        // Up marker: a triangle for "ahead" with a compass, otherwise an N.
        if (heading) {
            val tip = Offset(c.x, c.y - r - pad.toPx())
            val w = dot.toPx()
            val p = Path().apply { moveTo(tip.x, tip.y); lineTo(tip.x - w, tip.y + w * 1.6f); lineTo(tip.x + w, tip.y + w * 1.6f); close() }
            drawPath(p, ink)
        } else {
            drawText(measurer, "N", Offset(c.x - pad.toPx(), c.y - r), labelStyle)
        }

        if (d != null && angle != null) {
            val rr = (d / range).toFloat().coerceIn(0f, 1f) * r
            val a = Math.toRadians(angle)
            val at = Offset(c.x + sin(a).toFloat() * rr, c.y - cos(a).toFloat() * rr)
            drawLine(target, c, at, strokeWidth = stroke.toPx(), cap = StrokeCap.Round)
            drawCircle(targetHalo, dot.toPx() * 2.5f, at)
            drawCircle(target, dot.toPx(), at)
        } else if (d != null) {
            // Signal strength only: roughly how far, not which way.
            drawCircle(target, (d / range).toFloat().coerceIn(0f, 1f) * r, c, style = Stroke(stroke.toPx()))
        }
        drawCircle(ink, dot.toPx() / 2, c)
    }
}
