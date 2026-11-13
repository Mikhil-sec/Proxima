package mu.proxima.locate

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import mu.proxima.core.engine.LocateEngine
import mu.proxima.core.engine.MeshEngine
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.fake.FakeLocateEngine
import mu.proxima.core.model.DeviceCapabilities

/**
 * LOCATE lane entry point. The app calls ONLY this. Keep the signature stable.
 * Target: GPS + compass on every phone, UWB precision when both phones support it, RSSI fallback.
 */
object LocateModule {
    fun create(
        context: Context,
        capabilities: DeviceCapabilities,
        mesh: MeshEngine,
        trust: TrustEngine,
        scope: CoroutineScope,
        simulation: Boolean,
    ): LocateEngine {
        // TODO(LOCATE): if (!simulation) return AndroidLocateEngine(context, capabilities, mesh, trust, scope)
        return FakeLocateEngine(scope)
    }
}
