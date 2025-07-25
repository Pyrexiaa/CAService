package com.example.serviceapp.service.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AudioHandler(private val context: Context) {

    private var recorder: AudioRecord? = null
    private var isMicRecording = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startMicRecording(
        filePath: File,
        durationMillis: Long = 1000L
    ) = withContext(Dispatchers.IO) {
        val sampleRate = 26521
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val numChannels = 1
        val bitsPerSample = 16

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        val maxDataSize = (sampleRate * (bitsPerSample / 8) * numChannels) * (durationMillis / 1000.0).toInt()
        val pcmData = ByteArrayOutputStream()

        try {
            recorder.startRecording()
            val buffer = ByteArray(bufferSize)
            val startTime = System.currentTimeMillis()

            while (
                System.currentTimeMillis() - startTime < durationMillis &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                pcmData.size() < maxDataSize
            ) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmData.write(buffer, 0, read)
                }
            }

            recorder.stop()
        } finally {
            recorder.release()
        }

        // Now write WAV header + PCM to file
        val rawAudio = pcmData.toByteArray()
        writeWavFile(
            filePath.absolutePath,
            rawAudio,
            sampleRate,
            numChannels,
            bitsPerSample
        )
    }

    private fun writeWavFile(path: String, audioData: ByteArray, sampleRate: Int, channels: Int, bitDepth: Int) {
        val totalDataLen = audioData.size + 36
        val byteRate = sampleRate * channels * bitDepth / 8

        FileOutputStream(path).use { out ->
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            writeInt(header, 4, totalDataLen)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeInt(header, 16, 16)
            writeShort(header, 20, 1)
            writeShort(header, 22, channels.toShort())
            writeInt(header, 24, sampleRate)
            writeInt(header, 28, byteRate)
            writeShort(header, 32, (channels * bitDepth / 8).toShort())
            writeShort(header, 34, bitDepth.toShort())
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            writeInt(header, 40, audioData.size)

            out.write(header)
            out.write(audioData)
        }
    }

    private fun writeInt(header: ByteArray, index: Int, value: Int) {
        header[index] = (value and 0xff).toByte()
        header[index + 1] = ((value shr 8) and 0xff).toByte()
        header[index + 2] = ((value shr 16) and 0xff).toByte()
        header[index + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(header: ByteArray, index: Int, value: Short) {
        header[index] = (value.toInt() and 0xff).toByte()
        header[index + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }

    fun stopMicRecording() {
        if (isMicRecording) {
            recorder?.stop()
            recorder?.release()
            isMicRecording = false
        }
    }

    fun ensureMicPermissionGranted(): Boolean {
        val hasPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.e("AudioHandler", "Microphone permission not granted.")
        }
        return hasPermission
    }
}