package mu.proxima.trust

import android.content.Context
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import mu.proxima.core.model.WristbandTag

/**
 * Where [TinkTrustEngine] keeps its private keysets. Split out so the crypto can be tested on the JVM with an
 * in-memory store, while the app uses [AndroidKeystoreKeysetStore]. The engine itself never touches storage.
 */
interface KeysetStore {
    /** Returns the keyset called [name], generating it from [template] and persisting it the first time. */
    fun getOrCreate(name: String, template: KeyTemplate): KeysetHandle

    /** Names of every stored keyset, so retired sessions can be found and pruned. */
    fun names(): Set<String>

    /** Destroys the keyset called [name]. A no-op if it does not exist. */
    fun delete(name: String)
}

/** Persists the wristband tags this phone minted, so a guardian still recognises them after a restart. */
interface WristbandStore {
    fun load(): List<WristbandTag>
    fun save(tags: List<WristbandTag>)
}

/**
 * Keysets live in a private SharedPreferences file, encrypted under an AES-GCM master key that is generated
 * inside and never leaves the **Android Keystore**. Uninstalling the app destroys the identity, which is the
 * intended lifetime: a node id is a per-install pseudonym, not a person.
 *
 * [prefFile] and [masterKeyAlias] are parameters only so the instrumented test can simulate a second device on
 * the same phone. The app always uses the defaults.
 */
class AndroidKeystoreKeysetStore(
    context: Context,
    private val prefFile: String = "proxima_trust_keysets",
    private val masterKeyAlias: String = "proxima_trust_master_key",
) : KeysetStore {
    private val appContext = context.applicationContext

    /** The same file AndroidKeysetManager writes to: one entry per keyset name, nothing else. */
    private val prefs get() = appContext.getSharedPreferences(prefFile, Context.MODE_PRIVATE)

    override fun getOrCreate(name: String, template: KeyTemplate): KeysetHandle =
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, name, prefFile)
            .withKeyTemplate(template)
            .withMasterKeyUri("android-keystore://$masterKeyAlias")
            .build()
            .keysetHandle

    override fun names(): Set<String> = prefs.all.keys.toSet()

    override fun delete(name: String) {
        prefs.edit().remove(name).commit()
    }
}

/** Tags are stored as their own QR payload strings, one per line - the format already has a tested parser. */
class SharedPrefsWristbandStore(context: Context, prefFile: String = "proxima_trust_wristbands") : WristbandStore {
    private val prefs = context.applicationContext.getSharedPreferences(prefFile, Context.MODE_PRIVATE)

    override fun load(): List<WristbandTag> =
        prefs.getString(KEY, null).orEmpty().lineSequence().mapNotNull { WristbandTag.parse(it) }.toList()

    override fun save(tags: List<WristbandTag>) {
        prefs.edit().putString(KEY, tags.joinToString("\n") { it.toQrPayload() }).apply()
    }

    private companion object {
        const val KEY = "minted"
    }
}
