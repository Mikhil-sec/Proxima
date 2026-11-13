package mu.proxima.mesh

import mu.proxima.core.model.Beacon
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import mu.proxima.core.pipeline.ProximaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Friend find (#61): MESH carries the rotating tag and the signer key; the match happens only on the friend's phone. */
class MeshFriendsTest {

    private val now = 1_000_000L
    private val beacon = Beacon("a1b2c3d4", DeviceTier.TIER1_COMPUTE, hasUwb = false, llmReady = false, friendTag = "tag-w1")
    private val packet = MeshPacket("b1", PacketKind.BEACON, "a1b2c3d4", "peer-key", now, Severity.LOW, ttl = 3, payload = "{}")
    private val myFriends = { tag: String, _: Long -> if (tag == "tag-w1") "Mum" else null }

    @Test fun matchingTagGivesLocalLabelOtherwiseNothing() {
        assertEquals("Mum", MeshBeaconInfo.friendLabel(beacon, packet, now, myFriends))
        assertNull(MeshBeaconInfo.friendLabel(beacon.copy(friendTag = "tag-stranger"), packet, now, myFriends))
        assertNull(MeshBeaconInfo.friendLabel(beacon.copy(friendTag = null), packet, now, myFriends))
        assertNull(MeshBeaconInfo.friendLabel(beacon, packet, now) { _, _ -> error("keystore locked") })
    }

    @Test fun beaconNamingAnotherNodeGetsNoLabelAndNoKey() {
        val forged = beacon.copy(nodeId = "ffff0000")
        assertNull(MeshBeaconInfo.friendLabel(forged, packet, now, myFriends))
        assertNull(MeshBeaconInfo.signerKey(forged, packet))
        assertEquals("peer-key", MeshBeaconInfo.signerKey(beacon, packet))
        assertNull(MeshBeaconInfo.signerKey(beacon, packet.copy(originKey = "")))   // unsigned (acoustic) packets
    }

    @Test fun theLabelNeverGoesOnTheWire() {
        val json = ProximaJson.encodeToString(Beacon.serializer(), beacon)
        assertFalse(json.contains("Mum"))
        assertEquals("tag-w1", ProximaJson.decodeFromString(Beacon.serializer(), json).friendTag)
        assertFalse(ProximaJson.encodeToString(Beacon.serializer(), beacon.copy(friendTag = null)).contains("friendTag"))
    }
}
