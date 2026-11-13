package mu.proxima.mesh.acoustic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speaker/mic plumbing for [FskModem]. All signal logic lives in the pure classes; this only moves floats.
 *
 * Privacy: audio is demodulated in memory and dropped; nothing is stored or sent except decoded SOS frames.
 * Listening needs the app on screen (Android silences background capture without a microphone FGS, which
 * we deliberately don't request) and gives way to SpeechRecognizer (it wins the mic on Android 10+).
 */
class AcousticLink(private val context: Context, private val modem: FskModem = FskModem()) {

    private val sampleRate = modem.config.sampleRate

    /** Debug: force a MediaRecorder.AudioSource (e.g. 1 = MIC, 6 = VOICE_RECOGNITION, 9 = UNPROCESSED). */
    @Volatile var sourceOverride: Int? = null

    fun canListen(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Plays [payload] [repeats] times (1 s gap) on the alarm stream, so it is loud even in silent mode. Blocks until done. */
    suspend fun transmit(payload: ByteArray, repeats: Int = 2) = withContext(Dispatchers.IO) {
        val one = modem.modulate(payload)
        val gap = FloatArray(sampleRate)
        val pcm = FloatArray((one.size + gap.size) * repeats).also { out ->
            repeat(repeats) { r -> one.copyInto(out, r * (one.size + gap.size)) }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 4)
            .build()
        try {
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            Log.i(TAG, "acoustic tx ${payload.size} B × $repeats (${pcm.size * 1000L / sampleRate} ms)")
            delay(pcm.size * 1000L / sampleRate + 200)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Listens until the returned job is cancelled; [onPayload] gets each new frame once. */
    @SuppressLint("MissingPermission") // checked by canListen()
    fun listen(scope: CoroutineScope, onPayload: suspend (ByteArray) -> Unit): Job? {
        if (!canListen()) {
            Log.w(TAG, "acoustic listen skipped: RECORD_AUDIO not granted")
            return null
        }
        return scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val record = AudioRecord.Builder()
                .setAudioSource(bestSource())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4 / 2)) // ≥ 0.5 s
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "acoustic listen: AudioRecord init failed")
                record.release()
                return@launch
            }
            val effects = disableVoiceProcessing(record.audioSessionId)
            val receiver = AcousticReceiver(modem)
            val chunk = FloatArray(sampleRate / 10) // 100 ms
            var chunks = 0
            try {
                record.startRecording()
                Log.i(TAG, "acoustic listening (source=${record.audioSource})")
                while (isActive) {
                    val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) { delay(50); continue }
                    if (++chunks % 20 == 0) { // every 2 s: what the mic hears (device check)
                        val rms = kotlin.math.sqrt(chunk.take(n).sumOf { it.toDouble() * it } / n)
                        val c = modem.config
                        Log.d(TAG, "acoustic mic rms=%.4f mark=%.0fdB space=%.0fdB ref8k=%.0fdB".format(
                            rms, modem.toneDb(chunk, n, c.markHz), modem.toneDb(chunk, n, c.spaceHz), modem.toneDb(chunk, n, 8_000.0)))
                    }
                    receiver.feed(chunk, n).forEach { onPayload(it) }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                effects.forEach { runCatching { it.release() } }
                Log.i(TAG, "acoustic listening stopped")
            }
        }
    }

    /**
     * Noise suppression treats a steady tone as noise and fades it out mid-frame (seen on the S23+, 18:43 Fri).
     * Turn off NS / AGC / AEC on this session where the phone exposes them. Returns the effects to release later.
     */
    private fun disableVoiceProcessing(session: Int): List<android.media.audiofx.AudioEffect> {
        val out = mutableListOf<android.media.audiofx.AudioEffect>()
        val names = mutableListOf<String>()
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            android.media.audiofx.NoiseSuppressor.create(session)?.let { it.enabled = false; out += it; names += "NS" }
        }
        if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
            android.media.audiofx.AutomaticGainControl.create(session)?.let { it.enabled = false; out += it; names += "AGC" }
        }
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            android.media.audiofx.AcousticEchoCanceler.create(session)?.let { it.enabled = false; out += it; names += "AEC" }
        }
        Log.i(TAG, "acoustic mic effects disabled: ${names.ifEmpty { listOf("none available") }}")
        return out
    }

    /** UNPROCESSED where supported: noise suppression / AGC on MIC can eat pure tones. [sourceOverride] for device checks. */
    private fun bestSource(): Int {
        sourceOverride?.let { return it }
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // MIC, not VOICE_RECOGNITION: on the S23+ VOICE_RECOGNITION heard the tones loud yet never decoded (its
        // processing distorts them); MIC decoded both ways S23+ ↔ S26+ (device check 18:45 Fri).
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
    }

    private companion object { const val TAG = "Proxima" }
}
