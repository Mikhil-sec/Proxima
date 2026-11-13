package mu.proxima.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.SimBadge
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.color
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.Peer
import mu.proxima.core.model.Severity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Command Center = dispatch board for a steward at a first-aid / police post, read from ~2 m.
 * Queue first (who to send, where, how long they've waited), a quiet site plan second.
 * Register rules for this screen: Space Grotesk only, tabular figures, sentence case, no mono, no KPI tiles.
 */

private const val CELL_M = 25.0
private const val EARTH_R = 6_371_000.0
private const val HOP_MS = 900L
/** Unhandled for this long and the waiting time turns amber. */
private const val STALE_MS = 5 * 60_000L

/** Grotesk with tabular figures, so minutes and counts don't jitter as they change. */
private fun figures(size: Int, weight: FontWeight = FontWeight.Bold, color: Color = Px.Text): TextStyle =
    grotesk(size, weight, color).copy(fontFeatureSettings = "tnum")

/** Triage-board wording a steward already knows. */
private fun Severity.priority(): String = when (this) {
    Severity.CRITICAL -> "Immediate"
    Severity.HIGH -> "Urgent"
    Severity.MEDIUM -> "Soon"
    Severity.LOW -> "Minor"
}

/** Who sorted it, short enough to never clip on a ticket. Shown so judges see the AI tiering. */
private fun mu.proxima.core.model.TriageSource.short(): String = when (this) {
    mu.proxima.core.model.TriageSource.ON_DEVICE_LLM -> "On-device AI"
    mu.proxima.core.model.TriageSource.OFFLOADED_LLM -> "AI via nearby phone"
    mu.proxima.core.model.TriageSource.RULES -> "Keyword triage"
    mu.proxima.core.model.TriageSource.MANUAL -> "Reported by hand"
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

/** Board state only this tablet needs: who has been sent, and the stable letter that ties a ticket to its pin. */
private class Board {
    val claimedAt = mutableStateMapOf<String, Long>()
    private val letters = HashMap<String, String>()
    private var next = 0
    fun letter(id: String): String = letters.getOrPut(id) {
        val n = next++
        if (n < 26) ('A' + n).toString() else "A${n - 25}"
    }
}

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
    open.forEach { board.letter(it.incident.id) } // letters follow arrival order, never change
    val waiting = open.filter { it.incident.id !in board.claimedAt }
        .sortedWith(compareByDescending<IncidentRecord> { it.incident.severity }.thenBy { it.incident.createdAtMs })
    val handled = open.filter { it.incident.id in board.claimedAt }.sortedBy { board.claimedAt[it.incident.id] }
    val cleared = incidents.count { it.resolved }

    fun clear(id: String) {
        graph.scope.launch { graph.pipeline.resolve(id, "Cleared by the post") }
        board.claimedAt.remove(id)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val queue: @Composable (Modifier) -> Unit = { m ->
            Queue(
                graph, waiting, handled, cleared, peers, post, now, board, selected, onDev, m,
                onOpen = { id -> selected = id; sheetFor = id },
                onClaim = { id -> board.claimedAt[id] = System.currentTimeMillis(); selected = id },
                onClear = ::clear,
            )
        }
        val plan: @Composable (Modifier) -> Unit = { m ->
            SitePlan(graph.mesh.selfNodeId, post, peers, open, board, anims, frame, selected, lastHopMs, now, m)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                queue(Modifier.weight(0.58f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Px.Line))
                plan(Modifier.weight(0.42f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                queue(Modifier.weight(0.6f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Line))
                plan(Modifier.weight(0.4f).fillMaxWidth())
            }
        }
    }

    val sheet = incidents.firstOrNull { it.incident.id == sheetFor }
    if (sheet != null) IncidentSheet(graph, sheet, post, onDismiss = { sheetFor = null })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Queue(
    graph: ProximaGraph,
    waiting: List<IncidentRecord>,
    handled: List<IncidentRecord>,
    cleared: Int,
    peers: List<Peer>,
    post: GeoPoint?,
    now: Long,
    board: Board,
    selected: String?,
    onDev: () -> Unit,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onClaim: (String) -> Unit,
    onClear: (String) -> Unit,
) {
    val immediate = waiting.count { it.incident.severity == Severity.CRITICAL }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp)) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Proxima",
                    style = grotesk(16, FontWeight.Bold, Px.Dim),
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
                )
                Spacer(Modifier.weight(1f))
                if (graph.simulation) SimBadge()
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when (waiting.size) { 0 -> "Nobody waiting"; 1 -> "1 needs someone now"; else -> "${waiting.size} need someone now" },
                style = figures(36, color = if (immediate > 0) Px.Crimson else Px.Text),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                buildList {
                    if (handled.isNotEmpty()) add("${handled.size} being handled.")
                    if (cleared > 0) add("$cleared cleared.")
                    add(if (peers.isEmpty()) "No phones connected yet." else "${peers.size} phones connected.")
                }.joinToString(" "),
                style = figures(18, FontWeight.Medium, Px.Dim),
            )
            Spacer(Modifier.height(24.dp))
        }

        if (waiting.isEmpty()) {
            item(key = "empty") {
                Text(
                    "New reports from phones nearby appear here, most urgent first. Nothing needs a person right now.",
                    style = grotesk(18, FontWeight.Medium, Px.Dim),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(waiting, key = { it.incident.id }) { r ->
            Column(Modifier.animateItem()) {
                Ticket(r, board.letter(r.incident.id), post, now, r.incident.id == selected,
                    onOpen = { onOpen(r.incident.id) }, onClaim = { onClaim(r.incident.id) }, onClear = { onClear(r.incident.id) })
                Spacer(Modifier.height(14.dp))
            }
        }

        if (handled.isNotEmpty()) {
            item(key = "handled-head") {
                Spacer(Modifier.height(20.dp))
                Text("Someone is on it", style = grotesk(20, FontWeight.Bold, Px.Dim))
                Spacer(Modifier.height(6.dp))
            }
            items(handled, key = { "h-" + it.incident.id }) { r ->
                HandledRow(r, board.letter(r.incident.id), board.claimedAt[r.incident.id] ?: now, now, Modifier.animateItem(),
                    onOpen = { onOpen(r.incident.id) }, onClear = { onClear(r.incident.id) })
            }
        }
    }
}

/** Letter pin: the same mark on the ticket and on the plan. */
@Composable
private fun LetterPin(letter: String, severity: Severity, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(severity.color()).semantics { contentDescription = "Marker $letter" },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = figures(size / 2, FontWeight.Bold, Px.Black))
    }
}

