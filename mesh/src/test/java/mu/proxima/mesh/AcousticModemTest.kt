package mu.proxima.mesh

import mu.proxima.core.model.GeoPoint
import mu.proxima.core.model.Incident
import mu.proxima.core.model.IncidentType
import mu.proxima.core.model.MeshPacket
import mu.proxima.core.model.PacketKind
import mu.proxima.core.model.Severity
import mu.proxima.core.pipeline.ProximaJson
import mu.proxima.mesh.acoustic.AcousticReceiver
import mu.proxima.mesh.acoustic.AcousticSos
import mu.proxima.mesh.acoustic.FskModem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic-audio tests for the acoustic fallback: what a mic would hear, without a phone. */
class AcousticModemTest {

    private val modem = FskModem()
    private val sr = modem.config.sampleRate
    private val ganga = GeoPoint(-20.4183, 57.4924) // Ganga Talao
    private val sos = AcousticSos(IncidentType.MEDICAL, Severity.CRITICAL, "3fa9c01b", ganga, nonce = 0xBEEF)

    private fun silence(ms: Int) = FloatArray(sr * ms / 1000)

    /** Gaussian noise with the given RMS, seeded so failures are reproducible. */
    private fun noise(len: Int, rms: Double, seed: Int = 7): FloatArray {
        val r = Random(seed)
        return FloatArray(len) {
            val u1 = r.nextDouble().coerceAtLeast(1e-12); val u2 = r.nextDouble()
            (rms * sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)).toFloat()
        }
    }

    private fun mix(a: FloatArray, b: FloatArray) = FloatArray(maxOf(a.size, b.size)) {
        (if (it < a.size) a[it] else 0f) + (if (it < b.size) b[it] else 0f)
    }

    private fun scale(a: FloatArray, g: Float) = FloatArray(a.size) { a[it] * g }

    private fun rms(a: FloatArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size)

    // ------------------------------------------------------------------ frame codec

    @Test fun sosFrameRoundTripsAndIsSixteenBytes() {
        val bytes = sos.encode()
        assertEquals(16, bytes.size)
        val back = AcousticSos.decode(bytes)!!
        assertEquals(sos.copy(location = back.location), back)
        assertEquals(ganga.lat, back.location!!.lat, 1e-4)
        assertEquals(ganga.lon, back.location!!.lon, 1e-4)
    }

    @Test fun sosWithoutFixAndIncidentMapping() {
        val noFix = sos.copy(location = null, type = IncidentType.HAZARD, severity = Severity.HIGH)
        val back = AcousticSos.decode(noFix.encode())!!
        assertNull(back.location)
        val inc = back.toIncident(nowMs = 1_000L)
        assertEquals(IncidentType.HAZARD, inc.type)
        assertEquals("ac-3fa9c01b-beef", inc.id) // stable id → repeats merge
        assertTrue(inc.summary.contains("unverified"))
    }

    @Test fun nonHexNodeIdsAreHashedNotRejected() {
        val back = AcousticSos.decode(sos.copy(originNodeId = "SELF-ab12").encode())!!
        assertEquals(8, back.originNodeId.length)
    }

    @Test fun garbageBytesAreRejected() {
        assertNull(AcousticSos.decode(ByteArray(16)))           // version 0
        assertNull(AcousticSos.decode(ByteArray(15)))           // wrong size
    }

    @Test fun sosFitsInUnderFourSecondsOnAir() {
        assertTrue(modem.durationMs(AcousticSos.SIZE) < 4_000.0)
    }

    // ------------------------------------------------------------------ modem over synthetic audio

    @Test fun cleanRoundTrip() {
        val decoded = modem.demodulate(modem.modulate(sos.encode()))
        assertEquals(1, decoded.size)
        assertArrayEquals(sos.encode(), decoded[0])
    }

    @Test fun findsFrameAtAnyOffsetInsideSilence() {
        for (lead in listOf(0, 1, 137, 481, 959, 12_345)) {
            val audio = FloatArray(lead) + modem.modulate(sos.encode()) + silence(300)
            val decoded = modem.demodulate(audio)
            assertEquals("lead=$lead", 1, decoded.size)
            assertArrayEquals(sos.encode(), decoded[0])
        }
    }

    @Test fun survivesLoudBroadbandNoise() {
        // Faint signal (5 % level, as across a room) under noise of the SAME total power: 0 dB SNR broadband.
        val signal = scale(modem.modulate(sos.encode()), 0.05f)
        val audio = mix(silence(500) + signal + silence(500), noise(sr * 5, rms(signal)))
        assertArrayEquals(sos.encode(), modem.demodulate(audio).single())
    }

    @Test fun survivesRoomEcho() {
        val dry = silence(200) + modem.modulate(sos.encode()) + silence(200)
        val delay = sr * 7 / 1000 // 7 ms reflection at 60 %
        val wet = FloatArray(dry.size + delay) { i ->
            (if (i < dry.size) dry[i] else 0f) + (if (i >= delay) 0.6f * dry[i - delay] else 0f)
        }
        assertArrayEquals(sos.encode(), modem.demodulate(wet).single())
    }

    @Test fun survivesSpeakerMicClockMismatch() {
        // Receiver clock 300 ppm off: resample by linear interpolation.
        val src = silence(100) + modem.modulate(sos.encode()) + silence(100)
        val ratio = 1.0003
        val out = FloatArray((src.size / ratio).toInt()) { i ->
            val t = i * ratio; val k = t.toInt(); val f = (t - k).toFloat()
            src[k] * (1 - f) + (if (k + 1 < src.size) src[k + 1] else 0f) * f
        }
        assertArrayEquals(sos.encode(), modem.demodulate(out).single())
    }

    @Test fun twoDifferentFramesInOneRecordingAreBothFound() {
        val other = sos.copy(nonce = 1, type = IncidentType.HAZARD).encode()
        val audio = silence(50) + modem.modulate(sos.encode()) + silence(333) + modem.modulate(other) + silence(50)
        val decoded = modem.demodulate(audio)
        assertEquals(2, decoded.size)
        assertArrayEquals(sos.encode(), decoded[0])
        assertArrayEquals(other, decoded[1])
    }

    @Test fun pureNoiseNeverDecodes() {
        for (seed in 1..5) assertTrue(modem.demodulate(noise(sr * 10, 0.3, seed)).isEmpty())
    }

    // ------------------------------------------------------------------ streaming receiver + mesh mapping

    @Test fun receiverReportsEachChirpOnceAcrossChunksAndRepeats() {
        var now = 0L
        val rx = AcousticReceiver(modem, clock = { now })
        val one = modem.modulate(sos.encode())
        // Sender repeats twice with a 1 s gap; mic delivers 100 ms chunks with noise.
        val audio = mix(silence(700) + one + silence(1000) + one + silence(2500), noise(sr * 12, 0.01))
        val heard = mutableListOf<ByteArray>()
        val chunk = sr / 10
        var i = 0
        while (i < audio.size) {
            val c = audio.copyOfRange(i, minOf(i + chunk, audio.size))
            heard += rx.feed(c)
            i += chunk; now += 100
        }
        assertEquals(1, heard.size)
        assertArrayEquals(sos.encode(), heard[0])
    }

    @Test fun outgoingCriticalIncidentBecomesChirpAndHeardChirpBecomesUnsignedIncidentPacket() {
        val incident = Incident("i1", IncidentType.MEDICAL, Severity.CRITICAL, summary = "collapsed", location = ganga,
            createdAtMs = 0L, reporterNodeId = "3fa9c01b")
        val out = MeshPacket("p1", PacketKind.INCIDENT, "3fa9c01b", "key", 0L, Severity.CRITICAL, ttl = 10,
            path = listOf("3fa9c01b"), payload = ProximaJson.encodeToString(Incident.serializer(), incident))
        val chirp = AcousticSos.fromPacket(out)!!
        assertEquals(IncidentType.MEDICAL, chirp.type)

        val heard = AcousticSos.decode(modem.demodulate(modem.modulate(chirp.encode())).single())!!.toPacket(5_000L)
        assertEquals(PacketKind.INCIDENT, heard.kind)
        assertNull(heard.signature)                         // pipeline marks it unverified
        assertEquals(listOf("3fa9c01b"), heard.path)        // one hop from its origin
        val back = ProximaJson.decodeFromString(Incident.serializer(), heard.payload)
        assertEquals(heard.packetId, back.id)               // same chirp again → same id → dedup
        assertEquals(Severity.CRITICAL, back.severity)
    }

    @Test fun lowSeverityAndNonIncidentPacketsAreNotChirped() {
        val ping = MeshPacket("p2", PacketKind.CONTROL, "a", "k", 0L, Severity.HIGH, ttl = 3, payload = "ping")
        assertNull(AcousticSos.fromPacket(ping))
        assertNull(AcousticSos.fromPacket(ping.copy(kind = PacketKind.INCIDENT, severity = Severity.MEDIUM)))
    }

    @Test fun corruptedSymbolIsRejectedByCrc() {
        val audio = modem.modulate(sos.encode())
        val n = modem.config.symbolSamples
        val symbol = modem.config.preambleBits + 16 + 8 + 20 // a payload bit
        // Overwrite that symbol with each tone in turn: one keeps the bit, the other flips it,
        // and with the flip the CRC must reject the whole frame.
        val hz = listOf(modem.config.markHz, modem.config.spaceHz)
        val results = hz.map { f ->
            val copy = audio.copyOf()
            for (s in 0 until n) copy[symbol * n + s] = (0.8 * kotlin.math.sin(2 * Math.PI * f * s / sr)).toFloat()
            modem.demodulate(copy)
        }
        assertTrue("one flip decodes as original, the other is rejected", results.count { it.isEmpty() } == 1)
        assertArrayEquals(sos.encode(), results.single { it.isNotEmpty() }.single())
    }
}
