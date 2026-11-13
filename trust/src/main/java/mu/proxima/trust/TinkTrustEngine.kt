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

/** How long a node keeps one identity. */
enum class KeyRotation {
    /** One identity for the life of the install. For role phones (a medic tent is a post meant to be found). */
    STABLE,

    /**
     * A fresh identity every app session, so a node id cannot be used to follow one phone across sessions (#38).
     * Previous sessions' keys are kept for [TinkTrustEngine.RETAIN_MS] so a notice sealed to the guardian before a
     * restart can still be opened after it.
     */
    PER_SESSION,
}

/**
 * The real [TrustEngine]: Ed25519 signatures and HPKE (X25519 / HKDF-SHA256 / AES-256-GCM) sealing, via Tink.
 *
 * **Identity.** A session holds two private keysets, one per purpose, persisted by [keys] (the Android Keystore
 * in the app). `identity.publicKey` is `"<signing public keyset b64>.<encryption public keyset b64>"` built by
 * [NodeIds.publicKey], and `identity.nodeId` is [NodeIds.derive] of that - so a node id is a function of the key,
 * not a chosen name. With [KeyRotation.PER_SESSION] each construction is a new session with a new identity.
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
    rotation: KeyRotation = KeyRotation.PER_SESSION,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrustEngine {

    /** The app's constructor: keysets encrypted under an Android Keystore master key, tags in private prefs. */
    constructor(context: Context, rotation: KeyRotation = KeyRotation.PER_SESSION) :
        this(AndroidKeystoreKeysetStore(context), SharedPrefsWristbandStore(context), rotation)

    private val random = SecureRandom()

    /** This session's keys, and (PER_SESSION only) the previous sessions' keys still inside the retention window. */
    private val current: SessionKeys
    private val retired: List<SessionKeys>

    init {
        when (rotation) {
            KeyRotation.STABLE -> {
                current = SessionKeys.load(keys, STABLE_SIGNING, STABLE_ENCRYPTION)
                retired = emptyList()
            }
            KeyRotation.PER_SESSION -> {
                val now = clock()
                // A stable keyset left over from an earlier build would be a long-lived identity: destroy it.
                keys.delete(STABLE_SIGNING)
                keys.delete(STABLE_ENCRYPTION)
                val (keep, drop) = StoredSession.all(keys.names())
                    .sortedByDescending { it.startedAtMs }
                    .partition { it.complete && now - it.startedAtMs <= RETAIN_MS }
                    .let { (fresh, stale) -> fresh.take(MAX_RETIRED) to (stale + fresh.drop(MAX_RETIRED)) }
                drop.forEach { session -> session.names.forEach(keys::delete) }
                retired = keep.map { SessionKeys.load(keys, it.signingName, it.encryptionName) }

                val id = "$SESSION_PREFIX${now}_${hex(SESSION_ID_BYTES)}"
                current = SessionKeys.load(keys, "${id}$SIGNING_SUFFIX", "${id}$ENCRYPTION_SUFFIX")
            }
        }
    }

    private val signer: PublicKeySign =
        current.signing.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)

    override val identity: NodeIdentity = NodeIdentity(nodeId = current.nodeId, publicKey = current.publicKey)

    /** Parsing a peer's keyset on every packet would be wasteful on a busy mesh; peers repeat, so cache. */
    private val verifiers = LruCache<String, PublicKeyVerify>(VERIFIER_CACHE_SIZE)
    private val minted = wristbands.load().toMutableList()

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

    /** Tries this session's key, then each retained previous session's, so a restart does not lose a notice. */
    override fun open(sealedB64: String): ByteArray? {
        val ciphertext = try {
            B64_DECODER.decode(sealedB64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        for (session in listOf(current) + retired) {
            try {
                return session.decrypter.decrypt(ciphertext, SEAL_CONTEXT)
            } catch (e: Exception) {
                // Not this session's key, or tampered. Try the next; null if none can open it.
            }
        }
        return null
    }

    /**
     * True if [publicKey] is this node's current key or a previous session's still-retained key - i.e. "was this
     * addressed to me?" in a way that survives a restart (#38).
     */
    override fun ownsKey(publicKey: String): Boolean = publicKey == current.publicKey || retired.any { it.publicKey == publicKey }

    /** Same as [ownsKey] for node ids, so "is this lost report mine?" survives a restart. */
    override fun ownsNodeId(nodeId: String): Boolean = nodeId == current.nodeId || retired.any { it.nodeId == nodeId }

    override fun createWristband(): WristbandTag {
        val tag = WristbandTag(tagId = hex(TAG_BYTES), guardianKey = identity.publicKey, createdAtMs = clock())
        synchronized(minted) {
            minted += tag
            wristbands.save(minted.toList())
        }
        return tag
    }

    override fun myWristbands(): List<WristbandTag> = synchronized(minted) { minted.toList() }

    private fun hex(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** One session's two private keysets plus everything derived from them. */
    private class SessionKeys(val signing: KeysetHandle, encryption: KeysetHandle) {
        val publicKey: String = NodeIds.publicKey(exportPublic(signing), exportPublic(encryption))
        val nodeId: String = NodeIds.derive(publicKey)
        val decrypter: HybridDecrypt = encryption.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

        companion object {
            fun load(keys: KeysetStore, signingName: String, encryptionName: String) = SessionKeys(
                keys.getOrCreate(signingName, KeyTemplates.get(SIGNING_TEMPLATE)),
                keys.getOrCreate(encryptionName, KeyTemplates.get(ENCRYPTION_TEMPLATE)),
            )
        }
    }

    /** A previous session found in storage by its keyset names: `s<startedAtMs>_<random>_sig` / `_enc`. */
    private data class StoredSession(val id: String, val startedAtMs: Long, val names: List<String>) {
        val signingName get() = "$id$SIGNING_SUFFIX"
        val encryptionName get() = "$id$ENCRYPTION_SUFFIX"

        /** A crash between creating the two halves leaves one behind; such a session is unusable. */
        val complete get() = signingName in names && encryptionName in names

        companion object {
            private val NAME = Regex("^($SESSION_PREFIX(\\d+)_[0-9a-f]+)($SIGNING_SUFFIX|$ENCRYPTION_SUFFIX)$")

            fun all(stored: Set<String>): List<StoredSession> = stored
                .mapNotNull { name -> NAME.matchEntire(name)?.let { it.groupValues[1] to name } }
                .groupBy({ it.first }, { it.second })
                .map { (id, names) -> StoredSession(id, NAME.matchEntire(names.first())!!.groupValues[2].toLong(), names) }
        }
    }

    companion object {
        init {
            SignatureConfig.register()
            HybridConfig.register()
        }

        /** How long a previous session's keys can still open notices: one event day. */
        const val RETAIN_MS = 24L * 60 * 60 * 1000

        /** Cap on retained sessions, so a phone restarted all day does not try dozens of keys per notice. */
        const val MAX_RETIRED = 10

        const val STABLE_SIGNING = "proxima_signing"
        const val STABLE_ENCRYPTION = "proxima_encryption"
        const val SIGNING_TEMPLATE = "ED25519"
        const val ENCRYPTION_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

        private const val SESSION_PREFIX = "s"
        private const val SIGNING_SUFFIX = "_sig"
        private const val ENCRYPTION_SUFFIX = "_enc"
        private const val SESSION_ID_BYTES = 4

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
