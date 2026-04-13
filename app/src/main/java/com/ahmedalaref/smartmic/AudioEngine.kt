package com.ahmedalaref.smartmic

import android.content.Context
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * AudioEngine — heart of Smart Mic
 *
 * Responsibilities:
 *  • Capture PCM audio from AudioRecord (mic / BT / wired routed by AudioManager)
 *  • Echo audio in real time via AudioTrack  (VOICE_COMMUNICATION mode, minimal latency)
 *  • Optionally write raw PCM frames to a temp file for later MP3 conversion
 *  • Report RMS level on every buffer cycle for the level-meter UI
 *  • Apply NoiseSuppressor and AutomaticGainControl when the device supports them
 *
 * Threading: a dedicated Thread with THREAD_PRIORITY_AUDIO handles the hot loop;
 * UI callbacks are dispatched on the main looper.
 */
class AudioEngine(private val context: Context) {

    // ─── Constants ────────────────────────────────────────────────────────────
    companion object {
        const val SAMPLE_RATE     = 16_000          // 16 kHz — standard for voice
        const val CHANNEL_IN      = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT     = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_FRAME = 2               // 16-bit PCM → 2 bytes/sample
    }

    // ─── Public state ─────────────────────────────────────────────────────────
    @Volatile var isStreaming  = false ; private set
    @Volatile var isRecording  = false ; private set

    // ─── Callbacks (called on the main thread) ────────────────────────────────
    var onAudioLevelChanged : ((Float)  -> Unit)? = null
    var onError             : ((String) -> Unit)? = null

    // ─── Internal audio objects ───────────────────────────────────────────────
    private var audioRecord     : AudioRecord?           = null
    private var audioTrack      : AudioTrack?            = null
    private var noiseSuppressor : NoiseSuppressor?       = null
    private var gainControl     : AutomaticGainControl?  = null

    // ─── Recording state ──────────────────────────────────────────────────────
    private var recordingStream : FileOutputStream? = null
    private var recordingFile   : File?             = null
    @Volatile private var pcmBytesWritten = 0L

    // ─── Threading ────────────────────────────────────────────────────────────
    private var streamThread : Thread? = null
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Initialise AudioRecord + AudioTrack and start the real-time streaming loop.
     * Returns true on success, false on error (onError is also invoked).
     */
    fun startStreaming(): Boolean {
        if (isStreaming) return true

        val minIn  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN,  AUDIO_FORMAT)
        val minOut = AudioTrack.getMinBufferSize (SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
        val bufSz  = maxOf(minIn, minOut, 4096) * 2   // double buffer → lower latency

        return try {
            // ── AudioRecord ──────────────────────────────────────────────────
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_IN)
                        .build()
                )
                .setBufferSizeInBytes(bufSz)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                dispatchError("AudioRecord failed to initialise — check RECORD_AUDIO permission")
                return false
            }

            // ── Optional effects (applied per session) ───────────────────────
            audioRecord?.audioSessionId?.let { sid ->
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sid)?.also { it.enabled = true }
                }
                if (AutomaticGainControl.isAvailable()) {
                    gainControl = AutomaticGainControl.create(sid)?.also { it.enabled = true }
                }
            }

            // ── AudioTrack ───────────────────────────────────────────────────
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufSz)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                dispatchError("AudioTrack failed to initialise")
                return false
            }

            isStreaming = true
            audioRecord?.startRecording()
            audioTrack?.play()

            // ── Hot loop on a high-priority audio thread ─────────────────────
            streamThread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                val buf = ByteArray(bufSz)
                while (isStreaming) {
                    val n = audioRecord?.read(buf, 0, bufSz) ?: break
                    if (n <= 0) continue

                    // Play back immediately
                    audioTrack?.write(buf, 0, n)

                    // Write to recording file if recording is active
                    if (isRecording) {
                        try {
                            recordingStream?.write(buf, 0, n)
                            pcmBytesWritten += n
                        } catch (_: Exception) { /* ignore write errors mid-loop */ }
                    }

                    // Compute RMS → dispatch to UI
                    val rms = computeRms(buf, n)
                    mainHandler.post { onAudioLevelChanged?.invoke(rms) }
                }
                // Zero the meter when loop ends
                mainHandler.post { onAudioLevelChanged?.invoke(0f) }
            }, "SmartMic-AudioThread").apply { start() }

            true
        } catch (ex: Exception) {
            dispatchError("Engine start error: ${ex.message}")
            releaseAll()
            false
        }
    }

    /** Stop streaming (and recording if active). */
    fun stopStreaming() {
        isStreaming = false
        isRecording = false

        // Stop AudioRecord first so read() unblocks
        try { audioRecord?.stop() } catch (_: Exception) {}
        streamThread?.join(2_000)
        streamThread = null

        // Close any open recording stream
        runCatching { recordingStream?.flush(); recordingStream?.close() }
        recordingStream = null

        releaseAll()
    }

    /**
     * Begin writing captured PCM to [outputFile].
     * Must be called AFTER [startStreaming].
     */
    fun startRecording(outputFile: File): Boolean {
        if (!isStreaming) {
            dispatchError("Start streaming before recording")
            return false
        }
        if (isRecording) return true
        return try {
            recordingFile   = outputFile
            recordingStream = FileOutputStream(outputFile)
            pcmBytesWritten = 0L
            isRecording     = true
            true
        } catch (ex: Exception) {
            dispatchError("Cannot open recording file: ${ex.message}")
            false
        }
    }

    /**
     * Stop recording. Returns the raw PCM [File] ready for MP3 conversion,
     * or null if nothing was recorded.
     */
    fun stopRecording(): File? {
        if (!isRecording) return null
        isRecording = false
        runCatching { recordingStream?.flush(); recordingStream?.close() }
        recordingStream = null
        return recordingFile.takeIf { pcmBytesWritten > 0 }
    }

    // ── Effect toggles (can be called while streaming) ────────────────────────

    fun setNoiseSuppression(enabled: Boolean) { noiseSuppressor?.enabled = enabled }
    fun setAutoGainControl (enabled: Boolean) { gainControl?.enabled     = enabled }

    // ── Accessors ─────────────────────────────────────────────────────────────

    fun getSampleRate()       = SAMPLE_RATE
    fun getPcmBytesWritten()  = pcmBytesWritten

    /** Full cleanup — call from Activity.onDestroy(). */
    fun cleanup() {
        stopStreaming()
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun releaseAll() {
        runCatching { audioRecord?.release() }; audioRecord = null
        runCatching { audioTrack?.release()  }; audioTrack  = null
        runCatching { noiseSuppressor?.release() }; noiseSuppressor = null
        runCatching { gainControl?.release() };     gainControl     = null
    }

    private fun dispatchError(msg: String) =
        mainHandler.post { onError?.invoke(msg) }

    /** Root-mean-square of a PCM_16BIT byte buffer, normalised to [0, 1]. */
    private fun computeRms(buf: ByteArray, n: Int): Float {
        if (n < 2) return 0f
        val shorts = ShortArray(n / 2)
        ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        var sum = 0.0
        for (s in shorts) sum += s.toDouble() * s
        return (sqrt(sum / shorts.size) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
