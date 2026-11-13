package mu.proxima.trust

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Role
import mu.proxima.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verified roles (#38): a medic tent shows as verified only with a real organiser pass, presented by its holder. */
class RolePassTest {

    private val organiser = newEd25519Keyset()
    private val organiserPublic = organiser.publicKeysetHandle
    private val issuer = RolePassIssuer(organiser, clock = { NOW })

    private val medicTent = SimulatedPhone(KeyRotation.STABLE, organiserPublic).boot()
    private val attendee = SimulatedPhone(KeyRotation.PER_SESSION, organiserPublic).boot()
    private val impostor = SimulatedPhone(KeyRotation.STABLE, organiserPublic).boot()

    private fun beaconFrom(engine: TinkTrustEngine) = engine.sign(
        MeshPacket(
            packetId = "b1", kind = PacketKind.BEACON, originNodeId = engine.identity.nodeId,
            originKey = engine.identity.publicKey, createdAtMs = NOW, severity = Severity.LOW, ttl = 1, payload = "{}",
        ),
    )

    private val pass = issuer.issue(Role.MEDIC, medicTent.identity.publicKey, "Medic tent 2", validForMs = HOUR)

    @Test
    fun a_pass_presented_by_its_holder_verifies() {
        assertTrue(attendee.verifyRoleClaim(pass, beaconFrom(medicTent), NOW))
    }

    @Test
    fun an_expired_pass_does_not_verify() {
        assertFalse(attendee.verifyRoleClaim(pass, beaconFrom(medicTent), NOW + HOUR))
    }

    /** The reason the claim needs a packet at all: a pass travels in the clear, so anyone can copy it. */
    @Test
    fun an_overheard_pass_replayed_by_another_phone_does_not_verify() {
        assertFalse(attendee.verifyRoleClaim(pass, beaconFrom(impostor), NOW))
        // Nor by pretending to be the holder without its key.
        val spoofed = beaconFrom(impostor).copy(originKey = medicTent.identity.publicKey, originNodeId = medicTent.identity.nodeId)
        assertFalse(attendee.verifyRoleClaim(pass, spoofed, NOW))
    }

    @Test
    fun a_pass_signed_by_anyone_but_the_organiser_does_not_verify() {
        val selfIssued = RolePassIssuer(newEd25519Keyset(), clock = { NOW })
            .issue(Role.MEDIC, impostor.identity.publicKey, "Medic tent 2", validForMs = HOUR)

        assertFalse(attendee.verifyRoleClaim(selfIssued, beaconFrom(impostor), NOW))
    }

    @Test
    fun a_pass_edited_after_signing_does_not_verify() {
        val beacon = beaconFrom(medicTent)

        assertFalse(attendee.verifyRoleClaim(pass.copy(role = Role.POLICE), beacon, NOW))
        assertFalse(attendee.verifyRoleClaim(pass.copy(label = "Command post"), beacon, NOW))
        assertFalse(attendee.verifyRoleClaim(pass.copy(expiresAtMs = pass.expiresAtMs + 10 * HOUR), beacon, NOW))
        assertFalse(attendee.verifyRoleClaim(pass.copy(organiserSig = "AAAA"), beacon, NOW))
    }

    @Test
    fun a_tampered_beacon_does_not_carry_the_role() {
        val beacon = beaconFrom(medicTent)

        assertFalse(attendee.verifyRoleClaim(pass, beacon.copy(payload = "{\"forged\":true}"), NOW))
    }

    /** A build that ships no organiser key, and the simulation fake, never show anyone as verified. */
    @Test
    fun without_the_organiser_key_nobody_is_verified() {
        val noKey = SimulatedPhone(KeyRotation.PER_SESSION, organiserKey = null).boot()

        assertFalse(noKey.verifyRoleClaim(pass, beaconFrom(medicTent), NOW))
        assertFalse(mu.proxima.core.fake.FakeTrustEngine().verifyRoleClaim(pass, beaconFrom(medicTent), NOW))
    }

    /** The asset path: the public key round-trips through JSON, and a keyset WITH secrets is refused. */
    @Test
    fun organiser_key_asset_holds_only_public_material() {
        val json = TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(organiserPublic)
        val parsed = RolePasses.parseOrganiserKey(json)
        val phone = SimulatedPhone(KeyRotation.PER_SESSION, parsed).boot()

        assertTrue(phone.verifyRoleClaim(pass, beaconFrom(medicTent), NOW))
        assertThrows(Exception::class.java) {
            RolePasses.parseOrganiserKey(
                TinkJsonProtoKeysetFormat.serializeKeyset(organiser, com.google.crypto.tink.InsecureSecretKeyAccess.get()),
            )
        }
    }

    /**
     * The organiser key that SHIPS in the app. parseKeysetWithoutSecret throws on any secret material, so this
     * fails the build if anyone ever commits the private key in its place.
     */
    @Test
    fun the_shipped_organiser_key_is_public_only() {
        val shipped = java.io.File("src/main/assets/${RolePasses.ORGANISER_PUBLIC_ASSET}").readText()

        RolePasses.parseOrganiserKey(shipped)
        assertFalse(shipped.contains("ASYMMETRIC_PRIVATE"))
    }

    @Test
    fun issuer_rejects_labels_that_could_name_or_confuse() {
        val key = medicTent.identity.publicKey

        assertThrows(IllegalArgumentException::class.java) { issuer.issue(Role.STEWARD, key, " ", HOUR) }
        assertThrows(IllegalArgumentException::class.java) { issuer.issue(Role.STEWARD, key, "Gate|POLICE", HOUR) }
        assertThrows(IllegalArgumentException::class.java) { issuer.issue(Role.STEWARD, key, "x".repeat(41), HOUR) }
        assertThrows(IllegalArgumentException::class.java) { issuer.issue(Role.STEWARD, "not-a-key", "Gate 3", HOUR) }
        assertEquals("Gate 3", issuer.issue(Role.STEWARD, key, "Gate 3", HOUR).label)
    }

    private companion object {
        init {
            com.google.crypto.tink.signature.SignatureConfig.register()
        }

        const val NOW = 1_763_000_000_000L
        const val HOUR = 60L * 60 * 1000

        fun newEd25519Keyset(): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
    }
}
