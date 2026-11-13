package mu.proxima.trust.tools

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.signature.SignatureConfig
import java.io.File

/**
 * One-off: generates the event organiser's Ed25519 keypair for verified roles (#38).
 * Run with `./gradlew :trust:generateOrganiserKey`. Not a test; it lives in the test source set only so that
 * it is never packaged into the app.
 *
 * - PUBLIC keyset -> args[0], inside the repo (`trust/src/main/assets/data/organiser_public.json`). Safe to commit.
 * - PRIVATE keyset -> args[1], OUTSIDE the repo (default `~/.proxima/organiser_private.json`), so no `git add`
 *   can ever pick it up. CLAUDE.md section 9: it is pushed to the organiser phone with adb and never committed.
 *
 * Refuses to overwrite an existing private key: replacing it would silently invalidate every pass already issued.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <public-out> <private-out>" }
    val publicOut = File(args[0])
    val privateOut = File(args[1])
    check(!privateOut.exists()) { "$privateOut already exists - refusing to replace the organiser key" }

    SignatureConfig.register()
    val organiser = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))

    privateOut.parentFile?.mkdirs()
    privateOut.writeText(TinkJsonProtoKeysetFormat.serializeKeyset(organiser, InsecureSecretKeyAccess.get()))
    publicOut.parentFile?.mkdirs()
    publicOut.writeText(TinkJsonProtoKeysetFormat.serializeKeysetWithoutSecret(organiser.publicKeysetHandle))

    println("organiser PUBLIC key  -> ${publicOut.absolutePath} (commit this)")
    println("organiser PRIVATE key -> ${privateOut.absolutePath} (NEVER commit; adb push to the organiser phone only)")
}
