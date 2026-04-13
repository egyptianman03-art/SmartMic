package com.ahmedalaref.smartmic

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles saving raw PCM audio as WAV files.
 * No external libraries needed — WAV is just a header + raw PCM data.
 * WAV files play natively on all Android devices via MediaPlayer.
 */
class Mp3Recorder(private val context: Context) {

    private val recordingsDir: File by lazy {
        File(context.getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
    }

    /** Create a temp PCM file in cache dir */
    fun createTempPcmFile(): File =
        File(context.cacheDir, "rec_${System.currentTimeMillis()}.pcm")

    /**
     * Convert raw PCM file → WAV file saved in Recordings folder.
     * WAV header is 44 bytes; rest is raw 16-bit PCM samples.
     *
     * @param pcmFile   The temporary raw PCM file from AudioEngine
     * @param sampleRate  Sample rate used during recording (e.g. 16000)
     * @param channels    1 = mono, 2 = stereo
     * @param onDone    Called with the saved WAV File, or null on failure
     */
    fun convertToWav(
        pcmFile: File,
        sampleRate: Int = 16_000,
        channels: Int = 1,
        onDone: (File?) -> Unit
    ) {
        Thread {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                val wavFile = File(recordingsDir, "REC_$timestamp.wav")

                writeWav(pcmFile, wavFile, sampleRate, channels)
                pcmFile.delete()          // clean up temp PCM
                onDone(wavFile)
            } catch (e: Exception) {
                e.printStackTrace()
                pcmFile.delete()
                onDone(null)
            }
        }.start()
    }

    /** Write a valid WAV file from raw PCM data */
    private fun writeWav(pcmFile: File, wavFile: File, sampleRate: Int, channels: Int) {
        val pcmData   = pcmFile.readBytes()
        val byteRate  = sampleRate * channels * 2          // 16-bit = 2 bytes/sample
        val dataSize  = pcmData.size
        val totalSize = dataSize + 36                      // 44-byte header − 8

        FileOutputStream(wavFile).use { out ->
            // RIFF chunk
            out.write("RIFF".toByteArray())
            out.write(intToBytes(totalSize))
            out.write("WAVE".toByteArray())

            // fmt  sub-chunk
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16))                      // sub-chunk size = 16 for PCM
            out.write(shortToBytes(1))                     // AudioFormat = 1 (PCM)
            out.write(shortToBytes(channels.toShort()))
            out.write(intToBytes(sampleRate))
            out.write(intToBytes(byteRate))
            out.write(shortToBytes((channels * 2).toShort())) // block align
            out.write(shortToBytes(16))                    // bits per sample

            // data sub-chunk
            out.write("data".toByteArray())
            out.write(intToBytes(dataSize))
            out.write(pcmData)
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        (value shr 8  and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 24 and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        (value.toInt() shr 8 and 0xFF).toByte()
    )

    /** Return saved WAV files sorted newest-first */
    fun getRecordings(): List<File> =
        (recordingsDir.listFiles { f -> f.extension == "wav" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    /** Delete a recording file */
    fun deleteRecording(file: File): Boolean = file.delete()
}
