package mu.proxima.trust

import android.content.Context
import mu.proxima.core.engine.HelpPointRepository
import mu.proxima.core.engine.ProtocolRepository
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.fake.FakeTrustEngine

/**
 * TRUST lane entry point. The app calls ONLY this. Keep signatures stable.
 * Owns: keys/signing/sealing, wristband QR, and the real datasets (help points + protocol cards).
 */
object TrustModule {
    /**
     * Simulation OFF (the demo) = real Ed25519 signing + HPKE sealing, keys in the Android Keystore.
     * Simulation ON = `FakeTrustEngine`, which signs with "SIM" and base64s instead of encrypting - dev only.
     */
    fun createTrust(context: Context, simulation: Boolean): TrustEngine =
        // Fresh identity per app session, so a node id cannot follow one phone around (#38). The pipeline asks
        // trust.ownsKey / ownsNodeId, so a guardian's restart mid-search still receives the finder's notices.
        if (simulation) FakeTrustEngine() else TinkTrustEngine(context, KeyRotation.PER_SESSION)

    /**
     * Real OSM dataset, always - there is no simulated variant. 524 Mauritius help points parsed from
     * the raw Overpass extract committed in this module's assets. Loads lazily on first use.
     */
    fun createHelpPoints(context: Context): HelpPointRepository = AssetHelpPointRepository(context)

    /**
     * Vetted protocol cards, always real. Transcribed from the IFRC 2020 first aid guidelines with a
     * citation on each card; `bestFor` returns null for subtypes the source does not cover rather than
     * substituting an unrelated card.
     */
    fun createProtocols(context: Context): ProtocolRepository = AssetProtocolRepository(context)
}
