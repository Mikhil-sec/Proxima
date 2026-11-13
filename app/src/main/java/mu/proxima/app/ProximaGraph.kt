package mu.proxima.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.proxima.ai.AiModule
import mu.proxima.core.model.TriageCapability
import mu.proxima.core.pipeline.IncidentPipeline
import mu.proxima.locate.LocateModule
import mu.proxima.mesh.MeshBeaconInfo
import mu.proxima.mesh.MeshModule
import mu.proxima.trust.TrustModule

/**
 * Manual DI. Wires every lane's module factory together ONCE.
 * Lanes never edit this file — they swap implementations inside their own `*Module.create()`.
 */
class ProximaGraph(app: Application, val simulation: Boolean) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val capabilities = AndroidDeviceCapabilityProvider(app).detect()

    val trust = TrustModule.createTrust(app, simulation)
    val helpPoints = TrustModule.createHelpPoints(app)
    val protocols = TrustModule.createProtocols(app)

    val mesh = MeshModule.create(app, capabilities, trust, scope, simulation)
    val locate = LocateModule.create(app, capabilities, mesh, trust, scope, simulation)
    val triage = AiModule.createTriage(app, capabilities, mesh, trust, scope, simulation)
    val voice = AiModule.createVoice(app, simulation)

    val pipeline = IncidentPipeline(mesh, triage, trust, locate, protocols, scope)

    init {
        // What this phone's BEACON advertises (issue #28). The mesh snaps the location to its anonymous 25 m cell
        // before sending (MeshBeaconInfo.coarse); llmReady lets Tier 2 phones pick a peer to offload triage to.
        scope.launch { locate.selfLocation.collect { MeshBeaconInfo.location.value = it } }
        scope.launch { triage.capability.collect { MeshBeaconInfo.llmReady.value = it == TriageCapability.ON_DEVICE_LLM } }
    }

    /** Every runtime permission any engine needs — request these once from the UI. */
    val requiredPermissions: List<String>
        get() = (mesh.requiredPermissions + locate.requiredPermissions + voice.requiredPermissions).distinct()
}
