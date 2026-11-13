package mu.proxima.trust

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import mu.proxima.core.model.WristbandTag

/**
 * UNUSED since 15:35 Fri. The team dropped the QR wristband (#14) because there is no wristband
 * hardware or printing; lost & found now works off the reporter's key instead. Kept because it is
 * small, self-contained and round-trip tested, so it costs nothing to leave in and is here if the
 * surprise card wants a printable wristband. **Nothing in the demo flow calls it.**
 *
 * The child's wristband QR.
 *
 * The payload is [WristbandTag.toQrPayload] - `PRX1:<tagId>:<guardianKey>:<createdAtMs>` - and carries
 * **no personal data**: a random tag id and the guardian's public key. A finder who scans it learns
 * nothing about the child; it only tells their phone which key to seal the "found" notice to.
 *
 * Encoding is kept separate from the [Bitmap] so it can be round-trip tested on the JVM: see
 * `WristbandQrTest`, which decodes the matrix back and asserts it still parses as the same tag.
 */
object WristbandQr {

    /**
     * Error correction level. QR `M` recovers ~15% damage.
     *
     * Not `H` (~30%) on purpose: the payload is ~150 bytes because it embeds the guardian's public key,
     * and at `H` that needs a denser symbol. On a wristband printed at ~25 mm the modules then fall below
     * the ~0.5 mm a phone camera can resolve, so the "more robust" setting is the one that fails to scan.
     * If the wristbands are printed larger than ~40 mm, `H` becomes the better choice.
     */
    val DEFAULT_ECC: ErrorCorrectionLevel = ErrorCorrectionLevel.M

    /** Pure, Android-free encode step. [sizePx] is the square symbol size requested from zxing. */
    fun matrix(
        payload: String,
        sizePx: Int,
        ecc: ErrorCorrectionLevel = DEFAULT_ECC,
    ): BitMatrix {
        require(sizePx > 0) { "sizePx must be positive, was $sizePx" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ecc,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // 4 modules is the spec minimum quiet zone; scanners get unreliable below it.
            EncodeHintType.MARGIN to 4,
        )
        return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }
}

/**
 * Renders [tag] as a scannable QR.
 *
 * Deliberately dark-on-light even though the app is an OLED-black theme: a panicking finder may be using
 * a non-Proxima scanner app, and inverted QR codes are not universally supported. The UI should show it on
 * a light card rather than inverting it.
 */
fun wristbandQrBitmap(
    tag: WristbandTag,
    sizePx: Int,
    darkColor: Int = Color.BLACK,
    lightColor: Int = Color.WHITE,
): Bitmap {
    val matrix = WristbandQr.matrix(tag.toQrPayload(), sizePx)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix[x, y]) darkColor else lightColor
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
