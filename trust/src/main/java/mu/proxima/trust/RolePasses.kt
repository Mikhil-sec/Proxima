package mu.proxima.trust

import android.content.Context
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import mu.proxima.core.model.Role
import mu.proxima.core.model.RolePass
import java.util.Base64

/**
 * Verified roles (#38): the event organiser signs a [RolePass] for a medic tent or steward post. Every phone ships
 * the organiser's PUBLIC key (asset, safe to commit); only the organiser phone holds the private key, and it is
 * never committed (CLAUDE.md section 9).
 */
object RolePasses {

    /** Bundled organiser public keyset (Tink JSON, no secret material). Absent = nobody can be verified. */
    const val ORGANISER_PUBLIC_ASSET = "data/organiser_public.json"

    const val MAX_LABEL = 40

    /** The organiser's public key from assets, or null if this build ships none. */
    fun loadOrganiserKey(context: Context): KeysetHandle? = try {
        context.assets.open(ORGANISER_PUBLIC_ASSET).bufferedReader().use { parseOrganiserKey(it.readText()) }
    } catch (e: java.io.IOException) {
        null
    }

    /** Parses a public keyset; rejects anything carrying secret material. */
    fun parseOrganiserKey(json: String): KeysetHandle = TinkJsonProtoKeysetFormat.parseKeysetWithoutSecret(json)

    internal fun verifier(organiserKey: KeysetHandle): PublicKeyVerify =
        organiserKey.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)

    /** True if [pass] carries a valid organiser signature. Says nothing about who is presenting it. */
    internal fun signatureValid(verifier: PublicKeyVerify, pass: RolePass): Boolean = try {
        verifier.verify(Base64.getDecoder().decode(pass.organiserSig), pass.signingBytes())
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Organiser phone only: issues passes with the organiser's private keyset.
 *
 * @param organiserPrivate the organiser's private Ed25519 keyset. Loaded from a file pushed with `adb`, never
 * from the repo.
 */
class RolePassIssuer(organiserPrivate: KeysetHandle, private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = organiserPrivate.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)

    /**
     * @param label a post name ("Medic tent 2"), never a person's name; at most [RolePasses.MAX_LABEL] characters.
     * @throws IllegalArgumentException for a blank or over-long label, a label containing '|', or a holder key that
     * is not a Proxima public key.
     */
    fun issue(role: Role, holderKey: String, label: String, validForMs: Long): RolePass {
        require(label.isNotBlank() && label.length <= RolePasses.MAX_LABEL) { "label must be 1-${RolePasses.MAX_LABEL} chars" }
        require('|' !in label) { "label must not contain '|'" }
        require(NodeIds.split(holderKey) != null) { "holderKey is not a Proxima public key" }
        require(validForMs > 0) { "validForMs must be positive" }
        val unsigned = RolePass(role, holderKey, label.trim(), clock() + validForMs, organiserSig = "")
        return unsigned.copy(organiserSig = Base64.getEncoder().encodeToString(signer.sign(unsigned.signingBytes())))
    }
}
