package mu.proxima.trust

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.WristbandTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the full [TrustEngineContractTest] against the real Tink crypto on the JVM, on every `./gradlew :trust:test`,
 * with per-session identity (#38) - the policy the app is moving to.
 *
 * Only storage is swapped: keysets live in memory instead of the Android Keystore. They are still serialized and
 * re-parsed on every load, so "same device restarting" genuinely reloads the keys rather than reusing an object.
 * The Keystore itself is covered by `TinkTrustEngineKeystoreTest` in `src/androidTest`.
 */
class TinkTrustEngineTest : TrustEngineContractTest() {

    private val thisDevice = SimulatedPhone(KeyRotation.PER_SESSION)
    private val otherDevice = SimulatedPhone(KeyRotation.PER_SESSION)

    override val rotatesPerSession = true
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
}

/** The same contract with a stable identity: what role phones use, and what the app runs until #38 lands. */
class TinkTrustEngineStableTest : TrustEngineContractTest() {

    private val thisDevice = SimulatedPhone(KeyRotation.STABLE)
    private val otherDevice = SimulatedPhone(KeyRotation.STABLE)

    override val rotatesPerSession = false
    override fun newEngine(): TrustEngine = thisDevice.boot()
    override fun newOtherDevice(): TrustEngine = otherDevice.boot()
}

/** What per-session identity adds beyond the contract: ownership across restarts, expiry, and cleanup. */
class TinkKeyRotationTest {

    private val phone = SimulatedPhone(KeyRotation.PER_SESSION)
    private val stranger = SimulatedPhone(KeyRotation.PER_SESSION).boot()

    @Test
    fun a_restarted_phone_still_owns_its_previous_key_and_node_id() {
        val before = phone.boot()
        val after = phone.boot()

        assertTrue(after.ownsKey(after.identity.publicKey))
        assertTrue("the guardian's earlier key must still count as mine", after.ownsKey(before.identity.publicKey))
        assertTrue(after.ownsNodeId(before.identity.nodeId))
        assertFalse(after.ownsKey(stranger.identity.publicKey))
        assertFalse(after.ownsNodeId(stranger.identity.nodeId))
    }

    /** One event day. After that, an old key is gone for good - nothing on the phone links the sessions. */
    @Test
    fun previous_sessions_expire_after_24_hours() {
        val before = phone.boot()
        val sealed = stranger.seal("x".toByteArray(), before.identity.publicKey)

        phone.nowMs += TinkTrustEngine.RETAIN_MS - 1
        assertEquals("x", phone.boot().open(sealed)?.decodeToString())

        phone.nowMs += 2
        val dayLater = phone.boot()
        assertNull("a key older than the retention window must be destroyed", dayLater.open(sealed))
        assertFalse(dayLater.ownsKey(before.identity.publicKey))
    }

    @Test
    fun at_most_ten_previous_sessions_are_kept() {
        repeat(TinkTrustEngine.MAX_RETIRED + 5) {
            phone.nowMs += 1
            phone.boot()
        }

        // MAX_RETIRED previous sessions + the current one, two keysets each.
        assertEquals((TinkTrustEngine.MAX_RETIRED + 1) * 2, phone.storedKeysets().size)
    }

    @Test
    fun a_stable_identity_left_by_an_older_build_is_destroyed() {
        val legacy = SimulatedPhone(KeyRotation.STABLE)
        legacy.boot()
        assertTrue(TinkTrustEngine.STABLE_SIGNING in legacy.storedKeysets())

        legacy.boot(KeyRotation.PER_SESSION)

        assertFalse(TinkTrustEngine.STABLE_SIGNING in legacy.storedKeysets())
        assertFalse(TinkTrustEngine.STABLE_ENCRYPTION in legacy.storedKeysets())
    }

    @Test
    fun a_half_written_session_is_cleaned_up_not_loaded() {
        phone.boot()
        val orphan = "s${phone.nowMs}_deadbeef_sig"
        phone.plant(orphan)

        phone.boot()

        assertFalse(orphan in phone.storedKeysets())
    }
}

/** One simulated phone: its persisted storage and clock outlive any single engine instance. */
internal class SimulatedPhone(private val rotation: KeyRotation) {
    private val keysets = mutableMapOf<String, ByteArray>()
    private var tags = emptyList<WristbandTag>()
    var nowMs = 1_763_000_000_000L

    fun storedKeysets(): Set<String> = keysets.keys.toSet()

    /** Writes a junk keyset under [name], as a crash between creating a session's two halves would. */
    fun plant(name: String) {
        keysets[name] = ByteArray(1)
    }

    fun boot(rotation: KeyRotation = this.rotation) = TinkTrustEngine(
        keys = object : KeysetStore {
            override fun getOrCreate(name: String, template: KeyTemplate): KeysetHandle {
                val stored = keysets.getOrPut(name) {
                    TinkProtoKeysetFormat.serializeKeyset(KeysetHandle.generateNew(template), InsecureSecretKeyAccess.get())
                }
                return TinkProtoKeysetFormat.parseKeyset(stored, InsecureSecretKeyAccess.get())
            }

            override fun names(): Set<String> = keysets.keys.toSet()

            override fun delete(name: String) {
                keysets.remove(name)
            }
        },
        wristbands = object : WristbandStore {
            override fun load() = tags
            override fun save(tags: List<WristbandTag>) {
                this@SimulatedPhone.tags = tags
            }
        },
        rotation = rotation,
        clock = { nowMs },
    )
}
