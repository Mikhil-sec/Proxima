package mu.proxima.trust

import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security properties any **real** [TrustEngine] must satisfy. Written before `TinkTrustEngine` existed, on
 * purpose: this is the spec, so the implementation cannot quietly ship without one of these.
 *
 * Lives in `src/testFixtures` (shared by unit and instrumented tests) so it runs twice:
 *  - `TinkTrustEngineTest` (JVM, `src/test`) - in-memory keysets, runs on every `./gradlew :trust:test`.
 *  - `TinkTrustEngineKeystoreTest` (`src/androidTest`) - the real Android Keystore, needs a phone.
 *
 * `FakeTrustEngine` deliberately does NOT get a subclass: it fails most of these.
 *
 * Method names use underscores, not backtick sentences: this class is dexed for the instrumented run, and D8
 * rejects spaces in method names below DEX 040 (minSdk 26 here).
 */
abstract class TrustEngineContractTest {

    /** A fresh engine for THIS device. Two calls must behave like the same phone restarting: same identity. */
    protected abstract fun newEngine(): TrustEngine

    /** An engine for a DIFFERENT device: its own keys, its own identity. */
    protected abstract fun newOtherDevice(): TrustEngine

    private fun packet(engine: TrustEngine, payload: String = "incident-json") = MeshPacket(
        packetId = "p1",
        kind = PacketKind.INCIDENT,
        originNodeId = engine.identity.nodeId,
        originKey = engine.identity.publicKey,
        createdAtMs = 1_763_000_000_000L,
        severity = Severity.CRITICAL,
        ttl = 10,
        payload = payload,
    )

    // ---------- identity ----------

    @Test
    fun nodeId_is_derived_from_the_public_key() {
        val engine = newEngine()

        assertEquals(NodeIds.derive(engine.identity.publicKey), engine.identity.nodeId)
        assertTrue(engine.identity.nodeId.matches(Regex("[0-9a-f]{8}")))
    }

    /** `publicKey` must carry both halves, so peers can verify AND seal to this node. */
    @Test
    fun public_key_carries_both_the_signing_and_encryption_keys() {
        val halves = NodeIds.split(newEngine().identity.publicKey)

        assertTrue("publicKey must be '<sig>.<enc>'", halves != null)
        assertTrue(halves!!.first.isNotBlank() && halves.second.isNotBlank())
        assertNotEquals("the two halves must be different keys", halves.first, halves.second)
    }

    /** True if every [newEngine] is a new session with a new identity (#38); false if identity is stable (#19). */
    protected abstract val rotatesPerSession: Boolean

    @Test
    fun identity_across_a_restart_follows_the_rotation_policy() {
        val first = newEngine().identity
        val second = newEngine().identity

        if (rotatesPerSession) {
            // Unlinkable: neither half of the key, nor the id, carries over to the next session.
            val (sig1, enc1) = NodeIds.split(first.publicKey)!!
            val (sig2, enc2) = NodeIds.split(second.publicKey)!!
            assertNotEquals(sig1, sig2)
            assertNotEquals(enc1, enc2)
            assertNotEquals(first.nodeId, second.nodeId)
        } else {
            assertEquals(first.publicKey, second.publicKey)
            assertEquals(first.nodeId, second.nodeId)
        }
    }

    /** A guardian's app restarting mid-search must not lose the finder's notices, whatever the rotation policy. */
    @Test
    fun a_notice_sealed_to_me_before_a_restart_still_opens_after_it() {
        val before = newEngine()
        val sealed = newOtherDevice().seal("child at -20.4181,57.4914".toByteArray(), before.identity.publicKey)

        val after = newEngine()

        assertEquals("child at -20.4181,57.4914", after.open(sealed)?.decodeToString())
    }

    @Test
    fun two_devices_get_different_identities() {
        assertNotEquals(newEngine().identity.publicKey, newOtherDevice().identity.publicKey)
        assertNotEquals(newEngine().identity.nodeId, newOtherDevice().identity.nodeId)
    }

    // ---------- signing ----------

