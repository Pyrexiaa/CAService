package com.example.serviceapp.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.reflect.Array.get
import java.lang.reflect.Array.set
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit
import java.io.FileOutputStream

class SmartService : Service() {

    private var isActive = true
    private var sensorScore = 0
    private var sensorStatus =  true
    private var cameraStatus = true
    private var audioStatus = true
    private lateinit var sensorHandler: SensorHandler
    private lateinit var cameraHandler: CameraHandler
    private lateinit var audioHandler: AudioHandler

    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onCreate() {
        super.onCreate()
        sensorHandler = SensorHandler(this) { sensorType ->
            sensorScore++
            Log.d("SmartService", "Sensor activated: $sensorType | Score: $sensorScore")
        }

        // Initialize camera handler only if permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraHandler = CameraHandler(this)
            cameraHandler.startBackgroundThread()
            try {
                cameraHandler.initializeCamera()
                cameraStatus = true
            } catch (e: Exception) {
                Log.e("SmartService", "Camera initialization failed: ${e.message}")
                sendConnectionStatus("Camera init failed. Continuing without camera.")
                cameraStatus = false // mark camera as unusable
            }
        } else {
            sendConnectionStatus("Camera permission not granted. Continuing without camera.")
        }

        // Initialize audio handler only if permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioHandler = AudioHandler(this)
            audioStatus = true
        } else {
            audioStatus = false // mark audio as unusable
            sendConnectionStatus("Mic permission not granted. Continuing without mic.")
        }

    }

    // Handle cases where user might revoke mic or camera permission while the service is running.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!audioStatus) {
            sendConnectionStatus("Mic permission revoked. Continuing without camera.")
        }
        startForegroundServiceWithNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return messenger.binder
    }

    // region Foreground Service Setup
    private fun startForegroundServiceWithNotification() {
        val channelId = "smart_service_channel"
        val channelName = "Smart Service"

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Smart Service Running")
            .setContentText("Monitoring sensors")
            .build()

        startForeground(1, notification)
    }

    // Helper properties for SharedPreferences access (these need to access the class-level 'prefs')
    var currentAudioIndex: Int
        get() {
            Log.d("SmartServiceRecording", "Getting Audio Index: ${prefs.getInt("audio_index", 0)}")
            return prefs.getInt("audio_index", 0)
        }
        set(value) {
            Log.d("SmartServiceRecording", "Setting Audio Index to: $value")
            val success = prefs.edit().putInt("audio_index", value).commit() // <--- Change to commit()
            if (!success) {
                Log.e("SmartServiceRecording", "Failed to commit audio_index: $value")
            }
        }

    var currentSensorIndex: Int
        get() {
            Log.d("SmartServiceRecording", "Getting Sensor Index: ${prefs.getInt("sensor_index", 0)}")
            return prefs.getInt("sensor_index", 0)
        }
        set(value) {
            Log.d("SmartServiceRecording", "Setting Sensor Index to: $value")
            val success = prefs.edit().putInt("sensor_index", value).commit() // <--- Change to commit()
            if (!success) {
                Log.e("SmartServiceRecording", "Failed to commit sensor_index: $value")
            }
        }

    var currentImageIndex: Int
        get() {
            Log.d("SmartServiceRecording", "Getting Image Index: ${prefs.getInt("image_index", 0)}")
            return prefs.getInt("image_index", 0)
        }
        set(value) {
            Log.d("SmartServiceRecording", "Setting Image Index to: $value")
            val success = prefs.edit().putInt("image_index", value).commit() // <--- Change to commit()
            if (!success) {
                Log.e("SmartServiceRecording", "Failed to commit image_index: $value")
            }
        }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecordingSession() {
        // This CoroutineScope should ideally be managed by a ViewModel or LifecycleOwner
        // to prevent leaks. For demonstration, we'll keep it here, but be mindful.
        // If 'this' is an Activity/Fragment, consider lifecycleScope.launch from androidx.lifecycle.lifecycleScope
        // If 'this' is a Service, consider a SupervisorJob with the Service's lifecycle.
        val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        recordingScope.launch {
            Log.d("SmartServiceRecording", "Started")
            val audioDir = File(filesDir, "audio").apply { mkdirs() }
            val sensorDir = File(filesDir, "sensor").apply { mkdirs() }
            val imageDir = File(filesDir, "image").apply { mkdirs() }

            val MAX_CIRCULAR_FILES = 60 // Total files from index 0 to 59

            // Modified function to save file with circular logic
            fun saveFileCircular(fileDir: File, prefix: String, extension: String, tempFile: File, currentIndex: Int): Int {
                // Determine the final filename using the current index
                val finalFileName = "${prefix}_${currentIndex}$extension"
                val finalFile = File(fileDir, finalFileName)

                // Overwrite the file at this index
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete() // Delete the temporary file

                // Calculate the next index for the next recording
                val nextIndex = (currentIndex + 1) % MAX_CIRCULAR_FILES
                Log.d("SmartServiceRecording", "Saved ${finalFileName}. Next index will be $nextIndex for $prefix")
                return nextIndex
            }

            while (isActive) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.getDefault()).format(Date())

                // Create temporary files
                val tempAudioFile = File(audioDir, "temp_audio_$timestamp.wav")
                val tempSensorFile = File(sensorDir, "temp_sensor_$timestamp.txt")
                val tempImageFile = File(imageDir, "temp_image_$timestamp.jpg")

                val writer = try {
                    BufferedWriter(FileWriter(tempSensorFile))
                } catch (e: IOException) {
                    Log.e("SmartService", "Sensor file creation failed: ${e.message}")
                    delay(1000L) // Wait a bit before retrying
                    continue
                }

                // Launch all required jobs concurrently
                val jobs = mutableListOf<Job>()

                val sensorJob = launch {
                    try {
                        // Assuming sensorHandler.logSensorData writes to the provided writer
                        // and handles its own internal buffering/flushing before returning
                        sensorHandler.logSensorData(writer, durationMillis = 1000L)
                    } catch (e: Exception) {
                        Log.e("SensorHandler", "Sensor logging failed: ${e.message}")
                    } finally {
                        // Ensure writer is closed for sensor data after logging
                        try {
                            writer.flush()
                            writer.close()
                        } catch (e: IOException) {
                            Log.e("SmartService", "Error closing sensor writer: ${e.message}")
                        }
                    }
                }
                jobs.add(sensorJob)

                if (audioStatus) {
                    val audioJob = launch {
                        try {
                            audioHandler.startMicRecording(tempAudioFile, durationMillis = 1000L)
                        } catch (e: Exception) {
                            Log.e("AudioHandler", "Mic recording failed: ${e.message}")
                            // Clean up temp file if recording failed
                            if (tempAudioFile.exists()) tempAudioFile.delete()
                        }
                    }
                    jobs.add(audioJob)
                }

                if (cameraStatus) {
                    val imageJob = launch {
                        try {
                            cameraHandler.captureImage(outputFile = tempImageFile)

                        } catch (e: Exception) {
                            Log.e("CameraHandler", "Capture failed: ${e.message}")
                            if (tempImageFile.exists()) tempImageFile.delete()
                        }
                    }
                    jobs.add(imageJob)
                }


                // Wait for all active capture jobs to complete
                jobs.joinAll()

                // Save files using circular logic and update indices
                // Only save if the temporary file was actually created/filled
                // For sensor data, the temp file should be valid since we wrote to it via 'writer'
                currentSensorIndex = saveFileCircular(sensorDir, "sensor", ".txt", tempSensorFile, currentSensorIndex)


                if (audioStatus && tempAudioFile.exists() && tempAudioFile.length() > 0) {
                    currentAudioIndex = saveFileCircular(audioDir, "audio", ".wav", tempAudioFile, currentAudioIndex)
                } else if (audioStatus) {
                    Log.w("SmartService", "Audio file was expected but not created or is empty: ${tempAudioFile.name}")
                    if (tempAudioFile.exists()) tempAudioFile.delete() // Ensure it's cleaned up
                }

                if (cameraStatus && tempImageFile.exists() && tempImageFile.length() > 0) {
                    currentImageIndex = saveFileCircular(imageDir, "image", ".jpg", tempImageFile, currentImageIndex)
                } else if (cameraStatus) {
                    Log.w("SmartService", "Image file was expected but not created or is empty: ${tempImageFile.name}")
                    if (tempImageFile.exists()) tempImageFile.delete() // Ensure it's cleaned up
                }

                // The delay for the next cycle
                delay(500L)
            }

            // This block will be executed if the 'while (isActive)' loop finishes
            // (e.g., due to cancellation of recordingScope)
            withContext(Dispatchers.Main) {
                sendConnectionStatus("Rolling session stopped. Last $MAX_CIRCULAR_FILES segments stored.")
            }
        }
    }

    // region IPC via Messenger
    companion object {
        const val MSG_GET_SENSOR = 1
        const val MSG_SENSOR_RESPONSE = 2
        const val MSG_START_RECORDING = 3
    }

    private val messenger = Messenger(IncomingHandler())

    @SuppressLint("HandlerLeak")
    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_GET_SENSOR -> {
                    val reply = msg.replyTo
                    val response = Message.obtain(null, MSG_SENSOR_RESPONSE)
                    val data = Bundle().apply {
                        putString("sensor_status", "Opened")
                        putInt("sensor_score", sensorScore)
                    }
                    response.data = data
                    reply.send(response)
                    sendConnectionStatus("Replied with score: $sensorScore")
                }

                MSG_START_RECORDING -> {
                    startRecordingSession()
                    sendConnectionStatus("Started 1-minute recording session")
                }
            }
        }
    }

    private fun sendConnectionStatus(status: String) {
        val intent = Intent("SERVICE_STATUS").apply {
            putExtra("status", status)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorHandler.stopListening()
        if (audioStatus) {
            audioHandler.stopMicRecording()
            audioStatus = false
        }
        if (cameraStatus) {
            cameraHandler.releaseCamera()
            cameraHandler.stopBackgroundThread()
            cameraStatus = false
        }
    }
}