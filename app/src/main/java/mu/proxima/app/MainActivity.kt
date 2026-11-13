package mu.proxima.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * UI lane: this is a bare wiring check, NOT the design. Replace with the real
 * design system + screens (see docs/lanes/UI.md).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ProximaApp).graph
        setContent { WiringCheck(graph) }
    }
}

@Composable
private fun WiringCheck(graph: ProximaGraph) {
    LaunchedEffect(Unit) {
        graph.pipeline.start()
        graph.mesh.start()
        graph.locate.start()
    }
    val peers by graph.mesh.peers.collectAsStateWithLifecycle()
    val incidents by graph.pipeline.incidents.collectAsStateWithLifecycle()
    Column(Modifier.padding(24.dp)) {
        Text("Proxima wiring check — sim=${graph.simulation}")
        Text("tier=${graph.capabilities.tier} uwb=${graph.capabilities.hasUwb} ram=${"%.1f".format(graph.capabilities.totalRamGb)}GB")
        Text("peers=${peers.size} incidents=${incidents.size}")
        incidents.take(5).forEach { Text("• ${it.incident.type} ${it.incident.severity}: ${it.incident.summary}") }
    }
}
