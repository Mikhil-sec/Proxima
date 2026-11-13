package mu.proxima.trust

import android.content.Context
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.NodeIdentity
import mu.proxima.core.model.WristbandTag
import java.security.SecureRandom
import java.util.Base64

/**
 * The real [TrustEngine]: Ed25519 signatures and HPKE (X25519 / HKDF-SHA256 / AES-256-GCM) sealing, via Tink.
 *
 * **Identity.** Each install holds two private keysets, one per purpose, persisted by [keys] (the Android
 * Keystore in the app). `identity.publicKey` is `"<signing public keyset b64>.<encryption public keyset b64>"`
 * built by [NodeIds.publicKey], and `identity.nodeId` is [NodeIds.derive] of that - so a node id is a function of
 * the key, not a chosen name, and is stable across restarts.
 *
 * **Verify** checks two things: the Ed25519 signature over `signingBytes()` against the *sender's* key in
 * `originKey`, and that `originNodeId` really is derived from that key. Without the second check a node could sign
 * correctly with its own key while claiming another node's id. `ttl`, `hopCount` and `path` stay outside the
 * signed bytes, so relays can forward without breaking it.
 *
 * **Seal** encrypts to the encryption half of the recipient's combined key; only the holder of the matching
 * private keyset can [open] it. Relays forward the ciphertext but cannot read it.
 *
 * Public halves are Tink public keysets serialized *without secret material* (binary proto, then base64), so a
 * peer's key is parsed by Tink itself rather than by hand-rolled byte handling.
 */
class TinkTrustEngine(
    keys: KeysetStore,
    private val wristbands: WristbandStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrustEngine {

    /** The app's constructor: keysets encrypted under an Android Keystore master key, tags in private prefs. */
    constructor(context: Context) : this(AndroidKeystoreKeysetStore(context), SharedPrefsWristbandStore(context))

    private val signingKeys: KeysetHandle = keys.getOrCreate(SIGNING_KEYSET, KeyTemplates.get(SIGNING_TEMPLATE))
    private val encryptionKeys: KeysetHandle = keys.getOrCreate(ENCRYPTION_KEYSET, KeyTemplates.get(ENCRYPTION_TEMPLATE))

    private val signer: PublicKeySign = signingKeys.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)
    private val decrypter: HybridDecrypt = encryptionKeys.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    override val identity: NodeIdentity = run {
        val publicKey = NodeIds.publicKey(exportPublic(signingKeys), exportPublic(encryptionKeys))
        NodeIdentity(nodeId = NodeIds.derive(publicKey), publicKey = publicKey)
    }

    /** Parsing a peer's keyset on every packet would be wasteful on a busy mesh; peers repeat, so cache. */
    private val verifiers = LruCache<String, PublicKeyVerify>(VERIFIER_CACHE_SIZE)
    private val minted = wristbands.load().toMutableList()
    private val random = SecureRandom()

    override fun sign(packet: MeshPacket): MeshPacket =
        packet.copy(signature = B64.encodeToString(signer.sign(packet.signingBytes())))

    override fun verify(packet: MeshPacket): Boolean {
        val signature = packet.signature?.takeIf { it.isNotBlank() } ?: return false
        if (packet.originNodeId != NodeIds.derive(packet.originKey)) return false
        return try {
            val verifier = verifiers.getOrPut(packet.originKey) {
                val (signingHalf, _) = NodeIds.split(packet.originKey) ?: return false
                importPublic(signingHalf).getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
            }
            verifier.verify(B64_DECODER.decode(signature), packet.signingBytes())
            true
        } catch (e: Exception) {
            // GeneralSecurityException (bad signature, wrong key type) or IllegalArgumentException (bad base64).
            false
        }
    }

    /**
     * @throws IllegalArgumentException if [recipientKey] is not a combined Proxima public key. Sealing a child's
     * location to a key we cannot parse must fail loudly, never fall back to sending it readable.
     */
    override fun seal(plaintext: ByteArray, recipientKey: String): String {
        val (_, encryptionHalf) = requireNotNull(NodeIds.split(recipientKey)) { "recipientKey is not '<sig>.<enc>'" }
        val encrypter = try {
            importPublic(encryptionHalf).getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("recipientKey does not hold a usable HPKE public key", e)
        }
        return B64.encodeToString(encrypter.encrypt(plaintext, SEAL_CONTEXT))
    }

    override fun open(sealedB64: String): ByteArray? = try {
        decrypter.decrypt(B64_DECODER.decode(sealedB64), SEAL_CONTEXT)
    } catch (e: Exception) {
        // Not addressed to us, tampered, truncated, or not base64 at all: all the same to the caller.
        null
    }

    override fun createWristband(): WristbandTag {
        val id = ByteArray(TAG_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val tag = WristbandTag(tagId = id, guardianKey = identity.publicKey, createdAtMs = clock())
        synchronized(minted) {
            minted += tag
            wristbands.save(minted.toList())
        }
        return tag
    }

    override fun myWristbands(): List<WristbandTag> = synchronized(minted) { minted.toList() }

    companion object {
        init {
            SignatureConfig.register()
            HybridConfig.register()
        }

        const val SIGNING_KEYSET = "proxima_signing"
        const val ENCRYPTION_KEYSET = "proxima_encryption"
        const val SIGNING_TEMPLATE = "ED25519"
        const val ENCRYPTION_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

        /**
         * HPKE context info. Binds every ciphertext to this protocol and version, so a sealed notice cannot be
         * replayed into some other use of the same key.
         */
        private val SEAL_CONTEXT = "proxima/sealed/v1".encodeToByteArray()

        private const val TAG_BYTES = 8
        private const val VERIFIER_CACHE_SIZE = 256

        /** Standard base64: never contains '.', which [NodeIds] uses as the separator between the two halves. */
        private val B64 = Base64.getEncoder()
        private val B64_DECODER = Base64.getDecoder()

        // Both TinkProtoKeysetFormat calls below are deprecated in Tink 1.24. Context7 documents these methods
        // but not their replacement, and CLAUDE.md section 3 forbids guessing crypto APIs, so they stay (with
        // their compiler warnings visible) until the replacement can be checked. Logged 13 Nov in TRUST.md.
        private fun exportPublic(privateKeys: KeysetHandle): String =
            B64.encodeToString(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(privateKeys.publicKeysetHandle))

        /** Rejects anything carrying secret material; Tink parses the keyset, not us. */
        private fun importPublic(b64: String): KeysetHandle =
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(B64_DECODER.decode(b64))
    }
}

/** Small synchronized LRU. `android.util.LruCache` would make this class untestable on the JVM. */
private class LruCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > max
    }

    inline fun getOrPut(key: K, create: () -> V): V {
        synchronized(map) { map[key]?.let { return it } }
        val value = create()
        synchronized(map) { map[key] = value }
        return value
    }
}
