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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.clockLabel
import mu.proxima.app.ui.components.Hairline
import mu.proxima.app.ui.components.Rail
import mu.proxima.app.ui.components.SimBadge
import mu.proxima.app.ui.components.rememberReducedMotion
import mu.proxima.app.ui.label
import mu.proxima.app.ui.shortNode
import mu.proxima.app.ui.incidentTitle
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.color
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HopEvent
import mu.proxima.core.model.IncidentRecord
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.Peer
import mu.proxima.core.model.TriageSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val CELL_M = 25.0
private const val EARTH_R = 6_371_000.0
private const val HOP_MS = 900L

private data class HopAnim(val hop: HopEvent, val startMs: Long)

/** Venue-local metres east/north of [origin]. Good enough at festival scale. */
private fun local(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
    val east = Math.toRadians(p.lon - origin.lon) * EARTH_R * cos(Math.toRadians(origin.lat))
    val north = Math.toRadians(p.lat - origin.lat) * EARTH_R
    return east to north
}

/**
 * Command Center (tablet). The terminal register: dense, mono, live.
 * Left: tactical map (help-desk grid, anonymous incident heat cells, mesh topology, live packet hops).
 * Right: clock, counts, the merged incident list.
 */
@Composable
fun CommandCenterScreen(graph: ProximaGraph, onDev: () -> Unit) {
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    val me by graph.locate.selfLocation.collectAsStateWithLifecycle()
    val now = rememberNow()
    val reducedMotion = rememberReducedMotion()
    var selected by remember { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<String?>(null) }

    // Live hops: animated on the map, and the last few kept as a log line.
    val anims = remember { mutableStateListOf<HopAnim>() }
    val log = remember { mutableStateListOf<HopEvent>() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        graph.mesh.hops.collect { hop ->
            if (!reducedMotion) anims.add(HopAnim(hop, System.currentTimeMillis()))
            log.add(0, hop)
            if (log.size > 5) log.removeAt(log.lastIndex)
        }
    }
    val animating = anims.isNotEmpty()
    LaunchedEffect(animating) {
        while (anims.isNotEmpty()) {
            withFrameMillis { frame = System.currentTimeMillis() }
            anims.removeAll { frame - it.startMs > HOP_MS }
        }
    }

    val open = incidents.filter { !it.resolved && it.incident.type != IncidentType.ALL_CLEAR }
        .sortedWith(compareByDescending<IncidentRecord> { it.incident.severity }.thenByDescending { it.lastUpdatedMs })

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val map: @Composable (Modifier) -> Unit = { m ->
            TacticalMap(graph.mesh.selfNodeId, me, peers, open, anims, frame, selected, log, m)
        }
        val panel: @Composable (Modifier) -> Unit = { m ->
            SidePanel(graph, peers, open, now, selected, onDev, onSelect = { id ->
                if (selected == id) sheetFor = id else selected = id
            }, m)
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(0.64f).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Px.Line))
                panel(Modifier.weight(0.36f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.weight(0.55f).fillMaxWidth())
                Hairline()
                panel(Modifier.weight(0.45f).fillMaxWidth())
            }
        }
    }

    val sheet = incidents.firstOrNull { it.incident.id == sheetFor }
    if (sheet != null) IncidentSheet(graph, sheet, me, onDismiss = { sheetFor = null })
}