@Composable
private fun Ticket(
    r: IncidentRecord,
    letter: String,
    post: GeoPoint?,
    now: Long,
    selected: Boolean,
    onOpen: () -> Unit,
    onClaim: () -> Unit,
    onClear: () -> Unit,
) {
    val i = r.incident
    val waitedMs = now - i.createdAtMs
    val stale = waitedMs > STALE_MS
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Px.SurfaceHi else Px.Surface)
            .border(1.dp, if (selected) Px.Dim else Color.Transparent, shape)
            .clickable(role = Role.Button, onClickLabel = "Details", onClick = onOpen)
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            LetterPin(letter, i.severity)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The one loud element on the board: a solid priority tab. Black text on the fill for contrast.
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(i.severity.color()).padding(horizontal = 9.dp, vertical = 2.dp)) {
                        Text(i.severity.priority(), style = grotesk(15, FontWeight.Bold, Px.Black))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(incidentTitle(i.type, i.subtype), style = grotesk(28), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                whereLabel(r, post)?.let { Text(it, style = grotesk(19, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(i.summary, style = grotesk(16, FontWeight.Medium, Px.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            // Waiting time earns its size only once someone has actually been waiting.
            if (waitedMs < 60_000) {
                Text("Just in", style = grotesk(16, FontWeight.Bold, Px.Dim))
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(waitingLabel(waitedMs), style = figures(32, color = if (stale) Px.Amber else Px.Text))
                    Text(if (stale) "nobody sent yet" else "waiting", style = grotesk(14, FontWeight.Medium, if (stale) Px.Amber else Px.Dim))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i.reportCount > 1) Text("${i.reportCount} people reported it", style = figures(15, FontWeight.Bold, Px.Text))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(if (r.signatureValid) Glyph.Check else Glyph.Alert, if (r.signatureValid) Px.Dim else Px.Amber, 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (r.signatureValid) "Verified" else "Not verified",
                        style = grotesk(15, FontWeight.Medium, if (r.signatureValid) Px.Dim else Px.Amber),
                    )
                }
                Text(i.triagedBy.short(), style = grotesk(15, FontWeight.Medium, Px.Dim), maxLines = 1)
            }
            BoardButton("Cleared", filled = false, onClick = onClear)
            Spacer(Modifier.width(8.dp))
            BoardButton("Someone's going", filled = true, onClick = onClaim)
        }
    }
}

@Composable
private fun BoardButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(Px.Text) else Modifier.border(1.dp, Px.Line, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = grotesk(17, FontWeight.Bold, if (filled) Px.Black else Px.Text))
    }
}

