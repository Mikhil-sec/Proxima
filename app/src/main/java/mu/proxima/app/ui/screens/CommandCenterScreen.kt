package mu.proxima.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.components.MeshStatus
import mu.proxima.app.ui.components.SimLabel
import mu.proxima.app.ui.components.VerifiedMark
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.theme.fill
import mu.proxima.app.ui.theme.semantic
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.Peer
import mu.proxima.core.model.Severity
import mu.proxima.core.model.TriageSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Command Center = dispatch board for a steward at a first-aid / police post, read from ~2 m.
 * Queue first (who to send, where, how long they've waited), the site plan second. Density: dense.
 */

private const val CELL_M = 25.0
private const val EARTH_R = 6_371_000.0
private const val HOP_MS = 900L
/** Beacons arrive every ~10 s and relayed peers are kept as last seen (MESH, #30): older than this, a phone isn't counted. */
private const val DENSITY_FRESH_MS = 60_000L
/** Phones in one 25 m square from which the cell reads as crowded (darkest shade). */
private const val CROWDED = 4
/** Unhandled for this long and the waiting time switches to the caution role. */
private const val STALE_MS = 5 * 60_000L

/** Tabular figures so minutes and counts don't jitter as they change. */
private fun TextStyle.figures() = copy(fontFeatureSettings = "tnum")

/** Triage-board wording a steward already knows. */
private fun Severity.priority(): String = when (this) {
    Severity.CRITICAL -> "Immediate"
    Severity.HIGH -> "Urgent"
    Severity.MEDIUM -> "Soon"
    Severity.LOW -> "Minor"
}

/** Who sorted it, short enough never to clip. Shown so judges see the AI tiering. */
private fun TriageSource.short(): String = when (this) {
    TriageSource.ON_DEVICE_LLM -> "On-device AI"
    TriageSource.OFFLOADED_LLM -> "AI via nearby phone"
    TriageSource.RULES -> "Keyword triage"
    TriageSource.MANUAL -> "Reported by hand"
}

private fun compass(deg: Double): String =
    listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((deg + 22.5) / 45).toInt() % 8]

private fun waitingLabel(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return when { m < 1 -> "now"; m < 60 -> "$m min"; else -> "${m / 60} h ${m % 60}" }
}

private fun secondsLabel(ms: Long): String = (ms / 1000).let { if (it < 60) "$it s" else "${it / 60} min" }

/** Where, in a steward's terms: the landmark if someone said one, else distance and direction from this post. */
private fun whereLabel(r: IncidentRecord, post: GeoPoint?): String? {
    val loc = r.incident.location
    val rel = if (post != null && loc != null) {
        val d = GeoMath.distanceM(post, loc)
        if (d < 15) "At this post" else "${d.roundToInt()} m ${compass(GeoMath.bearingDeg(post, loc))} of this post"
    } else null
    val landmark = r.incident.landmark?.replaceFirstChar(Char::uppercase)
    return when {
        landmark != null && rel != null -> "$landmark, $rel"
        else -> landmark ?: rel
    }
}

private data class HopAnim(val hop: HopEvent, val startMs: Long)

