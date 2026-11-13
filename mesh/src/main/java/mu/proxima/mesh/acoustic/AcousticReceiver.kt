package mu.proxima.mesh.acoustic

/**
 * Streaming side of the modem, still pure Kotlin: feed mic chunks in, get each new frame out once.
 *
 * Keeps a rolling window long enough for one full SOS frame plus slack, decodes it about once a second,
 * and suppresses a payload it already reported within [dedupMs] (the same chirp is in several windows,
 * and the sender repeats it).
 */
class AcousticReceiver(
    private val modem: FskModem = FskModem(),
    payloadBytes: Int = AcousticSos.SIZE,
    private val decodeEverySamples: Int = modem.config.sampleRate,
    private val dedupMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val window = FloatArray(
        (modem.durationMs(payloadBytes) * modem.config.sampleRate / 1000).toInt() + 2 * decodeEverySamples,
    )
    private var filled = 0
    private var sinceDecode = 0
    private val reported = LinkedHashMap<String, Long>()

    /** Returns payloads first heard in this chunk (usually empty). */
    fun feed(chunk: FloatArray, length: Int = chunk.size): List<ByteArray> {
        var off = 0
        while (off < length) {
            val n = minOf(length - off, window.size)
            // Shift left and append (window is a few seconds; a copy per chunk is cheap).
            val keep = minOf(filled, window.size - n)
            System.arraycopy(window, filled - keep, window, 0, keep)
            System.arraycopy(chunk, off, window, keep, n)
            filled = keep + n
            sinceDecode += n
            off += n
        }
        if (sinceDecode < decodeEverySamples) return emptyList()
        sinceDecode = 0
        val now = clock()
        reported.entries.removeAll { now - it.value > dedupMs }
        return modem.demodulate(window.copyOf(filled)).filter { payload ->
            val key = payload.joinToString("") { "%02x".format(it) }
            if (key in reported) false else { reported[key] = now; true }
        }
    }
}
