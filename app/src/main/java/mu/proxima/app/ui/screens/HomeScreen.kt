package mu.proxima.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.LostFound
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.Screen
import mu.proxima.app.ui.VoiceCapture
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.MeshStatus
import mu.proxima.app.ui.components.SeverityBadge
import mu.proxima.app.ui.components.SimLabel
import mu.proxima.app.ui.components.Sym
import mu.proxima.app.ui.components.SymbolIcon
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.sym
import mu.proxima.app.ui.theme.Motion
import mu.proxima.app.ui.theme.container
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Incidents a civilian should see: open, and not a guardian-only sighting. */
fun List<IncidentRecord>.civilianVisible() =
    filter { !it.resolved && it.incident.type != IncidentType.SIGHTING && it.incident.type != IncidentType.ALL_CLEAR }

/** How long a finger must rest on the SOS before it arms. A brush or a scroll never fires it. */
private const val ARM_MS = 600L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(graph: ProximaGraph, capture: VoiceCapture, lostFound: LostFound, nav: Nav, onDev: () -> Unit) {
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val open = incidents.civilianVisible()
    val myLost = lostFound.myOpenLost(incidents).firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Proxima",
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
                    )
                },
                actions = {
                    MeshStatus(peers.size)
                    if (graph.simulation) {
                        Spacer(Modifier.width(MaterialTheme.spacing.sm))
                        SimLabel()
                    }
                    Spacer(Modifier.width(MaterialTheme.spacing.lg))
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = MaterialTheme.spacing.xl)) {
            if (myLost != null) {
                GuardianBanner(myLost, lostFound.notices[myLost.incident.id] != null) {
                    nav.go(Screen.Found(myLost.incident.id))
                }
            } else {
                Text("Need help?", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                Text(
                    "Hold the red button and say what's happening.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SosButton(capture, onRelease = { nav.go(Screen.Report(fromHold = true)) })
            }

            ReportActions(onPick = { nav.go(Screen.Report(hint = it)) })
            Spacer(Modifier.height(MaterialTheme.spacing.md))
            AlertsRow(open, onClick = { nav.go(Screen.Feed) })
            TextButton(
                onClick = { nav.go(Screen.Finder) },
                modifier = Modifier.fillMaxWidth().padding(bottom = MaterialTheme.spacing.sm),
            ) {
                SymbolIcon(Sym.Person, description = null, size = MaterialTheme.sizes.iconSmall)
                Spacer(Modifier.width(MaterialTheme.spacing.sm))
                Text("I'm with someone who is lost", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The SOS button. Rest a finger on it: an arc closes around the disc in 0.6 s, then it arms (haptic) and
 * listens while held; the voice level is drawn as ticks around the disc; lifting sends you to the review.
 * Lifting early or sliding past touch slop aborts. TalkBack: double-tap starts listening on the next screen.
 * At rest it is a still disc with a thin ring. No looping motion (DESIGN_RULES ban 8).
 */
@Composable
private fun SosButton(capture: VoiceCapture, onRelease: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var arming by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    var tapHint by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (held || arming) 0.94f else 1f, Motion.stateSpring(), label = "press")
    val arm = remember { Animatable(0f) }
    LaunchedEffect(arming) {
        if (arming) arm.animateTo(1f, tween(ARM_MS.toInt(), easing = Motion.Standard)) else arm.snapTo(0f)
    }
    LaunchedEffect(tapHint) { if (tapHint) { delay(2_500); tapHint = false } }

    val critical = MaterialTheme.semantic.critical
    val ring = MaterialTheme.semantic.criticalContainer
    val arc = MaterialTheme.colorScheme.onSurface
    val stroke = MaterialTheme.sizes.stroke
    val strokeBold = MaterialTheme.sizes.strokeBold

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(MaterialTheme.sizes.sos)
                    .semantics {
                        contentDescription = "SOS. Hold to call for help"
                        role = Role.Button
                        onClick { capture.start(); onRelease(); true }
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            arming = true
                            tapHint = false
                            val aborted = withTimeoutOrNull(ARM_MS) {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        ?: return@withTimeoutOrNull true
                                    if (!change.pressed) { tapHint = true; return@withTimeoutOrNull true }
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        return@withTimeoutOrNull true
                                    }
                                }
                                @Suppress("UNREACHABLE_CODE")
                                false
                            }
                            arming = false
                            if (aborted == true) return@awaitEachGesture

                            held = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            capture.start()
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                            held = false
                            capture.stop()
                            onRelease()
                        }
                    },
            ) {
                val c = Offset(size.width / 2, size.height / 2)
                val outer = size.minDimension / 2
                val core = outer * 0.70f * press
                val gap = outer * 0.08f

                when {
                    held -> {
                        // Voice level as radial ticks: shows the phone is hearing you.
                        val levels = capture.levels
                        val n = 48
                        for (i in 0 until n) {
                            val lv = if (levels.isEmpty()) 0.05f else levels[(levels.size - 1 - i % levels.size).coerceAtLeast(0)]
                            val a = (i.toFloat() / n) * 2f * PI.toFloat() - PI.toFloat() / 2
                            val r0 = core + gap
                            val r1 = r0 + (outer - r0) * (0.25f + 0.75f * lv)
                            drawLine(
                                critical.copy(alpha = 0.5f + 0.5f * lv),
                                Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                                Offset(c.x + cos(a) * r1, c.y + sin(a) * r1),
                                strokeWidth = stroke.toPx(), cap = StrokeCap.Round,
                            )
                        }
                    }
                    arming -> {
                        val r = core + gap
                        drawArc(
                            arc, startAngle = -90f, sweepAngle = 360f * arm.value, useCenter = false,
                            topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                            style = Stroke(strokeBold.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    else -> drawCircle(ring, core + gap, c, style = Stroke(strokeBold.toPx()))
                }
                drawCircle(critical, core, c)
            }
            Text("SOS", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.semantic.onCritical)
        }
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(
            when {
                held -> "Listening. Let go when you're done."
                arming -> "Keep holding"
                tapHint -> "Keep your finger on it for a moment"
                else -> "Hold to talk"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (held || arming || tapHint) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Three report types as outlined cards (one card variant project-wide). */
@Composable
private fun ReportActions(onPick: (IncidentType) -> Unit) {
    val items = listOf(
        IncidentType.MEDICAL to "Medical",
        IncidentType.HAZARD to "Hazard",
        IncidentType.LOST_PERSON to "Lost person",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        items.forEach { (type, label) ->
            OutlinedCard(onClick = { onPick(type) }, modifier = Modifier.weight(1f)) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SymbolIcon(type.sym(), description = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(MaterialTheme.spacing.sm))
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** The most urgent alert around you, in one row. Empty state still leads somewhere. */
@Composable
private fun AlertsRow(open: List<IncidentRecord>, onClick: () -> Unit) {
    val top = open.maxWithOrNull(compareBy<IncidentRecord> { it.incident.severity }.thenBy { it.incident.createdAtMs })
    val (container, content) = top?.incident?.severity?.container()
        ?: (MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant)
    ListItem(
        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(role = Role.Button, onClick = onClick),
        headlineContent = {
            Text(
                when (open.size) { 0 -> "No alerts near you"; 1 -> "1 alert near you"; else -> "${open.size} alerts near you" },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                top?.let { incidentTitle(it.incident.type, it.incident.subtype) } ?: "Alerts from phones around you appear here.",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { SeverityBadge(top?.incident?.type?.sym() ?: Sym.Mesh, container, content, description = null) },
        trailingContent = top?.let { t -> { Text(ageLabel(t.lastUpdatedMs), style = MaterialTheme.typography.labelLarge) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

/** Guardian with an open lost report: replaces the headline. Caution while searching, safe once someone is with them. */
@Composable
private fun GuardianBanner(lost: IncidentRecord, someoneWithThem: Boolean, onOpen: () -> Unit) {
    val s = MaterialTheme.semantic
    val (container, content) = if (someoneWithThem) s.safeContainer to s.onSafeContainer else s.cautionContainer to s.onCautionContainer
    OutlinedCard(
        onClick = onOpen,
        enabled = someoneWithThem,
        colors = CardDefaults.outlinedCardColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = container, disabledContentColor = content,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(MaterialTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            SymbolIcon(if (someoneWithThem) Sym.Done else Sym.LostPerson, description = null)
            Spacer(Modifier.width(MaterialTheme.spacing.md))
            Column {
                Text(
                    if (someoneWithThem) "Someone is with your child" else "Looking for your child",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                Text(
                    if (someoneWithThem) "Tap to see where they are."
                    else "Phones nearby can see your description. We'll alert you the moment someone is with them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                Text("“${lost.incident.summary}”", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
