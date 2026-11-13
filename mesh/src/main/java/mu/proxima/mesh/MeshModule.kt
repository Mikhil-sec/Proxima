package mu.proxima.mesh

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.fake.FakeMeshEngine
import mu.proxima.core.model.DeviceCapabilities

/**
 * MESH lane entry point. The app calls ONLY this. Keep the signature stable;
 * replace the body with your Nearby Connections engine when `simulation == false`.
 */
object MeshModule {
    fun create(
        context: Context,
        capabilities: DeviceCapabilities,
        trust: TrustEngine,
        scope: CoroutineScope,
        simulation: Boolean,
    ): MeshEngine {
        if (!simulation) return NearbyMeshEngine(context, capabilities, trust, scope)
        return FakeMeshEngine(scope)
    }
}
