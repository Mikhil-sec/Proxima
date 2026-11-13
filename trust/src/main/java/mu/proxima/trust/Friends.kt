package mu.proxima.trust

import android.content.Context
import com.google.crypto.tink.Aead
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Where the encrypted friend book lives. Holds only ciphertext; the key is in the Android Keystore. */
interface FriendStore {
    fun load(): String?
    fun save(blob: String)
}

class SharedPrefsFriendStore(context: Context, prefFile: String = "proxima_trust_friends") : FriendStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(blob: String) {
        prefs.edit().putString(KEY, blob).commit()
    }

    private companion object {
        const val KEY = "book"
    }
}

/**
 * Friend find (#38 item 3). Each phone has one random **beacon secret**. Its beacon carries
 * `HMAC-SHA256(secret, window)` truncated, where a window is 10 minutes, so the tag changes every window and a
 * bystander cannot link two windows. Paired friends hold the secret, so **the match happens only on the friend's
 * phone**, against beacons it already hears - nobody broadcasts a search, so nobody learns who is looking for whom.
 *
 * The whole book (my secret + each friend's label and secret) is stored as ONE blob encrypted with an AES-256-GCM
 * key under the Android Keystore. Labels are local nicknames and never leave the phone.
 */
internal class FriendBook(private val aead: Aead, private val store: FriendStore, private val random: SecureRandom) {

    private class Friend(val label: String, val secret: ByteArray)

    private var mySecret: ByteArray
    private val friends = mutableListOf<Friend>()

    init {
        val restored = store.load()?.let(::decode)
        if (restored == null) {
            mySecret = newSecret()
            persist()
        } else {
            mySecret = restored.first
            friends += restored.second
        }
    }

    val secret: ByteArray get() = synchronized(this) { mySecret.copyOf() }

    fun beaconTag(atMs: Long): String = synchronized(this) { tag(mySecret, window(atMs)) }

    /** Current window plus one either side, so a few minutes of clock skew between phones does not hide a friend. */
    fun match(tag: String, atMs: Long): String? = synchronized(this) {
        val w = window(atMs)
        friends.firstOrNull { f -> (w - 1..w + 1).any { tag(f.secret, it) == tag } }?.label
    }

    fun add(label: String, secret: ByteArray) = synchronized(this) {
        require(secret.size == SECRET_BYTES) { "bad friend secret" }
        friends.removeAll { it.label == label }
        friends += Friend(label, secret.copyOf())
        persist()
    }

    fun remove(label: String) = synchronized(this) {
        if (friends.removeAll { it.label == label }) persist()
    }

    fun labels(): List<String> = synchronized(this) { friends.map { it.label } }

    /** Revoke everyone at once: a new secret, so no current friend can recognise this phone until re-paired. */
    fun rotateMySecret() = synchronized(this) {
        mySecret = newSecret()
        persist()
    }

    private fun newSecret() = ByteArray(SECRET_BYTES).also(random::nextBytes)

    private fun persist() {
        val lines = listOf(VERSION, B64.encodeToString(mySecret)) +
            friends.map { B64.encodeToString(it.label.encodeToByteArray()) + ":" + B64.encodeToString(it.secret) }
        store.save(B64.encodeToString(aead.encrypt(lines.joinToString("\n").encodeToByteArray(), AD)))
    }

    /** Null if the blob cannot be decrypted (e.g. app data restored without its Keystore key): start afresh. */
    private fun decode(blob: String): Pair<ByteArray, List<Friend>>? = try {
        val lines = aead.decrypt(B64_DECODER.decode(blob), AD).decodeToString().split("\n")
        require(lines.first() == VERSION)
        val friends = lines.drop(2).filter { it.isNotEmpty() }.map { line ->
            val (label, secret) = line.split(":")
            Friend(B64_DECODER.decode(label).decodeToString(), B64_DECODER.decode(secret))
        }
        B64_DECODER.decode(lines[1]) to friends
    } catch (e: Exception) {
        null
    }

    companion object {
        const val WINDOW_MS = 10L * 60 * 1000
        const val SECRET_BYTES = 32
        private const val TAG_BYTES = 8
        private const val VERSION = "proxima-friends-v1"
        private val AD = "proxima/friends/v1".encodeToByteArray()
        private val B64 = Base64.getEncoder()
        private val B64_DECODER = Base64.getDecoder()

        fun window(atMs: Long): Long = Math.floorDiv(atMs, WINDOW_MS)

        fun tag(secret: ByteArray, window: Long): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
            return mac.doFinal("proxima-beacon-v1|$window".encodeToByteArray())
                .take(TAG_BYTES).joinToString("") { "%02x".format(it) }
        }

        /**
         * Six digits from both public keys, order-independent so both phones show the same code. If a relay swapped
         * in its own keys, the two screens would show different codes. 6 digits, not 4 as first proposed: 4 is one
         * guess in 10,000.
         */
        fun pairingCode(keyA: String, keyB: String): String {
            val (first, second) = listOf(keyA, keyB).sorted()
            val d = MessageDigest.getInstance("SHA-256").digest("proxima-pair-v1|$first|$second".encodeToByteArray())
            val n = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
                ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
            return "%06d".format(n % 1_000_000)
        }

        /** Sealed invite plaintext: names its sender so the receiver can check it came from the phone it paired with. */
        fun invitePlaintext(senderKey: String, secret: ByteArray) =
            "proxima-friend-v1|$senderKey|${B64.encodeToString(secret)}".encodeToByteArray()

        fun parseInvite(plain: ByteArray): Pair<String, ByteArray>? {
            val parts = plain.decodeToString().split("|")
            if (parts.size != 3 || parts[0] != "proxima-friend-v1") return null
            return try {
                parts[1] to B64_DECODER.decode(parts[2])
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Keeps the (still encrypted) friend book in memory only. Used where nothing needs to survive a restart. */
class InMemoryFriendStore : FriendStore {
    private var blob: String? = null
    override fun load(): String? = blob
    override fun save(blob: String) {
        this.blob = blob
    }
}
