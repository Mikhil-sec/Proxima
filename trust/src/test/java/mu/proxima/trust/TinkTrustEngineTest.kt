package mu.proxima.trust

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.WristbandTag
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the full [TrustEngineContractTest] against the real Tink crypto on the JVM, on every `./gradlew :trust:test`.
 *
 * Only storage is swapped: keysets live in memory instead of the Android Keystore. They are still serialized and
 * re-parsed on every load, so "same device restarting" genuinely reloads the keys rather than reusing an object.
 * The Keystore itself is covered by `TinkTrustEngineKeystoreTest` in `src/androidTest`.
 */
class TinkTrustEngineTest : TrustEngineContractTest() {

    private val thisDevice = Device()
    private val otherDevice = Device()

    override fun newEngine(): TrustEngine = thisDevice.boot()
    override fun newOtherDevice(): TrustEngine = otherDevice.boot()

    /**
     * `publicKey` rides in every packet's `originKey` (and `recipientKey` on SEALED). MESH budgets for this size,
     * so a template change that bloats it should fail here, not as a dropped Nearby payload mid-demo.
     */
    @Test
    fun public_key_stays_small_enough_to_ride_in_every_packet() {
        val length = newEngine().identity.publicKey.length

        // Measured 317 chars on 13 Nov (two Tink public keysets, base64). 400 leaves headroom, nothing more.
        assertTrue("publicKey is $length chars, MESH was told <= 400", length in 1..400)
    }

    /** One simulated phone: its persisted storage outlives any single engine instance. */
    private class Device {
        private val keysets = mutableMapOf<String, ByteArray>()
        private var tags = emptyList<WristbandTag>()

        fun boot(): TrustEngine = TinkTrustEngine(
            keys = object : KeysetStore {
                override fun getOrCreate(name: String, template: KeyTemplate): KeysetHandle {
                    val stored = keysets.getOrPut(name) {
                        TinkProtoKeysetFormat.serializeKeyset(KeysetHandle.generateNew(template), InsecureSecretKeyAccess.get())
                    }
                    return TinkProtoKeysetFormat.parseKeyset(stored, InsecureSecretKeyAccess.get())
                }
            },
            wristbands = object : WristbandStore {
                override fun load() = tags
                override fun save(tags: List<WristbandTag>) {
                    this@Device.tags = tags
                }
            },
        )
    }
}
