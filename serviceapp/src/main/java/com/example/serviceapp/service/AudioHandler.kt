package com.example.serviceapp.service

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

class AudioHandler(private val context: Context) {

    private var recorder: AudioRecord? = null
    private var isMicRecording = false

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startMicRecording(filePath: File, durationMillis: Long = 1000L) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        recorder?.startRecording()
        isMicRecording = true

        val audioData = ByteArray(bufferSize)
        val startTime = System.currentTimeMillis()

        FileOutputStream(filePath).use { outputStream ->
            while (System.currentTimeMillis() - startTime < durationMillis &&
                recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder?.read(audioData, 0, bufferSize) ?: 0
                if (read > 0) outputStream.write(audioData, 0, read)
            }
        }

        recorder?.stop()
        recorder?.release()
        recorder = null
        isMicRecording = false
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