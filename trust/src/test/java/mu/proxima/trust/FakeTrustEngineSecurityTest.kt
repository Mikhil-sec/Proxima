package mu.proxima.trust

import mu.proxima.core.fake.FakeTrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins, in code, exactly how `FakeTrustEngine` is **not** secure - the finding reported in #19.
 *
 * These tests pass today and are meant to: (1) stop anyone assuming simulation-OFF already gives real
 * signing and sealing, since `TrustModule.createTrust` currently returns the fake either way, and (2) fail
 * loudly once `TinkTrustEngine` is wired in, which is the signal to delete this file and run
 * [TrustEngineContractTest] against the real engine instead.
 *
 * It is deliberately a test rather than a comment: a comment would not have shown up in anyone's build.
 */
class FakeTrustEngineSecurityTest {

    private val engine = FakeTrustEngine()

    private fun packet(payload: String = "incident-json", signature: String? = null) = MeshPacket(
        packetId = "p1",
        kind = PacketKind.INCIDENT,
        originNodeId = engine.identity.nodeId,
        originKey = engine.identity.publicKey,
        createdAtMs = 1_763_000_000_000L,
        severity = Severity.CRITICAL,
        ttl = 10,
        payload = payload,
        signature = signature,
    )

    /** The real engine must reject a forged signature. The fake accepts any non-null string. */
    @Test
    fun `fake verify accepts a signature it never produced`() {
        assertTrue(engine.verify(packet(signature = "SIM")))
        assertTrue("the fake accepts arbitrary signatures", engine.verify(packet(signature = "not-a-signature")))
        assertTrue(engine.verify(packet(signature = "")))
    }

    /** The real engine must reject a packet whose covered bytes changed after signing. */
    @Test
    fun `fake verify does not notice a tampered payload`() {
        val signed = engine.sign(packet())
        val tampered = signed.copy(payload = "all clear, stand down")

        assertNotEquals(signed.payload, tampered.payload)
        assertTrue("the fake cannot detect tampering", engine.verify(tampered))
    }

    /**
     * The important one. `seal()` must produce ciphertext a relay cannot read. The fake base64-encodes the
     * plaintext, so a child's location is readable by every relay that forwards it.
     */
    @Test
    fun `fake seal is base64, not encryption - the plaintext is recoverable`() {
        val secret = "child at -20.4181,57.4914"
        val sealed = engine.seal(secret.toByteArray(), engine.identity.publicKey)

        assertTrue("expected the SIM marker, got: $sealed", sealed.startsWith("SIMSEALED|"))

        // Anyone relaying this can recover the plaintext with no key at all.
        val body = sealed.substringAfterLast("|")
        val recovered = String(java.util.Base64.getDecoder().decode(body))
        assertEquals("the fake offers no confidentiality whatsoever", secret, recovered)
    }

    /** The real nodeId is derived from the key and persisted. The fake randomises it every construction. */
    @Test
    fun `fake nodeId is random per instance and is not derived from the key`() {
        val a = FakeTrustEngine().identity
        val b = FakeTrustEngine().identity

        assertNotEquals("restarting the app changes the fake's identity", a.nodeId, b.nodeId)
        assertNotEquals(NodeIds.derive(a.publicKey), a.nodeId)
        assertTrue("fake ids look like SELF-xxxx, not 8 hex chars", a.nodeId.startsWith("SELF-"))
    }
}
