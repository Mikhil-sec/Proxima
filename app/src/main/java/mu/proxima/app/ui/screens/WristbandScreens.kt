package mu.proxima.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mu.proxima.app.ProximaGraph
import mu.proxima.app.ui.Nav
import mu.proxima.app.ui.ageLabel
import mu.proxima.app.ui.components.BackButton
import mu.proxima.app.ui.components.Glyph
import mu.proxima.app.ui.components.GlyphIcon
import mu.proxima.app.ui.components.PrimaryAction
import mu.proxima.app.ui.theme.Px
import mu.proxima.app.ui.theme.grotesk
import mu.proxima.app.ui.theme.mono

/** Guardian: pair wristbands to this phone. The QR holds a random tag and this phone's key, nothing personal. */
@Composable
fun WristbandsScreen(graph: ProximaGraph, nav: Nav) {
    var tags by remember { mutableStateOf(graph.trust.myWristbands()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { BackButton(nav::back) }
        Text("My wristbands", style = grotesk(30))
        Spacer(Modifier.height(6.dp))
        Text(
            "Put one on your child. If they get lost, whoever finds them scans it and only this phone is told where they are.",
            style = grotesk(16, FontWeight.Medium, Px.Dim),
        )
        Spacer(Modifier.height(24.dp))
        if (tags.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlyphIcon(Glyph.Wristband, Px.Dim, 56.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("No wristbands paired yet.", style = grotesk(18, FontWeight.Medium, Px.Dim))
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(tags, key = { it.tagId }) { tag ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Wristband, Px.Green, 28.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Wristband ${tag.tagId.take(4).uppercase()}", style = grotesk(18, FontWeight.Bold))
                            Text("Paired to this phone", style = grotesk(14, FontWeight.Medium, Px.Green))
                        }
                        Text(ageLabel(tag.createdAtMs), style = mono(13))
                    }
                }
            }
        }
        PrimaryAction("Pair a new wristband", { graph.trust.createWristband(); tags = graph.trust.myWristbands() }, glyph = Glyph.Wristband)
        Spacer(Modifier.height(16.dp))
    }
}

/** Finder: one job, scan the wristband. The camera scanner composable comes from the TRUST lane. */
@Composable
fun FinderScreen(graph: ProximaGraph, nav: Nav) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { BackButton(nav::back) }
        Text("Found someone?", style = grotesk(30))
        Spacer(Modifier.height(6.dp))
        Text("Stay with them. Scan the code on their wristband and their guardian is told where you are.", style = grotesk(17, FontWeight.Medium, Px.Dim))
        Spacer(Modifier.height(28.dp))
        // Viewfinder frame; TRUST's WristbandScanner drops in here.
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val l = size.minDimension * 0.16f
                val w = 4.dp.toPx()
                listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)).forEach { o ->
                    val dx = if (o.x == 0f) l else -l
                    val dy = if (o.y == 0f) l else -l
                    drawLine(Px.Text, o, o + Offset(dx, 0f), w)
                    drawLine(Px.Text, o, o + Offset(0f, dy), w)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon(Glyph.Scan, Px.Dim, 40.dp)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (graph.simulation) "Camera scanner not connected yet" else "Starting camera",
                    style = grotesk(15, FontWeight.Medium, Px.Dim),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text("No wristband? Go back and use Lost person to describe them.", style = grotesk(15, FontWeight.Medium, Px.Dim))
        Spacer(Modifier.height(16.dp))
    }
}