@Composable
private fun HandledRow(r: IncidentRecord, letter: String, sentAt: Long, now: Long, modifier: Modifier, onOpen: () -> Unit, onClear: () -> Unit) {
    val i = r.incident
    val ago = waitingLabel(now - sentAt).let { if (it == "now") "just now" else "$it ago" }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LetterPin(letter, i.severity, size = 28)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(incidentTitle(i.type, i.subtype), style = grotesk(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Someone went $ago", style = figures(15, FontWeight.Medium, Px.Dim))
        }
        BoardButton("Cleared", filled = false, onClick = onClear)
    }
}

/** Venue-local metres east/north of [origin]. */
private fun local(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
    val east = Math.toRadians(p.lon - origin.lon) * EARTH_R * cos(Math.toRadians(origin.lat))
    val north = Math.toRadians(p.lat - origin.lat) * EARTH_R
    return east to north
}

/**
 * The site plan: thin linework, incident zones as hatching, letter pins that match the queue,
 * small white pulses when an alert crosses a link. Quiet on purpose; the queue is the primary tool.
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

    Column(modifier.padding(24.dp)) {
        Text("Around this post", style = grotesk(20, FontWeight.Bold, Px.Dim))
        Spacer(Modifier.height(8.dp))
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

                // Links: hairlines. Direct = solid, relayed = dashed to the nearest direct phone.
                val directPeers = peers.filter { it.direct }
                directPeers.forEach { drawLine(Px.Dim.copy(alpha = 0.45f), c, pos.getValue(it.nodeId), 1.dp.toPx()) }
                peers.filter { !it.direct }.forEach { p ->
                    val from = pos.getValue(p.nodeId)
                    val via = directPeers.minByOrNull { (pos.getValue(it.nodeId) - from).getDistance() }?.let { pos.getValue(it.nodeId) } ?: c
                    drawLine(Px.Dim.copy(alpha = 0.35f), from, via, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }

                drawZones(origin, c, pxPerM, open, selected)

                anims.forEach { a ->
                    val from = pos[a.hop.fromNodeId] ?: return@forEach
                    val to = pos[a.hop.toNodeId] ?: return@forEach
                    val t = ((frame - a.startMs).toFloat() / HOP_MS).coerceIn(0f, 1f)
                    val head = from + (to - from) * t
                    drawLine(Px.Text.copy(alpha = 0.35f * (1f - t)), from, head, 2.dp.toPx())
                    drawCircle(Px.Text, 3.dp.toPx(), head)
                }

                peers.forEach { drawPhone(it.tier, pos.getValue(it.nodeId)) }

                // This post: a filled square, labelled in words.
                val s = 7.dp.toPx()
                drawRect(Px.Text, c - Offset(s, s), Size(2 * s, 2 * s))
                drawText(measurer, "This post", c + Offset(s + 6.dp.toPx(), -s - 2.dp.toPx()), grotesk(13, FontWeight.Bold, Px.Text))

                drawPins(origin, c, pxPerM, open, board, measurer)
                drawScale(pxPerM, measurer)
            }
            if (origin == null) {
                Text(
                    "Waiting for this tablet's location to draw the plan.",
                    style = grotesk(16, FontWeight.Medium, Px.Dim),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (peers.isNotEmpty()) Px.Green else Px.Amber))
            Spacer(Modifier.width(10.dp))
            Text(
                buildString {
                    append(if (peers.isEmpty()) "Live mesh: looking for phones." else "Live mesh: ${peers.size} phones, $direct linked directly.")
                    if (lastHopMs > 0) append(" Last relay ${secondsLabel(now - lastHopMs)} ago.")
                },
                style = figures(15, FontWeight.Medium, Px.Dim),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Square: phone that runs AI and relays. Circle: phone. Hatching: where reports came from.",
            style = grotesk(13, FontWeight.Medium, Px.Dim),
        )
    }
}

/** Phones: square = runs AI + relays, circle = phone, diamond = another post. Outline only, so pins stay the loudest marks. */
private fun DrawScope.drawPhone(tier: DeviceTier, at: Offset) {
    val r = 5.dp.toPx()
    val stroke = Stroke(1.5.dp.toPx())
    when (tier) {
        DeviceTier.TIER1_COMPUTE -> {
            drawRect(Px.Black, at - Offset(r, r), Size(2 * r, 2 * r))
            drawRect(Px.Text, at - Offset(r, r), Size(2 * r, 2 * r), style = stroke)
        }
        DeviceTier.TIER2_SCOUT -> {
            drawCircle(Px.Black, r, at)
            drawCircle(Px.Text, r, at, style = stroke)
        }
        DeviceTier.COMMAND_CENTER -> {
            val d = Path().apply {
                moveTo(at.x, at.y - r * 1.3f); lineTo(at.x + r * 1.3f, at.y); lineTo(at.x, at.y + r * 1.3f); lineTo(at.x - r * 1.3f, at.y); close()
            }
            drawPath(d, Px.Black)
            drawPath(d, Px.Text, style = stroke)
        }
    }
}

