package mu.proxima.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.distanceLabel
import mu.proxima.app.ui.label
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono
import mu.proxima.core.model.GeoMath
import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.HelpCategory
import mu.proxima.core.model.RankedHelpPoint

/** Beyond this, a walking arrow misleads (Ganga Talao: nearest clinic is ~8.5 km). Show the distance and the place. */
private const val WALKABLE_M = 2_000.0

/**
 * Nearest real help points from the Mauritius dataset (OSM). Clinical categories only, as chosen by the incident type.
 * Close by: an arrow that turns with the phone. Far away: no arrow, a plain statement of distance.
 */
@Composable
fun NearestHelp(graph: ProximaGraph, from: GeoPoint?, categories: Set<HelpCategory>, modifier: Modifier = Modifier) {
    var help by remember { mutableStateOf<List<RankedHelpPoint>?>(null) }
    LaunchedEffect(from, categories) {
        help = if (from == null || categories.isEmpty()) emptyList() else graph.helpPoints.nearest(from, categories, 2)
    }
    val heading by graph.locate.headingDeg.collectAsStateWithLifecycle()
    val list = help ?: return
    if (list.isEmpty()) return

    Column(modifier) {
        Text("Nearest help", style = grotesk(22))
        Spacer(Modifier.height(4.dp))
        list.forEach { HelpPointRow(it, heading) }
        if (list.first().distanceM > WALKABLE_M) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Help is ${distanceLabel(list.first().distanceM)} away. Tell people around you; your alert keeps spreading phone to phone.",
                style = grotesk(15, FontWeight.Medium, Px.Amber),
            )
        }
    }
}

@Composable
private fun HelpPointRow(h: RankedHelpPoint, heading: Float?) {
    val walkable = h.distanceM <= WALKABLE_M
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (walkable && heading != null) {
                val rel = GeoMath.relativeBearingDeg(h.bearingDeg, heading.toDouble()).toFloat()
                Canvas(Modifier.size(32.dp).semantics { contentDescription = "Direction to ${h.point.name}" }) {
                    rotate(rel) {
                        val w = size.width
                        val p = Path().apply {
                            moveTo(w / 2, 0f); lineTo(w * 0.85f, w * 0.75f); lineTo(w / 2, w * 0.58f); lineTo(w * 0.15f, w * 0.75f); close()
                        }
                        drawPath(p, Px.Text)
                    }
                }
            } else {
                GlyphIcon(glyphFor(h.point.category), Px.Dim, 26.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.point.name, style = grotesk(18, FontWeight.Bold))
            Text("${h.point.category.label()}, ${h.point.source}", style = grotesk(13, FontWeight.Medium, Px.Dim))
        }
        Text(distanceLabel(h.distanceM), style = mono(17, FontWeight.Bold, if (walkable) Px.Text else Px.Dim))
    }
}

private fun glyphFor(c: HelpCategory) = when (c) {
    HelpCategory.HOSPITAL, HelpCategory.HEALTH_CENTRE, HelpCategory.FIRST_AID_POST, HelpCategory.PHARMACY,
    HelpCategory.AMBULANCE, HelpCategory.DEFIBRILLATOR -> Glyph.Medical
    HelpCategory.FIRE_STATION -> Glyph.Hazard
    else -> Glyph.Person
}
