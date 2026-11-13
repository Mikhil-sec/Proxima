package mu.proxima.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mu.proxima.core.engine.TrustEngine
import mu.proxima.core.fake.FakeTrustEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same [TrustEngineContractTest], but with keysets persisted through the real **Android Keystore**.
 * Run on a phone: `./gradlew :trust:connectedDebugAndroidTest`.
 *
 * "Another device" is a second keyset file under a second Keystore master key on the same phone.
 */
@RunWith(AndroidJUnit4::class)
class TinkTrustEngineKeystoreTest : TrustEngineContractTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    override fun newEngine(): TrustEngine = TinkTrustEngine(context)

    override fun newOtherDevice(): TrustEngine = TinkTrustEngine(
        AndroidKeystoreKeysetStore(context, prefFile = "test_other_device_keysets", masterKeyAlias = "test_other_device_master"),
        SharedPrefsWristbandStore(context, prefFile = "test_other_device_wristbands"),
    )

    /** The regression that mattered: simulation OFF must never hand out the fake again. */
    @Test
    fun simulationOffUsesTheRealEngine() {
        val trust = TrustModule.createTrust(context, simulation = false)

        assertTrue(trust is TinkTrustEngine)
        assertFalse(trust is FakeTrustEngine)
    }
}
