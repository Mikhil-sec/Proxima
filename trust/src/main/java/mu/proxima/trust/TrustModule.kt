package mu.proxima.trust

import android.content.Context
import mu.proxima.core.engine.HelpPointRepository
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.fake.EmptyHelpPointRepository
import mu.proxima.core.fake.EmptyProtocolRepository
import mu.proxima.core.fake.FakeTrustEngine

/**
 * TRUST lane entry point. The app calls ONLY this. Keep signatures stable.
 * Owns: keys/signing/sealing, wristband QR, and the real datasets (help points + protocol cards).
 */
object TrustModule {
    fun createTrust(context: Context, simulation: Boolean): TrustEngine {
        // TODO(TRUST): if (!simulation) return TinkTrustEngine(context)
        return FakeTrustEngine()
    }

    fun createHelpPoints(context: Context): HelpPointRepository {
        // TODO(TRUST): AssetHelpPointRepository(context, "data/help_points_mu.json")
        return EmptyHelpPointRepository()
    }

    fun createProtocols(context: Context): ProtocolRepository {
        // TODO(TRUST): AssetProtocolRepository(context, "data/protocols.json")
        return EmptyProtocolRepository()
    }
}
