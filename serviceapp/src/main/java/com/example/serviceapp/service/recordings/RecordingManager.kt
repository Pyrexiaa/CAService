package com.example.serviceapp.service.recordings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.serviceapp.service.handlers.HandlerManager
import com.example.serviceapp.service.preferences.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Manages recording operations and file handling
class RecordingManager(
    private val context: Context,
    private val handlerManager: HandlerManager,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "RecordingSessionManager"
        private const val MAX_CIRCULAR_FILES = 60
        private const val RECORDING_DURATION_MS = 1000L
        private const val RECORDING_CYCLE_DELAY_MS = 500L
    }

    private var recordingScope: CoroutineScope? = null

    // File directories
    private val audioDir: File by lazy { File(context.filesDir, "audio").apply { mkdirs() } }
    private val sensorDir: File by lazy { File(context.filesDir, "sensor").apply { mkdirs() } }
    private val imageDir: File by lazy { File(context.filesDir, "image").apply { mkdirs() } }

    fun startRecordingSession(onStatusUpdate: (String) -> Unit) {
        // Cancel any existing recording session
        recordingScope?.cancel()

        recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        recordingScope?.launch {
            try {
                runRecordingLoop(onStatusUpdate)
            } catch (e: Exception) {
                Log.e(TAG, "Recording session failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Recording session encountered an error: ${e.message}")
                }
            }
        }
    }

    private suspend fun runRecordingLoop(onStatusUpdate: (String) -> Unit) {
        Log.d(TAG, "Recording session started")

        while (currentCoroutineContext().isActive) {
            val timestamp = getCurrentTimestamp()
            val tempFiles = createTempFiles(timestamp)

            try {
                val jobs = createRecordingJobs(tempFiles)
                jobs.joinAll()
                processCompletedFiles(tempFiles)
            } catch (e: Exception) {
                Log.e(TAG, "Recording cycle failed: ${e.message}")
                cleanupTempFiles(tempFiles)
            }

            delay(RECORDING_CYCLE_DELAY_MS)
        }

        withContext(Dispatchers.Main) {
            onStatusUpdate("Rolling session stopped. Last $MAX_CIRCULAR_FILES segments stored.")
        }
    }

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.getDefault()).format(Date())
    }

    private data class TempFiles(
        val audio: File,
        val sensor: File,
        val image: File,
        val sensorWriter: BufferedWriter?
    )

    private fun createTempFiles(timestamp: String): TempFiles {
        val tempAudioFile = File(audioDir, "temp_audio_$timestamp.wav")
        val tempSensorFile = File(sensorDir, "temp_sensor_$timestamp.txt")
        val tempImageFile = File(imageDir, "temp_image_$timestamp.jpg")

        val writer = try {
            BufferedWriter(FileWriter(tempSensorFile))
        } catch (e: IOException) {
            Log.e(TAG, "Sensor file creation failed: ${e.message}")
            null
        }

        return TempFiles(tempAudioFile, tempSensorFile, tempImageFile, writer)
    }

    private suspend fun createRecordingJobs(tempFiles: TempFiles): List<Job> =
        coroutineScope {
            val jobs = mutableListOf<Job>()

            tempFiles.sensorWriter?.let {
                jobs.add(createSensorJob(tempFiles))
            }

            if (handlerManager.audioStatus) {
                jobs.add(createAudioJob(tempFiles))
            }

            if (handlerManager.cameraStatus) {
                jobs.add(createCameraJob(tempFiles))
            }

            jobs
        }

    private fun CoroutineScope.createSensorJob(tempFiles: TempFiles) = launch {
        try {
            tempFiles.sensorWriter?.use { writer ->
                handlerManager.sensorHandler.logSensorData(writer, durationMillis = RECORDING_DURATION_MS)
            }
        } catch (e: Exception) {
            Log.e("SensorHandler", "Sensor logging failed: ${e.message}")
        }
    }

    private fun CoroutineScope.createAudioJob(tempFiles: TempFiles) = launch {
        try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                handlerManager.audioHandler?.startMicRecording(
                    tempFiles.audio,
                    durationMillis = RECORDING_DURATION_MS
                )
            } else {
                Log.e("AudioHandler", "RECORD_AUDIO permission not granted.")
                tempFiles.audio.deleteIfExists()
            }
        } catch (e: SecurityException) {
            Log.e("AudioHandler", "Mic recording failed due to missing permission: ${e.message}")
            tempFiles.audio.deleteIfExists()
        } catch (e: Exception) {
            Log.e("AudioHandler", "Mic recording failed: ${e.message}")
            tempFiles.audio.deleteIfExists()
        }
    }

    private fun CoroutineScope.createCameraJob(tempFiles: TempFiles) = launch {
        try {
            handlerManager.cameraHandler?.captureImage(outputFile = tempFiles.image)
        } catch (e: Exception) {
            Log.e("CameraHandler", "Capture failed: ${e.message}")
            tempFiles.image.deleteIfExists()
        }
    }

    private fun processCompletedFiles(tempFiles: TempFiles) {
        // Always save sensor data
        preferencesManager.currentSensorIndex = saveFileCircular(
            sensorDir, "sensor", ".txt", tempFiles.sensor, preferencesManager.currentSensorIndex
        )

        // Save audio if available and valid
        if (handlerManager.audioStatus && tempFiles.audio.isValidFile()) {
            preferencesManager.currentAudioIndex = saveFileCircular(
                audioDir, "audio", ".wav", tempFiles.audio, preferencesManager.currentAudioIndex
            )
        } else if (handlerManager.audioStatus) {
            Log.w(TAG, "Audio file was expected but not created or is empty: ${tempFiles.audio.name}")
            tempFiles.audio.deleteIfExists()
        }

        // Save image if available and valid
        if (handlerManager.cameraStatus && tempFiles.image.isValidFile()) {
            preferencesManager.currentImageIndex = saveFileCircular(
                imageDir, "image", ".jpg", tempFiles.image, preferencesManager.currentImageIndex
            )
        } else if (handlerManager.cameraStatus) {
            Log.w(TAG, "Image file was expected but not created or is empty: ${tempFiles.image.name}")
            tempFiles.image.deleteIfExists()
        }
    }

    private fun cleanupTempFiles(tempFiles: TempFiles) {
        tempFiles.audio.deleteIfExists()
        tempFiles.sensor.deleteIfExists()
        tempFiles.image.deleteIfExists()
    }

    private fun File.isValidFile(): Boolean = exists() && length() > 0

    private fun File.deleteIfExists() {
        if (exists()) delete()
    }

    private fun saveFileCircular(
        fileDir: File,
        prefix: String,
        extension: String,
        tempFile: File,
        currentIndex: Int
    ): Int {
        val finalFileName = "${prefix}_${currentIndex}$extension"
        val finalFile = File(fileDir, finalFileName)

        tempFile.copyTo(finalFile, overwrite = true)
        tempFile.delete()

        val nextIndex = (currentIndex + 1) % MAX_CIRCULAR_FILES
        Log.d(TAG, "Saved $finalFileName. Next index will be $nextIndex for $prefix")
        return nextIndex
    }

    fun stopRecording() {
        recordingScope?.cancel()
        recordingScope = null
    }
}