@Composable
private fun TacticalMap(
    selfId: String,
    me: GeoPoint?,
    peers: List<Peer>,
    open: List<IncidentRecord>,
    anims: List<HopAnim>,
    frame: Long,
    selected: String?,
    log: List<HopEvent>,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val origin = me ?: peers.firstNotNullOfOrNull { it.location } ?: open.firstNotNullOfOrNull { it.incident.location }

    Box(modifier.background(Px.Black)) {
        Canvas(
            Modifier.fillMaxSize().semantics {
                contentDescription = "Venue map: ${peers.size} phones, ${open.size} open incidents"
            },
        ) {
            if (origin == null) return@Canvas
            val pts = peers.mapNotNull { it.location } + open.mapNotNull { it.incident.location }
            val rangeM = max(80.0, pts.maxOfOrNull { val (e, n) = local(origin, it); max(abs(e), abs(n)) } ?: 0.0) * 1.2
            val pxPerM = (size.minDimension / 2f) / rangeM.toFloat()
            val c = Offset(size.width / 2, size.height / 2)
            fun toPx(p: GeoPoint): Offset { val (e, n) = local(origin, p); return Offset(c.x + e.toFloat() * pxPerM, c.y - n.toFloat() * pxPerM) }

            drawGrid(c, pxPerM)
            drawHeat(origin, c, pxPerM, open, selected)

            // Node positions. Peers without a location sit on an outer ring, spaced by id.
            val pos = HashMap<String, Offset>()
            pos[selfId] = c
            peers.forEachIndexed { i, p ->
                pos[p.nodeId] = p.location?.let(::toPx) ?: run {
                    val a = i * 2.4f
                    Offset(c.x + cos(a) * size.minDimension * 0.42f, c.y + sin(a) * size.minDimension * 0.42f)
                }
            }

            // Links: solid to direct peers, dashed from indirect peers to their nearest direct peer.
            val direct = peers.filter { it.direct }
            direct.forEach { p -> drawLine(Px.Green.copy(alpha = 0.35f), c, pos.getValue(p.nodeId), 1.5.dp.toPx()) }
            peers.filter { !it.direct }.forEach { p ->
                val from = pos.getValue(p.nodeId)
                val via = direct.minByOrNull { (pos.getValue(it.nodeId) - from).getDistance() }?.let { pos.getValue(it.nodeId) } ?: c
                drawLine(Px.Dim.copy(alpha = 0.5f), from, via, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }

            // Packet pulses travelling along links.
            anims.forEach { a ->
                val from = pos[a.hop.fromNodeId] ?: return@forEach
                val to = pos[a.hop.toNodeId] ?: return@forEach
                val t = ((frame - a.startMs).toFloat() / HOP_MS).coerceIn(0f, 1f)
                val head = from + (to - from) * t
                val col = a.hop.severity.color()
                drawLine(col.copy(alpha = 0.5f * (1f - t)), from, head, 2.5.dp.toPx())
                drawCircle(col.copy(alpha = 0.25f), 9.dp.toPx(), head)
                drawCircle(col, 4.dp.toPx(), head)
            }

            peers.forEach { p -> drawNode(p.tier, pos.getValue(p.nodeId), p.shortCode, measurer) }
            // Self: the reference point of the whole map.
            drawCircle(Px.Green.copy(alpha = 0.18f), 14.dp.toPx(), c)
            drawCircle(Px.Green, 5.dp.toPx(), c)
            drawText(measurer, "you", c + Offset(10.dp.toPx(), 6.dp.toPx()), mono(11, FontWeight.Bold, Px.Green))

            drawScaleAndNorth(pxPerM, measurer)
        }

        if (origin == null) {
            Text(
                "Waiting for a location fix to draw the map.",
                style = grotesk(16, FontWeight.Medium, Px.Dim),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Hop log: the last few link crossings, newest on top.
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            log.forEachIndexed { i, h ->
                Text(
                    "${clockLabel(h.atMs)}  ${shortNode(h.fromNodeId)} > ${shortNode(h.toNodeId)}  ${h.kind.name.lowercase()}",
                    style = mono(11, color = if (i == 0) Px.Text else Px.Dim),
                )
            }
        }
    }
}

private fun DrawScope.drawGrid(c: Offset, pxPerM: Float) {
    val step = CELL_M.toFloat() * pxPerM
    if (step < 6f) return
    var k = 0
    var x = c.x % step
    while (x < size.width) {
        drawLine(Px.Line.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), 1f); x += step; k++
    }
    var y = c.y % step
    while (y < size.height) {
        drawLine(Px.Line.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1f); y += step
    }
    // 100 m range ring: the only circle on the map, so distance reads at a glance.
    drawCircle(Px.Line, 100f * pxPerM, c, style = Stroke(1.dp.toPx()))
}

/** Anonymous density: incidents counted per 25 m cell (GeoMath.cellKey), never raw positions of people. */
private fun DrawScope.drawHeat(origin: GeoPoint, c: Offset, pxPerM: Float, open: List<IncidentRecord>, selected: String?) {
    val originEast = Math.toRadians(origin.lon) * EARTH_R * cos(Math.toRadians(origin.lat))
    val originNorth = Math.toRadians(origin.lat) * EARTH_R
    val cellPx = CELL_M.toFloat() * pxPerM
    open.filter { it.incident.location != null }
        .groupBy { GeoMath.cellKey(it.incident.location!!, CELL_M) }
        .forEach { (key, recs) ->
            val east = key.first * CELL_M - originEast
            val north = key.second * CELL_M - originNorth
            val center = Offset(c.x + east.toFloat() * pxPerM, c.y - north.toFloat() * pxPerM)
            val worst = recs.maxOf { it.incident.severity }
            val weight = recs.sumOf { it.incident.reportCount }
            val topLeft = center - Offset(cellPx / 2, cellPx / 2)
            drawRect(worst.color().copy(alpha = (0.18f + 0.1f * weight).coerceAtMost(0.6f)), topLeft, Size(cellPx, cellPx))
            if (recs.any { it.incident.id == selected }) {
                drawRect(Px.Text, topLeft, Size(cellPx, cellPx), style = Stroke(2.dp.toPx()))
            }
        }
}

/** Node shape encodes tier: square = relay with AI, circle = scout, diamond = command center. */
private fun DrawScope.drawNode(tier: DeviceTier, at: Offset, code: String, measurer: TextMeasurer) {
    val r = 6.dp.toPx()
    val stroke = Stroke(2.dp.toPx())
    when (tier) {
        DeviceTier.TIER1_COMPUTE -> {
            drawRect(Px.Black, at - Offset(r, r), Size(2 * r, 2 * r))
            drawRect(Px.Green, at - Offset(r, r), Size(2 * r, 2 * r), style = stroke)
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
            drawPath(d, Px.Amber, style = stroke)
        }
    }
    drawText(measurer, code, at + Offset(r + 5.dp.toPx(), -r - 2.dp.toPx()), mono(11, color = Px.Dim))
}

private fun DrawScope.drawScaleAndNorth(pxPerM: Float, measurer: TextMeasurer) {
    val pad = 20.dp.toPx()
    // North arrow, top right.
    val n = Offset(size.width - pad - 8.dp.toPx(), pad + 14.dp.toPx())
    val arrow = Path().apply {
        moveTo(n.x, n.y - 12.dp.toPx()); lineTo(n.x + 6.dp.toPx(), n.y + 6.dp.toPx())
        lineTo(n.x, n.y + 2.dp.toPx()); lineTo(n.x - 6.dp.toPx(), n.y + 6.dp.toPx()); close()
    }
    drawPath(arrow, Px.Text)
    drawText(measurer, "N", n + Offset(-4.dp.toPx(), 10.dp.toPx()), mono(11, FontWeight.Bold, Px.Text))
    // 50 m scale bar, top left (the bottom left belongs to the hop log).
    val len = 50f * pxPerM
    val y = pad + 10.dp.toPx()
    drawLine(Px.Text, Offset(pad, y), Offset(pad + len, y), 2.dp.toPx())
    drawLine(Px.Text, Offset(pad, y - 4.dp.toPx()), Offset(pad, y + 4.dp.toPx()), 2.dp.toPx())
    drawLine(Px.Text, Offset(pad + len, y - 4.dp.toPx()), Offset(pad + len, y + 4.dp.toPx()), 2.dp.toPx())
    drawText(measurer, "50 m", Offset(pad, y + 8.dp.toPx()), mono(11, color = Px.Text))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidePanel(
    graph: ProximaGraph,
    peers: List<Peer>,
    open: List<IncidentRecord>,
    now: Long,
    selected: String?,
    onDev: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    val critical = open.count { it.incident.severity == mu.proxima.core.model.Severity.CRITICAL }
    val verified = if (open.isEmpty()) null else open.count { it.signatureValid } * 100 / open.size
    val bySource = open.groupingBy { it.incident.triagedBy }.eachCount()

    Column(modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Proxima command",
                style = grotesk(20),
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDev, onLongClickLabel = "Developer settings"),
            )
            Spacer(Modifier.weight(1f))
            if (graph.simulation) SimBadge()
        }
        Text(clockLabel(now), style = mono(34, FontWeight.Bold, Px.Text))
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            Stat("open", open.size.toString(), Px.Text, Modifier.weight(1f))
            Stat("critical", critical.toString(), if (critical > 0) Px.Crimson else Px.Text, Modifier.weight(1f))
            Stat("phones", "${peers.count { it.direct }}/${peers.size}", if (peers.isEmpty()) Px.Amber else Px.Green, Modifier.weight(1f))
            Stat("verified", verified?.let { "$it%" } ?: "-", if ((verified ?: 100) < 100) Px.Amber else Px.Text, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("ai on device", (bySource[TriageSource.ON_DEVICE_LLM] ?: 0).toString(), Px.Text, Modifier.weight(1f), small = true)
            Stat("ai offloaded", (bySource[TriageSource.OFFLOADED_LLM] ?: 0).toString(), Px.Text, Modifier.weight(1f), small = true)
            Stat("rules", (bySource[TriageSource.RULES] ?: 0).toString(), Px.Text, Modifier.weight(1f), small = true)
        }
        Spacer(Modifier.height(16.dp))
        Hairline()

        if (open.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("No open incidents. New reports from the mesh appear here as they arrive.", style = grotesk(15, FontWeight.Medium, Px.Dim))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(open, key = { it.incident.id }) { r ->
                    CompactRow(r, now, r.incident.id == selected, Modifier.animateItem()) { onSelect(r.incident.id) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier, small: Boolean = false) {
    Column(modifier) {
        Text(value, style = mono(if (small) 18 else 28, FontWeight.Bold, color))
        Text(label, style = mono(11))
    }
}

/** Dense row: time, severity rail, what, where it came from. Tap once to locate on the map, again for details. */
@Composable
private fun CompactRow(r: IncidentRecord, now: Long, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val i = r.incident
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) Px.SurfaceHi else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Rail(i.severity.color(), Modifier.fillMaxHeight())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(incidentTitle(i.type, i.subtype), style = grotesk(16), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ageLabel(r.lastUpdatedMs, now), style = mono(12, color = Px.Text))
            }
            Text(i.summary, style = grotesk(13, FontWeight.Medium, Px.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (r.signatureValid) "signed" else "unsigned", style = mono(11, color = if (r.signatureValid) Px.Green else Px.Amber))
                Text("${r.hopsTravelled} hop", style = mono(11))
                if (i.reportCount > 1) Text("x${i.reportCount}", style = mono(11, color = Px.Text))
                Text(i.triagedBy.name.lowercase().replace('_', ' '), style = mono(11))
            }
        }
    }
}
