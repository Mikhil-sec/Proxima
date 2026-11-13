package mu.proxima.trust

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import mu.proxima.core.model.WristbandTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the wristband QR: encode, then actually decode the symbol back. Asserting only that
 * encoding did not throw would not tell us a finder's phone can read it.
 *
 * `wristbandQrBitmap` itself cannot run here - `android.graphics.Bitmap` is a stub in unit tests - so
 * the Android-free [WristbandQr.matrix] is what gets covered, which is where the encoding lives.
 */
class WristbandQrTest {

    /** Shaped like a real one: two base64 32-byte public keys joined by a dot, as TrustEngine will emit. */
    private val realisticGuardianKey =
        "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE" +
            "." +
            "MCowBQYDK2VuAyEAq2sE2Hk1pVJ9kHhSKqV3VbEWlLLnKvxYyXyQ8ZkR5Ac"

    private val tag = WristbandTag(
        tagId = "7f3c9a21-55d4-4c0b-9e18-2a6b8f0d1e44",
        guardianKey = realisticGuardianKey,
        createdAtMs = 1_763_032_932_000L,
    )

    private fun decode(matrix: BitMatrix): String =
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(MatrixLuminanceSource(matrix)))).text

    @Test
    fun `a wristband QR decodes back to the same tag`() {
        val decoded = decode(WristbandQr.matrix(tag.toQrPayload(), sizePx = 512))

        assertEquals(tag.toQrPayload(), decoded)
        // And the finder's side parses it back into the identical tag.
        assertEquals(tag, WristbandTag.parse(decoded))
    }

    @Test
    fun `the payload carries no personal data`() {
        val payload = tag.toQrPayload()

        assertTrue(payload.startsWith("PRX1:"))
        assertEquals(4, payload.split(":").size)
        // Only a random id, the guardian's public key and a timestamp - nothing else can be in there.
        assertEquals("PRX1:${tag.tagId}:${tag.guardianKey}:${tag.createdAtMs}", payload)
    }

    @Test
    fun `still scannable at high error correction, which matters if wristbands are printed large`() {
        val decoded = decode(
            WristbandQr.matrix(tag.toQrPayload(), sizePx = 1024, ecc = ErrorCorrectionLevel.H),
        )
        assertEquals(tag.toQrPayload(), decoded)
    }

    @Test
    fun `the symbol is square and at least the requested size`() {
        val m = WristbandQr.matrix(tag.toQrPayload(), sizePx = 300)

        assertEquals(m.width, m.height)
        assertTrue("got ${m.width}", m.width >= 300)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive size is rejected rather than producing an empty bitmap`() {
        WristbandQr.matrix(tag.toQrPayload(), sizePx = 0)
    }

    /** The scanner contract: anything that is not a PRX1 tag must be ignored, not half-parsed. */
    @Test
    fun `foreign QR codes are rejected by the parser`() {
        assertNull(WristbandTag.parse("https://example.com"))
        assertNull(WristbandTag.parse("PRX1:only:three"))
        assertNull(WristbandTag.parse("PRX2:a:b:123"))
        assertNull(WristbandTag.parse("PRX1:a:b:not-a-number"))
        assertNotNull(WristbandTag.parse("PRX1:a:b:123"))
    }

    /** Reads a [BitMatrix] as if it were a camera frame: set module = black (0), clear = white (255). */
    private class MatrixLuminanceSource(private val m: BitMatrix) :
        LuminanceSource(m.width, m.height) {

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = if (m[x, y]) 0 else WHITE
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val base = y * width
                for (x in 0 until width) out[base + x] = if (m[x, y]) 0 else WHITE
            }
            return out
        }

        private companion object {
            const val WHITE: Byte = -1 // 0xFF
        }
    }
}
