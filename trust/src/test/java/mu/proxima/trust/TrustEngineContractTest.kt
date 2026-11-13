package mu.proxima.trust

import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security properties any **real** [TrustEngine] must satisfy. Written before `TinkTrustEngine` exists,
 * on purpose: this is the spec, so the implementation cannot quietly ship without one of these.
 *
 * Abstract and therefore not run on its own. When `TinkTrustEngine` lands, add:
 *
 * ```kotlin
 * class TinkTrustEngineTest : TrustEngineContractTest() {
 *     override fun newEngine() = TinkTrustEngine(ApplicationProvider.getApplicationContext())
 * }
 * ```
 *
 * It needs an Android `Context` for the Keystore, so that subclass is an **instrumented** test
 * (`src/androidTest`), not a JVM one. `FakeTrustEngine` deliberately does NOT get a subclass here - it fails
 * most of these, which is the point of [FakeTrustEngineSecurityTest].
 */
abstract class TrustEngineContractTest {

    /** A fresh engine. Two calls must behave like the same device restarting: same persisted identity. */
    protected abstract fun newEngine(): TrustEngine

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

    @Test
    fun `nodeId is derived from the public key`() {
        val engine = newEngine()

        assertEquals(NodeIds.derive(engine.identity.publicKey), engine.identity.nodeId)
        assertTrue(engine.identity.nodeId.matches(Regex("[0-9a-f]{8}")))
    }

    /** `publicKey` must carry both halves, so peers can verify AND seal to this node. */
    @Test
    fun `public key carries both the signing and encryption keys`() {
        val halves = NodeIds.split(newEngine().identity.publicKey)

        assertTrue("publicKey must be '<sig>.<enc>'", halves != null)
        assertTrue(halves!!.first.isNotBlank() && halves.second.isNotBlank())
        assertNotEquals("the two halves must be different keys", halves.first, halves.second)
    }

    /** The whole point of #19: the identity survives a restart. */
    @Test
    fun `identity is stable across engine instances`() {
        val first = newEngine().identity
        val second = newEngine().identity

        assertEquals(first.publicKey, second.publicKey)
        assertEquals(first.nodeId, second.nodeId)
    }

    @Test
    fun `a signed packet verifies`() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))

        assertTrue("sign() must attach a signature", signed.signature != null)
        assertNotEquals("SIM", signed.signature)
        assertTrue(engine.verify(signed))
    }

    @Test
    fun `verify rejects an unsigned packet`() {
        val engine = newEngine()

        assertFalse(engine.verify(packet(engine)))
        assertFalse(engine.verify(packet(engine).copy(signature = "")))
        assertFalse(engine.verify(packet(engine).copy(signature = "forged")))
    }

    /** A relay must not be able to change what an alert says and still have it verify. */
    @Test
    fun `verify rejects a packet tampered with after signing`() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))

        assertFalse(engine.verify(signed.copy(payload = "all clear, stand down")))
        assertFalse(engine.verify(signed.copy(severity = Severity.LOW)))
        assertFalse(engine.verify(signed.copy(packetId = "p2")))
    }

    /** Relaying must stay possible: ttl, hopCount and path are outside the signed bytes. */
    @Test
    fun `verify still passes after a legitimate relay`() {
        val engine = newEngine()
        val signed = engine.sign(packet(engine))
        val relayed = signed.copy(ttl = signed.ttl - 1, hopCount = signed.hopCount + 1, path = listOf("abc12345"))

        assertTrue("relays must be able to forward without breaking the signature", engine.verify(relayed))
    }

    @Test
    fun `sealed data round-trips for the intended recipient`() {
        val engine = newEngine()
        val secret = "child at -20.4181,57.4914".toByteArray()

        val sealed = engine.seal(secret, engine.identity.publicKey)
        assertEquals(secret.toList(), engine.open(sealed)?.toList())
    }

    /** The headline privacy claim: a relay carrying the notice must not be able to read it. */
    @Test
    fun `sealed data is not readable by another node`() {
        val guardian = newEngine()
        val sealed = guardian.seal("child at -20.4181,57.4914".toByteArray(), guardian.identity.publicKey)

        // The ciphertext must not simply contain the plaintext in any obvious encoding.
        val plain = "child at -20.4181,57.4914"
        assertFalse("ciphertext leaks the plaintext", sealed.contains(plain))
        assertFalse(
            "ciphertext leaks base64 of the plaintext",
            sealed.contains(java.util.Base64.getEncoder().encodeToString(plain.toByteArray())),
        )
    }

    @Test
    fun `open rejects a notice sealed to someone else, and garbage`() {
        val engine = newEngine()

        assertNull(engine.open("not-base64-at-all"))
        assertNull(engine.open(""))
        // Sealed to a key this node does not hold.
        val other = NodeIds.publicKey("AAAAnotOurSigningKeyAAAA", "AAAAnotOurEncryptionKeyAA")
        assertNull("must not open a notice addressed to another node", engine.open(engine.seal("x".toByteArray(), other)))
    }

    @Test
    fun `wristband tags are minted against this node and remembered`() {
        val engine = newEngine()
        val before = engine.myWristbands().size

        val tag = engine.createWristband()

        assertEquals(engine.identity.publicKey, tag.guardianKey)
        assertTrue(tag.tagId.isNotBlank())
        assertEquals(before + 1, engine.myWristbands().size)
        assertTrue(engine.myWristbands().any { it.tagId == tag.tagId })
    }
}