private fun cellOf(origin: GeoPoint, c: Offset, pxPerM: Float, key: Pair<Int, Int>): Rect {
    val originEast = Math.toRadians(origin.lon) * EARTH_R * cos(Math.toRadians(origin.lat))
    val originNorth = Math.toRadians(origin.lat) * EARTH_R
    val center = Offset(
        c.x + (key.first * CELL_M - originEast).toFloat() * pxPerM,
        c.y - (key.second * CELL_M - originNorth).toFloat() * pxPerM,
    )
    val half = CELL_M.toFloat() * pxPerM / 2
    return Rect(center.x - half, center.y - half, center.x + half, center.y + half)
}

/** Anonymous density: reports per 25 m cell (GeoMath.cellKey), drawn as plan hatching. Denser hatching = more reports. */
private fun DrawScope.drawZones(origin: GeoPoint, c: Offset, pxPerM: Float, open: List<IncidentRecord>, selected: String?) {
    open.filter { it.incident.location != null }
        .groupBy { GeoMath.cellKey(it.incident.location!!, CELL_M) }
        .forEach { (key, recs) ->
            val cell = cellOf(origin, c, pxPerM, key)
            val worst = recs.maxOf { it.incident.severity }.color()
            val reports = recs.sumOf { it.incident.reportCount }
            val gap = (9f - reports.coerceAtMost(4) * 1.5f).dp.toPx()
            clipRect(cell.left, cell.top, cell.right, cell.bottom) {
                var x = cell.left - cell.height
                while (x < cell.right) {
                    drawLine(worst.copy(alpha = 0.7f), Offset(x, cell.bottom), Offset(x + cell.height, cell.top), 1.2.dp.toPx())
                    x += gap
                }
            }
            val sel = recs.any { it.incident.id == selected }
            drawRect(worst, cell.topLeft, cell.size, style = Stroke(if (sel) 2.5.dp.toPx() else 1.dp.toPx()))
        }
}

private fun DrawScope.drawPins(origin: GeoPoint, c: Offset, pxPerM: Float, open: List<IncidentRecord>, board: Board, measurer: TextMeasurer) {
    open.filter { it.incident.location != null }
        .groupBy { GeoMath.cellKey(it.incident.location!!, CELL_M) }
        .forEach { (key, recs) ->
            val cell = cellOf(origin, c, pxPerM, key)
            recs.sortedByDescending { it.incident.severity }.forEachIndexed { n, r ->
                val at = Offset(cell.right + 4.dp.toPx() + n * 26.dp.toPx(), cell.top - 4.dp.toPx())
                val rad = 12.dp.toPx()
                drawCircle(Px.Black, rad + 2.dp.toPx(), at)
                drawCircle(r.incident.severity.color(), rad, at)
                val t = measurer.measure(board.letter(r.incident.id), figures(13, FontWeight.Bold, Px.Black))
                drawText(t, topLeft = at - Offset(t.size.width / 2f, t.size.height / 2f))
            }
        }
}

private fun DrawScope.drawScale(pxPerM: Float, measurer: TextMeasurer) {
    val len = 50f * pxPerM
    val y = size.height - 8.dp.toPx()
    drawLine(Px.Dim, Offset(0f, y), Offset(len, y), 1.5.dp.toPx())
    drawLine(Px.Dim, Offset(0f, y - 4.dp.toPx()), Offset(0f, y + 1.dp.toPx()), 1.5.dp.toPx())
    drawLine(Px.Dim, Offset(len, y - 4.dp.toPx()), Offset(len, y + 1.dp.toPx()), 1.5.dp.toPx())
    drawText(measurer, "50 m", Offset(len + 8.dp.toPx(), y - 10.dp.toPx()), grotesk(13, FontWeight.Medium, Px.Dim))
    // North: a plain letter over a short tick, top right.
    val n = Offset(size.width - 10.dp.toPx(), 4.dp.toPx())
    drawLine(Px.Dim, n + Offset(0f, 18.dp.toPx()), n + Offset(0f, 34.dp.toPx()), 1.5.dp.toPx())
    drawText(measurer, "N", n - Offset(4.dp.toPx(), 0f), grotesk(13, FontWeight.Bold, Px.Dim))
}
