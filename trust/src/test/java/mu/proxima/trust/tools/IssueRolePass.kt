package mu.proxima.trust.tools

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.signature.SignatureConfig
import mu.proxima.core.model.Role
import mu.proxima.core.model.RolePass
import mu.proxima.core.pipeline.ProximaJson
import mu.proxima.trust.RolePassIssuer
import mu.proxima.trust.RolePasses
import java.io.File

/**
 * Organiser's laptop: signs a verified-role pass for one role phone (#55). Run via
 * `./gradlew :trust:issueRolePass -Prole=MEDIC -Plabel="Medic tent 2" -Pholder=<holder_key.txt> [-Phours=12]`.
 * Lives in the test source set so it is never packaged into the app. See `RolePhone` for the full adb flow.
 *
 * args: privateKeyFile holderKeyFile role label hours outFile shippedPublicKeyFile
 */
fun main(args: Array<String>) {
    require(args.size == 7) { "usage: <organiser private keyset> <holder_key.txt> <MEDIC|STEWARD|POLICE> <label> <hours> <out> <shipped public key>" }
    val privateKey = File(args[0])
    val holderFile = File(args[1])
    val role = Role.valueOf(args[2].uppercase())
    val label = args[3]
    val hours = args[4].toLong()
    val out = File(args[5])
    check(privateKey.isFile) { "no organiser key at $privateKey - run :trust:generateOrganiserKey once" }
    check(holderFile.isFile) { "no holder key at $holderFile - adb pull it from the role phone first" }

    SignatureConfig.register()
    val issuer = RolePassIssuer(TinkJsonProtoKeysetFormat.parseKeyset(privateKey.readText(), InsecureSecretKeyAccess.get()))
    val pass: RolePass = issuer.issue(role, holderFile.readText().trim(), label, validForMs = hours * 60 * 60 * 1000)
    // Refuse to hand out a pass the app would reject: the laptop key must match the public key the app ships.
    val shipped = RolePasses.verifier(RolePasses.parseOrganiserKey(File(args[6]).readText()))
    check(RolePasses.signatureValid(shipped, pass)) {
        "the organiser key in ${privateKey.parent} does NOT match the public key shipped in the app - pass not written"
    }
    out.writeText(ProximaJson.encodeToString(RolePass.serializer(), pass))
    println("pass for ${pass.role} \"${pass.label}\" valid $hours h -> ${out.absolutePath}")
    println("next: adb push \"${out.absolutePath}\" /sdcard/Android/data/mu.proxima.app/files/role/ and restart the app")
}
