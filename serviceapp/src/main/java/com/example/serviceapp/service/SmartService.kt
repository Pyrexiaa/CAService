package com.example.serviceapp.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartService : Service() {

    private var isActive = true
    private var sensorScore = 0
    private var sensorStatus =  true
    private var cameraStatus = true
    private var audioStatus = true
    private lateinit var sensorHandler: SensorHandler
    private lateinit var cameraHandler: CameraHandler
    private lateinit var audioHandler: AudioHandler

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

    // To prevent coroutine scope leak risk
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecordingSession() {
        CoroutineScope(Dispatchers.IO).launch {

            val audioDir = File(filesDir, "audio").apply { mkdirs() }
            val sensorDir = File(filesDir, "sensor").apply { mkdirs() }
            val imageDir = File(filesDir, "image").apply { mkdirs() }

            val maxFiles = 60

            // Reusable function to manage file renaming and rotation
            fun shiftAndSaveFile(fileDir: File, prefix: String, extension: String, tempFile: File) {
                // Delete oldest file
                val oldestFile = File(fileDir, "${prefix}_1$extension")
                if (oldestFile.exists()) {
                    oldestFile.delete()
                }

                // Shift existing files
                for (i in 2..maxFiles) {
                    val oldFile = File(fileDir, "${prefix}_$i$extension")
                    if (oldFile.exists()) {
                        val newFile = File(fileDir, "${prefix}_${i - 1}$extension")
                        oldFile.renameTo(newFile)
                    }
                }

                // Save new file as the latest one
                val finalFile = File(fileDir, "${prefix}_$maxFiles$extension")
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            while (isActive) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.getDefault()).format(Date())
                val tempAudioFile = File(audioDir, "temp_audio_$timestamp.pcm")
                val tempSensorFile = File(sensorDir, "temp_sensor_$timestamp.txt")
                val tempImageFile = File(imageDir, "temp_image_$timestamp.jpg")

                val writer = try {
                    BufferedWriter(FileWriter(tempSensorFile))
                } catch (e: IOException) {
                    Log.e("SmartService", "Sensor file creation failed: ${e.message}")
                    delay(1000L)
                    continue
                }

                val sensorJob = launch {
                    try {
                        sensorHandler.logSensorData(writer, durationMillis = 1000L)
                    } catch (e: Exception) {
                        Log.e("SensorHandler", "Sensor logging failed: ${e.message}")
                    }
                }

                if (audioStatus && cameraStatus) {
                    val audioJob = launch {
                        try {
                            audioHandler.startMicRecording(tempAudioFile, durationMillis = 1000L)
                        } catch (e: Exception) {
                            Log.e("AudioHandler", "Mic recording failed: ${e.message}")
                        }
                    }

                    val imageJob = launch {
                        try {
                            cameraHandler.captureImage(outputFile = tempImageFile)
                        } catch (e: Exception) {
                            Log.e("CameraHandler", "Capture failed: ${e.message}")
                        }
                    }

                    joinAll(sensorJob, audioJob, imageJob)
                } else if (audioStatus) {
                    val audioJob = launch {
                        try {
                            audioHandler.startMicRecording(tempAudioFile, durationMillis = 1000L)
                        } catch (e: Exception) {
                            Log.e("AudioHandler", "Mic recording failed: ${e.message}")
                        }
                    }
                    joinAll(sensorJob, audioJob)
                } else if (cameraStatus) {
                    val imageJob = launch {
                        try {
                            cameraHandler.captureImage(outputFile = tempImageFile)
                        } catch (e: Exception) {
                            Log.e("CameraHandler", "Capture failed: ${e.message}")
                        }
                    }
                    joinAll(sensorJob, imageJob)
                } else {
                    sensorJob.join()
                }

                writer.flush()
                writer.close()

                // Rotate and rename files to maintain max 60
                shiftAndSaveFile(audioDir, "audio", ".pcm", tempAudioFile)
                shiftAndSaveFile(sensorDir, "sensor", ".txt", tempSensorFile)
                shiftAndSaveFile(imageDir, "image", ".jpg", tempImageFile)
            }

            withContext(Dispatchers.Main) {
                sendConnectionStatus("Rolling session stopped. Last 60 segments stored.")
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