/** Board state only this tablet needs: who has been sent, and the stable letter tying a ticket to its pin. */
private class Board {
    val claimedAt = mutableStateMapOf<String, Long>()
    private val letters = HashMap<String, String>()
    private var next = 0
    fun letter(id: String): String = letters.getOrPut(id) {
        val n = next++
        if (n < 26) ('A' + n).toString() else "A${n - 25}"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommandCenterScreen(graph: ProximaGraph, onDev: () -> Unit) {
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val post by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    val reducedMotion = rememberReducedMotion()
    val board = remember { Board() }
    var selected by remember { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<String?>(null) }

    // Hop pulses: motion that answers an event (a packet crossed a link), not decoration.
    val anims = remember { mutableStateListOf<HopAnim>() }
    var lastHopMs by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        graph.mesh.hops.collect { hop ->
            lastHopMs = System.currentTimeMillis()
            if (!reducedMotion) anims.add(HopAnim(hop, lastHopMs))
        }
    }
    val animating = anims.isNotEmpty()
    LaunchedEffect(animating) {
        while (anims.isNotEmpty()) {
            withFrameMillis { frame = System.currentTimeMillis() }
            anims.removeAll { frame - it.startMs > HOP_MS }
        }
    }

    val open = incidents.filter {
        !it.resolved && it.incident.type != IncidentType.ALL_CLEAR && it.incident.type != IncidentType.FOUND
    }.sortedBy { it.incident.createdAtMs }
    open.forEach { board.letter(it.incident.id) } // letters follow arrival order and never change
    val waiting = open.filter { it.incident.id !in board.claimedAt }
        .sortedWith(compareByDescending<IncidentRecord> { it.incident.severity }.thenBy { it.incident.createdAtMs })
    val handled = open.filter { it.incident.id in board.claimedAt }.sortedBy { board.claimedAt[it.incident.id] }
    val cleared = incidents.count { it.resolved }

    fun clear(id: String) {
        graph.scope.launch { graph.pipeline.resolve(id, "Cleared by the post") }
        board.claimedAt.remove(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Proxima command",
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
                    )
                },
                actions = {
                    MeshStatus(peers.size)
                    if (graph.simulation) {
                        Spacer(Modifier.width(MaterialTheme.spacing.md))
                        SimLabel()
                    }
                    Spacer(Modifier.width(MaterialTheme.spacing.xxl))
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val landscape = maxWidth > maxHeight
            val queue: @Composable (Modifier) -> Unit = { m ->
                Queue(waiting, handled, cleared, peers, post, now, board, selected, m,
                    onOpen = { id -> selected = id; sheetFor = id },
                    onClaim = { id -> board.claimedAt[id] = System.currentTimeMillis(); selected = id },
                    onClear = ::clear)
            }
            val plan: @Composable (Modifier) -> Unit = { m ->
                SitePlan(graph.mesh.selfNodeId, post, peers, open, board, anims, frame, selected, lastHopMs, now, m)
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    queue(Modifier.weight(0.58f).fillMaxHeight())
                    VerticalDivider()
                    plan(Modifier.weight(0.42f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    queue(Modifier.weight(0.6f).fillMaxWidth())
                    plan(Modifier.weight(0.4f).fillMaxWidth())
                }
            }
        }
    }

    val sheet = incidents.firstOrNull { it.incident.id == sheetFor }
    if (sheet != null) IncidentSheet(graph, sheet, post, onDismiss = { sheetFor = null })
}

@Composable
private fun Queue(
    waiting: List<IncidentRecord>,
    handled: List<IncidentRecord>,
    cleared: Int,
    peers: List<Peer>,
    post: GeoPoint?,
    now: Long,
    board: Board,
    selected: String?,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onClaim: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    val immediate = waiting.count { it.incident.severity == Severity.CRITICAL }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.xxl, vertical = MaterialTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        item(key = "head") {
            Text(
                when (waiting.size) { 0 -> "Nobody waiting"; 1 -> "1 needs someone now"; else -> "${waiting.size} need someone now" },
                style = MaterialTheme.typography.displaySmall.figures(),
                color = if (immediate > 0) MaterialTheme.semantic.critical else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildList {
                    if (handled.isNotEmpty()) add("${handled.size} being handled.")
                    if (cleared > 0) add("$cleared cleared.")
                    add(if (peers.isEmpty()) "No phones connected yet." else "${peers.size} phones connected.")
                }.joinToString(" "),
                style = MaterialTheme.typography.bodyLarge.figures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (waiting.isEmpty()) {
            item(key = "empty") {
                Text(
                    "New reports from phones nearby appear here, most urgent first. Nothing needs a person right now.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(waiting, key = { it.incident.id }) { r ->
            Ticket(r, board.letter(r.incident.id), post, now, r.incident.id == selected, Modifier.animateItem(),
                onOpen = { onOpen(r.incident.id) }, onClaim = { onClaim(r.incident.id) }, onClear = { onClear(r.incident.id) })
        }
        if (handled.isNotEmpty()) {
            item(key = "handled-head") {
                Text("Someone is on it", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = MaterialTheme.spacing.lg))
            }
            items(handled, key = { "h-" + it.incident.id }) { r ->
                val i = r.incident
                val ago = waitingLabel(now - (board.claimedAt[i.id] ?: now)).let { if (it == "now") "just now" else "$it ago" }
                ListItem(
                    modifier = Modifier.animateItem().clip(MaterialTheme.shapes.large).clickable(role = Role.Button) { onOpen(i.id) },
                    headlineContent = { Text(incidentTitle(i.type, i.subtype), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("Someone went $ago", style = MaterialTheme.typography.bodyMedium.figures()) },
                    leadingContent = { LetterPin(board.letter(i.id), i.severity) },
                    trailingContent = { OutlinedButton(onClick = { onClear(i.id) }) { Text("Cleared") } },
                )
            }
        }
    }
}

/** Letter pin: the same mark on the ticket and on the plan. */
@Composable
private fun LetterPin(letter: String, severity: Severity) {
    val (fill, onFill) = severity.fill()
    Box(
        Modifier.size(MaterialTheme.sizes.pin).clip(CircleShape).background(fill).semantics { contentDescription = "Marker $letter" },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = onFill)
    }
}

@Composable
private fun Ticket(
    r: IncidentRecord,
    letter: String,
    post: GeoPoint?,
    now: Long,
    selected: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onClaim: () -> Unit,
    onClear: () -> Unit,
) {
    val i = r.incident
    val waitedMs = now - i.createdAtMs
    val stale = waitedMs > STALE_MS
    val (fill, onFill) = i.severity.fill()
    OutlinedCard(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                LetterPin(letter, i.severity)
                Spacer(Modifier.width(MaterialTheme.spacing.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = fill, contentColor = onFill, shape = MaterialTheme.shapes.small) {
                            Text(
                                i.severity.priority(),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
                            )
                        }
                        Spacer(Modifier.width(MaterialTheme.spacing.md))
                        Text(incidentTitle(i.type, i.subtype), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    whereLabel(r, post)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(i.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(MaterialTheme.spacing.md))
                // The waiting time earns its size only once someone has actually been waiting.
                if (waitedMs < 60_000) {
                    Text("Just in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            waitingLabel(waitedMs),
                            style = MaterialTheme.typography.headlineMedium.figures(),
                            color = if (stale) MaterialTheme.semantic.caution else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (stale) "nobody sent yet" else "waiting",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (stale) MaterialTheme.semantic.caution else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.md))
            Row(Modifier.padding(start = MaterialTheme.sizes.pin + MaterialTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    if (i.reportCount > 1) Text("${i.reportCount} people reported it", style = MaterialTheme.typography.labelLarge.figures())
                    VerifiedMark(r.signatureValid)
                    Text(i.triagedBy.short(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                OutlinedButton(onClick = onClear) { Text("Cleared") }
                Spacer(Modifier.width(MaterialTheme.spacing.sm))
                FilledTonalButton(onClick = onClaim) { Text("Someone's going") }
            }
        }
    }
}

/** Venue-local metres east/north of [origin]. */
private fun local(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
    val east = Math.toRadians(p.lon - origin.lon) * EARTH_R * cos(Math.toRadians(origin.lat))
    val north = Math.toRadians(p.lat - origin.lat) * EARTH_R
    return east to north
}

/** Colours and stroke widths resolved from the theme once, for the plan's DrawScope helpers. */
private data class PlanInk(
    val line: Color,
    val node: Color,
    val nodeFill: Color,
    val post: Color,
    val pulse: Color,
    val density: Color,
    val label: TextStyle,
    val postLabel: TextStyle,
    val hairline: Float,
    val stroke: Float,
    val strokeBold: Float,
    val pinRadius: Float,
    val gap: Float,
    val hatch: Float,
)

/**
 * The site plan: thin linework, anonymous incident zones as hatching, letter pins matching the queue,
 * a pulse when an alert crosses a link. Quiet on purpose; the queue is the primary tool.
 */
@Composable
private fun SitePlan(
    selfId: String,
    post: GeoPoint?,
    peers: List<Peer>,
    open: List<IncidentRecord>,
    board: Board,
    anims: List<HopAnim>,
    frame: Long,
    selected: String?,
    lastHopMs: Long,
    now: Long,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val origin = post ?: peers.firstNotNullOfOrNull { it.location } ?: open.firstNotNullOfOrNull { it.incident.location }
    val direct = peers.count { it.direct }
    val sevFills = Severity.entries.associateWith { it.fill() }
    val ink = PlanInk(
        line = MaterialTheme.colorScheme.outlineVariant,
        node = MaterialTheme.colorScheme.onSurfaceVariant,
        nodeFill = MaterialTheme.colorScheme.surface,
        post = MaterialTheme.colorScheme.primary,
        pulse = MaterialTheme.colorScheme.primary,
        density = MaterialTheme.colorScheme.primary,
        label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        postLabel = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.primary),
        hairline = MaterialTheme.sizes.hairline.px(),
        stroke = MaterialTheme.sizes.stroke.px(),
        strokeBold = MaterialTheme.sizes.strokeBold.px(),
        pinRadius = MaterialTheme.sizes.pin.px() / 2.6f,
        gap = MaterialTheme.spacing.xs.px(),
        hatch = MaterialTheme.spacing.sm.px(),
    )

    Column(modifier.padding(MaterialTheme.spacing.xxl)) {
        Text("Around this post", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize().semantics {
                    contentDescription = "Plan of the area: ${peers.size} phones, ${open.size} open incidents"
                },
            ) {
                if (origin == null) return@Canvas
                val pts = peers.mapNotNull { it.location } + open.mapNotNull { it.incident.location }
                val rangeM = max(60.0, pts.maxOfOrNull { val (e, n) = local(origin, it); max(abs(e), abs(n)) } ?: 0.0) * 1.2
                val pxPerM = (size.minDimension / 2f) / rangeM.toFloat()
                val c = Offset(size.width / 2, size.height / 2)
                fun toPx(p: GeoPoint): Offset {
                    val (e, n) = local(origin, p)
                    return Offset(c.x + e.toFloat() * pxPerM, c.y - n.toFloat() * pxPerM)
                }

                val pos = HashMap<String, Offset>()
                pos[selfId] = c
                peers.forEachIndexed { i, p ->
                    pos[p.nodeId] = p.location?.let(::toPx)
                        ?: Offset(c.x + cos(i * 2.4f) * size.minDimension * 0.4f, c.y + sin(i * 2.4f) * size.minDimension * 0.4f)
                }

                drawDensity(origin, c, pxPerM, peers, measurer, ink)

                val directPeers = peers.filter { it.direct }
                directPeers.forEach { drawLine(ink.line, c, pos.getValue(it.nodeId), ink.stroke) }
                peers.filter { !it.direct }.forEach { p ->
                    val from = pos.getValue(p.nodeId)
                    val via = directPeers.minByOrNull { (pos.getValue(it.nodeId) - from).getDistance() }?.let { pos.getValue(it.nodeId) } ?: c
                    drawLine(ink.line, from, via, ink.hairline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(ink.hatch, ink.hatch)))
                }

                drawZones(origin, c, pxPerM, open, selected, sevFills, ink)

                anims.forEach { a ->
                    val from = pos[a.hop.fromNodeId] ?: return@forEach
                    val to = pos[a.hop.toNodeId] ?: return@forEach
                    val t = ((frame - a.startMs).toFloat() / HOP_MS).coerceIn(0f, 1f)
                    val head = from + (to - from) * t
                    drawLine(ink.pulse.copy(alpha = 0.4f * (1f - t)), from, head, ink.strokeBold)
                    drawCircle(ink.pulse, ink.strokeBold * 1.5f, head)
                }

                peers.forEach { drawPhone(it.tier, pos.getValue(it.nodeId), ink) }

                val s = ink.pinRadius * 0.7f
                drawRect(ink.post, c - Offset(s, s), Size(2 * s, 2 * s))
                drawText(measurer.measure("This post", ink.postLabel), color = ink.post, topLeft = c + Offset(s + ink.gap * 2, -s - ink.gap * 4))

                drawPins(origin, c, pxPerM, open, board, measurer, sevFills, ink)
                drawScale(pxPerM, measurer, ink)
            }
            if (origin == null) {
                Text(
                    "Waiting for this tablet's location to draw the plan.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        Text(
            buildString {
                append(if (peers.isEmpty()) "Live mesh: looking for phones." else "Live mesh: ${peers.size} phones, $direct linked directly.")
                if (lastHopMs > 0) append(" Last relay ${secondsLabel(now - lastHopMs)} ago.")
            },
            style = MaterialTheme.typography.bodyMedium.figures(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Shading: phones per 25 m square, from their beacons; no one is tracked. Square: phone that runs AI and " +
                "relays. Circle: phone. Hatching: where reports came from.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Dp.px(): Float = with(androidx.compose.ui.platform.LocalDensity.current) { toPx() }

/** Phones: square = runs AI + relays, circle = phone, diamond = another post. Outline only, so pins stay loudest. */
private fun DrawScope.drawPhone(tier: DeviceTier, at: Offset, ink: PlanInk) {
    val r = ink.pinRadius * 0.55f
    val stroke = Stroke(ink.stroke)
    when (tier) {
        DeviceTier.TIER1_COMPUTE -> {
            drawRect(ink.nodeFill, at - Offset(r, r), Size(2 * r, 2 * r))
            drawRect(ink.node, at - Offset(r, r), Size(2 * r, 2 * r), style = stroke)
        }
        DeviceTier.TIER2_SCOUT -> {
            drawCircle(ink.nodeFill, r, at)
            drawCircle(ink.node, r, at, style = stroke)
        }
        DeviceTier.COMMAND_CENTER -> {
            val d = Path().apply {
                moveTo(at.x, at.y - r * 1.3f); lineTo(at.x + r * 1.3f, at.y); lineTo(at.x, at.y + r * 1.3f); lineTo(at.x - r * 1.3f, at.y); close()
            }
            drawPath(d, ink.nodeFill)
            drawPath(d, ink.node, style = stroke)
        }
    }
}

/**
 * Invert GeoMath.cellKey to the cell's centre, then project it like every other point on the plan. (Projecting the
 * key with the origin's cos(lat) drifted tens of metres per 100 m north-south at Mauritius' longitude.)
 */
private fun cellOf(origin: GeoPoint, c: Offset, pxPerM: Float, key: Pair<Int, Int>): Rect {
    val lat = Math.toDegrees(key.second * CELL_M / EARTH_R)
    val lon = Math.toDegrees(key.first * CELL_M / (EARTH_R * cos(Math.toRadians(lat))))
    val (e, n) = local(origin, GeoPoint(lat, lon))
    val center = Offset(c.x + e.toFloat() * pxPerM, c.y - n.toFloat() * pxPerM)
    val half = CELL_M.toFloat() * pxPerM / 2
    return Rect(center.x - half, center.y - half, center.x + half, center.y + half)
}

/**
 * Crowd density from beacons: phones per anonymous 25 m cell. Beacon locations arrive already snapped to their
 * cell (MeshBeaconInfo.coarse), so this only counts; it never places a person. Three shades, and the count in
 * cells with two or more phones. Drawn first, under links, zones and pins.
 * (Text colour goes in at draw time on this plan: TextMeasurer reuses cached layouts across a light/dark switch.)
 */
private fun DrawScope.drawDensity(origin: GeoPoint, c: Offset, pxPerM: Float, peers: List<Peer>, measurer: TextMeasurer, ink: PlanInk) {
    val freshSince = System.currentTimeMillis() - DENSITY_FRESH_MS
    peers.filter { it.lastSeenMs >= freshSince }.mapNotNull { it.location }
        .groupingBy { GeoMath.cellKey(it, CELL_M) }
        .eachCount()
        .forEach { (key, n) ->
            val cell = cellOf(origin, c, pxPerM, key)
            val alpha = when { n >= CROWDED -> 0.32f; n >= 2 -> 0.18f; else -> 0.08f }
            drawRect(ink.density.copy(alpha = alpha), cell.topLeft, cell.size)
            if (n >= 2) {
                val t = measurer.measure("$n", ink.label)
                drawText(t, color = ink.node, topLeft = Offset(cell.left + ink.gap, cell.bottom - t.size.height - ink.gap))
            }
        }
}

/** Anonymous density: reports per 25 m cell (GeoMath.cellKey), drawn as hatching. Denser hatching = more reports. */
private fun DrawScope.drawZones(
    origin: GeoPoint, c: Offset, pxPerM: Float, open: List<IncidentRecord>, selected: String?,
    sevFills: Map<Severity, Pair<Color, Color>>, ink: PlanInk,
) {
    open.filter { it.incident.location != null }
        .groupBy { GeoMath.cellKey(it.incident.location!!, CELL_M) }
        .forEach { (key, recs) ->
            val cell = cellOf(origin, c, pxPerM, key)
            val worst = sevFills.getValue(recs.maxOf { it.incident.severity }).first
            val reports = recs.sumOf { it.incident.reportCount }
            val gap = ink.hatch * (1.2f - reports.coerceAtMost(4) * 0.15f)
            clipRect(cell.left, cell.top, cell.right, cell.bottom) {
                var x = cell.left - cell.height
                while (x < cell.right) {
                    drawLine(worst.copy(alpha = 0.6f), Offset(x, cell.bottom), Offset(x + cell.height, cell.top), ink.stroke * 0.75f)
                    x += gap
                }
            }
            val sel = recs.any { it.incident.id == selected }
            drawRect(worst, cell.topLeft, cell.size, style = Stroke(if (sel) ink.strokeBold else ink.hairline))
        }
}

private fun DrawScope.drawPins(
    origin: GeoPoint, c: Offset, pxPerM: Float, open: List<IncidentRecord>, board: Board, measurer: TextMeasurer,
    sevFills: Map<Severity, Pair<Color, Color>>, ink: PlanInk,
) {
    open.filter { it.incident.location != null }
        .groupBy { GeoMath.cellKey(it.incident.location!!, CELL_M) }
        .forEach { (key, recs) ->
            val cell = cellOf(origin, c, pxPerM, key)
            recs.sortedByDescending { it.incident.severity }.forEachIndexed { n, r ->
                val (fill, onFill) = sevFills.getValue(r.incident.severity)
                val at = Offset(cell.right + ink.gap + n * ink.pinRadius * 2.2f, cell.top - ink.gap)
                drawCircle(ink.nodeFill, ink.pinRadius + ink.stroke, at)
                drawCircle(fill, ink.pinRadius, at)
                val t = measurer.measure(board.letter(r.incident.id), ink.label.copy(color = onFill))
                drawText(t, color = onFill, topLeft = at - Offset(t.size.width / 2f, t.size.height / 2f))
            }
        }
}

private fun DrawScope.drawScale(pxPerM: Float, measurer: TextMeasurer, ink: PlanInk) {
    val len = 50f * pxPerM
    val y = size.height - ink.gap * 2
    val tick = ink.gap
    drawLine(ink.node, Offset(0f, y), Offset(len, y), ink.stroke)
    drawLine(ink.node, Offset(0f, y - tick), Offset(0f, y + tick), ink.stroke)
    drawLine(ink.node, Offset(len, y - tick), Offset(len, y + tick), ink.stroke)
    drawText(measurer.measure("50 m", ink.label), color = ink.node, topLeft = Offset(len + ink.gap * 2, y - ink.gap * 4))
    // North: a plain letter over a short tick, top right.
    val n = Offset(size.width - ink.gap * 3, 0f)
    drawText(measurer.measure("N", ink.label), color = ink.node, topLeft = n)
    drawLine(ink.node, n + Offset(ink.gap, ink.gap * 5), n + Offset(ink.gap, ink.gap * 9), ink.stroke)
}
