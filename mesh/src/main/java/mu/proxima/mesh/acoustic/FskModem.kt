package mu.proxima.mesh.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Acoustic fallback modem (P2): binary FSK through the phone speaker → another phone's microphone, for a
 * tiny SOS frame when Bluetooth/Wi-Fi are unavailable. Pure Kotlin (no Android), so it is tested on the JVM
 * with synthetic audio; the Android side only moves FloatArrays to AudioTrack / from AudioRecord.
 *
 * Frame on air: preamble (alternating bits) · 16-bit sync word · length byte · payload · CRC-16/CCITT.
 * Bits MSB first, one tone per bit (`markHz` = 1, `spaceHz` = 0), phase-continuous so there are no clicks.
 */
data class FskConfig(
    val sampleRate: Int = 48_000,
    /** 20 ms per bit = 50 bit/s. Long symbols survive indoor echoes and phone-speaker ringing. */
    val symbolSamples: Int = 960,
    /** 4–5 kHz: above most crowd/voice energy, still loud on small phone speakers. Multiples of 50 Hz = exact DFT bins. */
    val markHz: Double = 5_000.0,
    val spaceHz: Double = 4_000.0,
    val amplitude: Float = 0.8f,
    val preambleBits: Int = 16,
    val maxPayloadBytes: Int = 32,
) {
    val symbolMs: Double get() = symbolSamples * 1000.0 / sampleRate
}

class FskModem(val config: FskConfig = FskConfig()) {

    /** Bits on air for a payload of [payloadBytes] bytes (preamble + sync + length + payload + CRC). */
    fun frameBits(payloadBytes: Int): Int = config.preambleBits + 16 + 8 * (1 + payloadBytes + 2)

    fun durationMs(payloadBytes: Int): Double = frameBits(payloadBytes) * config.symbolMs

    /** PCM in −1..1, mono, at [FskConfig.sampleRate]. */
    fun modulate(payload: ByteArray): FloatArray {
        require(payload.size <= config.maxPayloadBytes) { "payload ${payload.size} B > ${config.maxPayloadBytes} B" }
        val bits = frameToBits(payload)
        val n = config.symbolSamples
        val out = FloatArray(bits.size * n)
        var phase = 0.0
        bits.forEachIndexed { i, bit ->
            val step = 2 * PI * (if (bit) config.markHz else config.spaceHz) / config.sampleRate
            for (s in 0 until n) {
                out[i * n + s] = (config.amplitude * sin(phase)).toFloat()
                phase += step
            }
            phase %= 2 * PI
        }
        // 5 ms raised-cosine fade in/out of the whole burst (no speaker pop).
        val ramp = minOf(config.sampleRate / 200, out.size / 2)
        for (s in 0 until ramp) {
            val g = (0.5 - 0.5 * cos(PI * s / ramp)).toFloat()
            out[s] *= g
            out[out.size - 1 - s] *= g
        }
        return out
    }

    /**
     * Every valid frame found in [samples] (any alignment, any level, with noise), oldest first, duplicates removed.
     * Tries 8 symbol phases; a frame counts only if its sync word matches and its CRC checks.
     */
    fun demodulate(samples: FloatArray): List<ByteArray> {
        val n = config.symbolSamples
        val found = mutableListOf<Pair<Int, ByteArray>>() // sample position → payload
        val phases = 8
        for (p in 0 until phases) {
            val offset = p * n / phases
            val count = (samples.size - offset) / n
            if (count <= 0) continue
            val bits = BooleanArray(count) { i -> symbolBit(samples, offset + i * n) }
            var i = 0
            while (i + 16 <= count) {
                val frame = if (readBits(bits, i, 16) == SYNC) parseAfterSync(bits, i + 16) else null
                if (frame != null) {
                    found += (offset + i * n) to frame.first
                    i = frame.second
                } else {
                    i++
                }
            }
        }
        // The same frame is usually found at several phases: keep one per (payload, position ± 2 symbols).
        val unique = mutableListOf<Pair<Int, ByteArray>>()
        for (f in found.sortedBy { it.first }) {
            if (unique.none { it.second.contentEquals(f.second) && kotlin.math.abs(it.first - f.first) <= 2 * n }) unique += f
        }
        return unique.map { it.second }
    }

    // ------------------------------------------------------------------ internals

    private fun frameToBits(payload: ByteArray): BooleanArray {
        val body = ByteArray(1 + payload.size)
        body[0] = payload.size.toByte()
        payload.copyInto(body, 1)
        val crc = crc16(body)
        val bits = ArrayList<Boolean>(frameBits(payload.size))
        repeat(config.preambleBits) { bits += it % 2 == 0 }
        for (b in 15 downTo 0) bits += (SYNC shr b) and 1 == 1
        (body + byteArrayOf((crc shr 8).toByte(), crc.toByte())).forEach { byte ->
            for (b in 7 downTo 0) bits += (byte.toInt() shr b) and 1 == 1
        }
        return bits.toBooleanArray()
    }

    private fun parseAfterSync(bits: BooleanArray, start: Int): Pair<ByteArray, Int>? {
        if (start + 8 > bits.size) return null
        val len = readBits(bits, start, 8)
        if (len == 0 || len > config.maxPayloadBytes) return null
        val end = start + 8 * (1 + len + 2)
        if (end > bits.size) return null
        val body = ByteArray(1 + len) { k -> readBits(bits, start + 8 * k, 8).toByte() }
        val crc = readBits(bits, start + 8 * (1 + len), 16)
        if (crc16(body) != crc) return null
        return body.copyOfRange(1, body.size) to end
    }

    /** true = mark tone stronger than space tone in this symbol window (Goertzel). */
    private fun symbolBit(x: FloatArray, from: Int): Boolean =
        goertzel(x, from, config.symbolSamples, config.markHz) > goertzel(x, from, config.symbolSamples, config.spaceHz)

    private fun goertzel(x: FloatArray, from: Int, len: Int, hz: Double): Double {
        val coeff = 2 * cos(2 * PI * hz / config.sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (k in from until from + len) {
            val s0 = x[k] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun readBits(bits: BooleanArray, start: Int, count: Int): Int {
        var v = 0
        for (k in start until start + count) v = (v shl 1) or (if (bits[k]) 1 else 0)
        return v
    }

    companion object {
        /** Sync word: good autocorrelation, not confused with the 1010… preamble. */
        const val SYNC = 0x2DD4

        /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF). */
        fun crc16(data: ByteArray): Int {
            var crc = 0xFFFF
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}
