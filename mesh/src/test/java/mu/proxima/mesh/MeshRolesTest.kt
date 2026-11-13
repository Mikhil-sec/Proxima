package mu.proxima.mesh

import mu.proxima.core.model.Beacon
import mu.proxima.core.model.DeviceTier
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Role
import mu.proxima.core.model.RolePass
import mu.proxima.core.model.Severity
import mu.proxima.core.pipeline.ProximaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verified roles in beacons (#55, #69): a pass shows only when TRUST verifies it with the signed beacon packet. */
class MeshRolesTest {

    private val now = 1_000_000L
    private val pass = RolePass(Role.MEDIC, holderKey = "medic-key", label = "Medic tent 2", expiresAtMs = now + 3_600_000, organiserSig = "sig")
    private val beacon = Beacon("a1b2c3d4", DeviceTier.TIER1_COMPUTE, hasUwb = false, llmReady = false, role = pass)
    private val packet = MeshPacket("b1", PacketKind.BEACON, "a1b2c3d4", "medic-key", now, Severity.LOW, ttl = 3,
        payload = ProximaJson.encodeToString(Beacon.serializer(), beacon))

    private val trustSaysYes = { _: RolePass, _: MeshPacket, _: Long -> true }
    private val trustSaysNo = { _: RolePass, _: MeshPacket, _: Long -> false }

    @Test fun verifiedPassBecomesRoleAndPostLabel() {
        assertEquals(Role.MEDIC to "Medic tent 2", MeshBeaconInfo.verifiedRole(beacon, packet, now, trustSaysYes))
    }

    @Test fun anythingTrustRejectsIsNotShown() {
        assertNull(MeshBeaconInfo.verifiedRole(beacon, packet, now, trustSaysNo))                   // forged / replayed
        assertNull(MeshBeaconInfo.verifiedRole(beacon, packet, now) { _, _, _ -> error("crypto") }) // verifier crashed
        assertNull(MeshBeaconInfo.verifiedRole(beacon.copy(role = null), packet, now, trustSaysYes))
    }

    @Test fun expiredPassOrBeaconForAnotherNodeIsNotShownEvenIfTrustWouldSayYes() {
        assertNull(MeshBeaconInfo.verifiedRole(beacon.copy(role = pass.copy(expiresAtMs = now)), packet, now, trustSaysYes))
        // A signed beacon naming a different node must not lend it the role.
        assertNull(MeshBeaconInfo.verifiedRole(beacon.copy(nodeId = "ffff0000"), packet, now, trustSaysYes))
    }

    @Test fun relayedBeaconStillVerifies() {
        val relayed = MeshRelay.nextHop(packet.copy(path = listOf("a1b2c3d4")), "relay001")!!
        assertEquals(packet.signingBytes().toList(), relayed.signingBytes().toList()) // relays don't touch signed bytes
        assertEquals(Role.MEDIC to "Medic tent 2", MeshBeaconInfo.verifiedRole(beacon, relayed, now, trustSaysYes))
    }

    @Test fun ordinaryBeaconsAreUnchangedOnTheWireAndOldBuildsStillReadNewOnes() {
        val ordinary = ProximaJson.encodeToString(Beacon.serializer(), beacon.copy(role = null))
        assertFalse(ordinary.contains("role"))                                  // explicitNulls = false
        val withRole = ProximaJson.encodeToString(Beacon.serializer(), beacon)
        assertTrue(withRole.contains("Medic tent 2"))
        assertEquals(beacon, ProximaJson.decodeFromString(Beacon.serializer(), withRole))
        // A beacon from an older build (no role field) still decodes.
        val old = """{"nodeId":"a1b2c3d4","tier":"TIER2_SCOUT","hasUwb":false,"llmReady":true}"""
        assertNull(ProximaJson.decodeFromString(Beacon.serializer(), old).role)
    }
}
