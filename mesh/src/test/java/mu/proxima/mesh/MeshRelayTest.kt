package mu.proxima.mesh

import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRelayTest {

    private fun packet(ttl: Int = 5) = MeshPacket(
        packetId = "p1", kind = PacketKind.INCIDENT, originNodeId = "A", originKey = "kA",
        createdAtMs = 1L, severity = Severity.MEDIUM, ttl = ttl, payload = "{}", signature = "sig",
    )

    @Test fun chainAtoBtoC() {
        val sent = MeshRelay.originate(packet(), "A")
        assertEquals(listOf("A"), sent.path)
        assertEquals(0, sent.hopCount)

        val atB = MeshRelay.nextHop(sent, "B")!!
        assertEquals(listOf("A", "B"), atB.path)
        assertEquals(1, atB.hopCount)
        assertEquals(4, atB.ttl)
        assertEquals("B", MeshRelay.lastHop(atB))
    }

    @Test fun relayKeepsSignedFieldsIntact() {
        val sent = MeshRelay.originate(packet(), "A")
        val atB = MeshRelay.nextHop(sent, "B")!!
        assertTrue(sent.signingBytes().contentEquals(atB.signingBytes()))
        assertEquals("sig", atB.signature)
    }

    @Test fun ttlOneStops() {
        assertNull(MeshRelay.nextHop(packet(ttl = 1), "B"))
    }

    @Test fun ownPacketIsNotRelayed() {
        assertNull(MeshRelay.nextHop(MeshRelay.originate(packet(), "A"), "A"))
    }

    @Test fun neverSendBackAlongPath() {
        val atB = MeshRelay.nextHop(MeshRelay.originate(packet(), "A"), "B")!!
        assertFalse(MeshRelay.shouldSendTo(atB, "A"))
        assertFalse(MeshRelay.shouldSendTo(atB, "B"))
        assertTrue(MeshRelay.shouldSendTo(atB, "C"))
    }
}
