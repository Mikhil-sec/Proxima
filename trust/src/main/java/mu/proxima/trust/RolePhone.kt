package mu.proxima.trust

import android.content.Context
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.RolePass
import mu.proxima.core.model.Severity
import mu.proxima.core.pipeline.ProximaJson
import java.io.File

/**
 * Role mode for a medic tent / steward phone (#55, #69). Driven entirely by adb, so no extra screens are needed and
 * the organiser key never leaves the organiser's laptop:
 *
 * 1. `adb shell mkdir -p /sdcard/Android/data/mu.proxima.app/files/role` and restart the app. The phone now keeps a
 *    **stable** identity (a pass binds to its key, so per-session keys would void it) and writes `holder_key.txt`.
 * 2. Laptop: `adb pull .../files/role/holder_key.txt`, then
 *    `./gradlew :trust:issueRolePass -Prole=MEDIC -Plabel="Medic tent 2" -Pholder=<path to holder_key.txt>`.
 * 3. `adb push ~/.proxima/pass.json /sdcard/Android/data/mu.proxima.app/files/role/` and restart:
 *    [loadPass] returns it, and the app puts it in every beacon.
 *
 * Remove the `role` folder to turn role mode off. A role phone is a post meant to be found, not a person, so a
 * stable identity there does not break "never track individuals".
 */
object RolePhone {
    const val DIR = "role"
    const val HOLDER_KEY_FILE = "holder_key.txt"
    const val PASS_FILE = "pass.json"

    private fun dir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, DIR) }

    /** True if this phone was put in role mode (the `role` folder exists). */
    fun enabled(context: Context): Boolean = dir(context)?.isDirectory == true

    /** Writes this phone's public key where `adb pull` can fetch it for the organiser to sign. */
    fun publishHolderKey(context: Context, trust: TrustEngine) {
        val dir = dir(context) ?: return
        File(dir, HOLDER_KEY_FILE).writeText(trust.identity.publicKey)
    }

    /**
     * The pushed pass, only if it is genuinely usable by THIS phone right now: organiser-signed, unexpired, and bound
     * to this phone's key. Checked through [TrustEngine.verifyRoleClaim] on a packet this phone signs itself, so it is
     * exactly the check every other phone will make on our beacons. Null otherwise (wrong phone, expired, forged).
     */
    fun loadPass(context: Context, trust: TrustEngine, nowMs: Long = System.currentTimeMillis()): RolePass? {
        val file = dir(context)?.let { File(it, PASS_FILE) }?.takeIf { it.isFile } ?: return null
        val pass = parse(file.readText()) ?: return null
        return pass.takeIf { usableBy(trust, it, nowMs) }
    }

    fun parse(json: String): RolePass? = runCatching { ProximaJson.decodeFromString(RolePass.serializer(), json) }.getOrNull()

    fun usableBy(trust: TrustEngine, pass: RolePass, nowMs: Long): Boolean {
        val probe = trust.sign(
            MeshPacket(
                packetId = "role-self-check", kind = PacketKind.BEACON, originNodeId = trust.identity.nodeId,
                originKey = trust.identity.publicKey, createdAtMs = nowMs, severity = Severity.LOW, ttl = 0, payload = "",
            ),
        )
        return trust.verifyRoleClaim(pass, probe, nowMs)
    }
}
