package mu.proxima.trust

import java.security.MessageDigest

/**
 * Derives a node's mesh id from its public key.
 *
 * Split out from `TinkTrustEngine` on purpose: this is plain SHA-256, no Tink, so MESH can rely on the exact
 * format (and this can be tested) before the keystore work lands. Requested in #19.
 *
 * A node id is **not** a chosen name - it is a function of the key, so a node cannot claim another node's id
 * without holding that node's key. Combined with a persisted keyset, the id is also stable across app restarts,
 * which is what the multi-hop allow-list and the Command Center need.
 */
object NodeIds {

    /** Characters of the hex digest kept. 8 hex chars = 32 bits, short enough to read off a screen. */
    const val LENGTH = 8

    /**
     * `first 8 lowercase hex chars of SHA-256(publicKey)`.
     *
     * [publicKey] is the combined `"<Ed25519 sig pub b64>.<X25519 enc pub b64>"` string that
     * `NodeIdentity.publicKey` carries, hashed as UTF-8 bytes.
     */
    fun derive(publicKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(LENGTH)
        // Only the first LENGTH/2 bytes are needed to produce LENGTH hex characters.
        for (i in 0 until LENGTH / 2) {
            val b = digest[i].toInt() and 0xFF
            out.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return out.toString()
    }

    /** Builds the combined public key string from its two halves, so the format lives in exactly one place. */
    fun publicKey(signingKeyB64: String, encryptionKeyB64: String): String {
        require(signingKeyB64.isNotBlank() && encryptionKeyB64.isNotBlank()) {
            "both key halves are required"
        }
        require(!signingKeyB64.contains(SEPARATOR) && !encryptionKeyB64.contains(SEPARATOR)) {
            "key halves must not contain '$SEPARATOR' - it separates them"
        }
        return signingKeyB64 + SEPARATOR + encryptionKeyB64
    }

    /** Splits a combined public key back into (signing, encryption), or null if it is not the expected shape. */
    fun split(publicKey: String): Pair<String, String>? {
        val parts = publicKey.split(SEPARATOR)
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return parts[0] to parts[1]
    }

    /** Base64 never contains '.', so it is unambiguous as a separator. */
    const val SEPARATOR = "."

    private val HEX = "0123456789abcdef".toCharArray()
}