    @Test
    fun a_signed_packet_verifies() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))

        assertTrue("sign() must attach a signature", signed.signature != null)
        assertNotEquals("SIM", signed.signature)
        assertTrue(engine.verify(signed))
    }

    /** What the mesh actually does: a packet signed on one phone is checked on another. */
    @Test
    fun another_device_verifies_my_signature_from_the_packet_alone() {
        val me = newEngine()
        val signed = me.sign(packet(me))

        assertTrue(newOtherDevice().verify(signed))
    }

    @Test
    fun verify_rejects_an_unsigned_packet() {
        val engine = newEngine()

        assertFalse(engine.verify(packet(engine)))
        assertFalse(engine.verify(packet(engine).copy(signature = "")))
        assertFalse(engine.verify(packet(engine).copy(signature = "forged")))
    }

    /** A relay must not be able to change what an alert says and still have it verify. */
    @Test
    fun verify_rejects_a_packet_tampered_with_after_signing() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))

        assertFalse(engine.verify(signed.copy(payload = "all clear, stand down")))
        assertFalse(engine.verify(signed.copy(severity = Severity.LOW)))
        assertFalse(engine.verify(signed.copy(packetId = "p2")))
    }

    /** Relaying must stay possible: ttl, hopCount and path are outside the signed bytes. */
    @Test
    fun verify_still_passes_after_a_legitimate_relay() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))
        val relayed = signed.copy(ttl = signed.ttl - 1, hopCount = signed.hopCount + 1, path = listOf("abc12345"))

        assertTrue("relays must be able to forward without breaking the signature", engine.verify(relayed))
    }

    /**
     * Impersonation: an attacker signs validly with their OWN key but claims my node id. The signature is
     * genuine, so only the nodeId-is-derived-from-key check stops this.
     */
    @Test
    fun verify_rejects_a_valid_signature_that_claims_someone_else_s_node_id() {
        val victim = newEngine()
        val attacker = newOtherDevice()
        val forged = attacker.sign(packet(attacker).copy(originNodeId = victim.identity.nodeId))

        assertFalse(victim.verify(forged))
        assertFalse(attacker.verify(forged))
    }

    /** Key substitution: claim my key and id, but sign with a different key. */
    @Test
    fun verify_rejects_a_packet_that_claims_my_key_but_was_signed_by_another() {
        val victim = newEngine()
        val attacker = newOtherDevice()
        val forged = attacker.sign(packet(victim))

        assertFalse(victim.verify(forged))
        assertFalse(attacker.verify(forged))
    }

    @Test
    fun verify_rejects_a_malformed_origin_key_instead_of_throwing() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))
        val garbageKey = "not.a-key"

        assertFalse(engine.verify(signed.copy(originKey = garbageKey, originNodeId = NodeIds.derive(garbageKey))))
        assertFalse(engine.verify(signed.copy(originKey = "nodot", originNodeId = NodeIds.derive("nodot"))))
    }

    // ---------- sealing ----------

    @Test
    fun sealed_data_round_trips_for_the_intended_recipient() {
        val engine = newEngine()
        val secret = "child at -20.4181,57.4914".toByteArray()

        val sealed = engine.seal(secret, engine.identity.publicKey)
        assertEquals(secret.toList(), engine.open(sealed)?.toList())
    }

    /** The real flow: a finder seals to the guardian's key, and only the guardian's phone opens it. */
    @Test
    fun a_finder_seals_to_the_guardian_and_the_guardian_opens_it() {
        val guardian = newEngine()
        val finder = newOtherDevice()
        val secret = "child at -20.4181,57.4914".toByteArray()

        val sealed = finder.seal(secret, guardian.identity.publicKey)

        assertEquals(secret.toList(), guardian.open(sealed)?.toList())
        assertNull("the finder (or any relay) must not be able to read it back", finder.open(sealed))
    }

    /** The headline privacy claim: what a relay carries does not contain the plaintext in any obvious encoding. */
    @Test
    fun sealed_data_is_not_readable_by_another_node() {
        val guardian = newEngine()
        val relay = newOtherDevice()
        val plain = "child at -20.4181,57.4914"
        val sealed = guardian.seal(plain.toByteArray(), guardian.identity.publicKey)

        assertNull(relay.open(sealed))
        assertFalse("ciphertext leaks the plaintext", sealed.contains(plain))
        assertFalse(
            "ciphertext leaks base64 of the plaintext",
            sealed.contains(java.util.Base64.getEncoder().encodeToString(plain.toByteArray())),
        )
    }

    /** Fresh randomness per seal, so a relay cannot tell that two notices carry the same location. */
    @Test
    fun sealing_the_same_thing_twice_gives_different_ciphertexts() {
        val engine = newEngine()
        val secret = "child at -20.4181,57.4914".toByteArray()

        assertNotEquals(engine.seal(secret, engine.identity.publicKey), engine.seal(secret, engine.identity.publicKey))
    }

    @Test
    fun open_rejects_a_notice_sealed_to_someone_else_tampered_or_garbage() {
        val engine = newEngine()
        val other = newOtherDevice()

        assertNull(engine.open("not-base64-at-all"))
        assertNull(engine.open(""))
        assertNull("must not open a notice addressed to another node", engine.open(engine.seal("x".toByteArray(), other.identity.publicKey)))

        val sealed = engine.seal("child at -20.4181,57.4914".toByteArray(), engine.identity.publicKey)
        val bytes = java.util.Base64.getDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull("a flipped bit must be detected, not decrypted to garbage", engine.open(java.util.Base64.getEncoder().encodeToString(bytes)))
    }

    /** Sealing a child's location to a key we cannot parse must fail loudly, never fall back to plaintext. */
    @Test
    fun seal_refuses_a_recipient_key_it_cannot_use() {
        val engine = newEngine()

        assertThrows(IllegalArgumentException::class.java) { engine.seal("x".toByteArray(), "SIMKEY-1234") }
        assertThrows(IllegalArgumentException::class.java) {
            engine.seal("x".toByteArray(), NodeIds.publicKey("AAAAnotOurSigningKeyAAAA", "AAAAnotOurEncryptionKeyAA"))
        }
    }

    // ---------- wristbands ----------

    @Test
    fun wristband_tags_are_minted_against_this_node_and_remembered() {
        val engine = newEngine()
        val before = engine.myWristbands().size

        val tag = engine.createWristband()

        assertEquals(engine.identity.publicKey, tag.guardianKey)
        assertTrue(tag.tagId.isNotBlank())
        assertEquals(before + 1, engine.myWristbands().size)
        assertTrue(engine.myWristbands().any { it.tagId == tag.tagId })
    }

    @Test
    fun minted_wristbands_survive_a_restart() {
        val tag = newEngine().createWristband()

        assertTrue(newEngine().myWristbands().any { it.tagId == tag.tagId })
    }
}
