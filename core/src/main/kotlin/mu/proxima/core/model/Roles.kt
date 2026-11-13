package mu.proxima.core.model

import kotlinx.serialization.Serializable

/** A responder role the event organiser can vouch for (#38). */
@Serializable
enum class Role { MEDIC, STEWARD, POLICE }

/**
 * The organiser's signed statement "the phone holding [holderKey] is [role] at post [label] until [expiresAtMs]".
 *
 * Not secret: it travels in the clear (e.g. in the holder's beacon). It proves nothing on its own - anyone can
 * replay an overheard pass - which is why it is only ever checked together with a packet signed by [holderKey],
 * via `TrustEngine.verifyRoleClaim`.
 *
 * [label] names a post ("Medic tent 2"), never a person.
 */
@Serializable
data class RolePass(
    val role: Role,
    /** The role phone's `NodeIdentity.publicKey`. Role phones keep a stable identity so this stays valid. */
    val holderKey: String,
    val label: String,
    val expiresAtMs: Long,
    /** Base64 Ed25519 signature by the organiser over [signingBytes]. */
    val organiserSig: String,
) {
    /** Bytes the organiser signs. Versioned and domain-separated so a pass signature is never valid as anything else. */
    fun signingBytes(): ByteArray =
        listOf("proxima-rolepass-v1", role.name, holderKey, label, expiresAtMs.toString())
            .joinToString("|")
            .encodeToByteArray()
}
