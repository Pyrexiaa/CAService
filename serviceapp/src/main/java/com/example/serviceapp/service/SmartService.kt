package com.example.serviceapp.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartService : Service() {

    private var sensorScore = 0
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
        cameraHandler = CameraHandler(this)
        cameraHandler.startBackgroundThread()

        try {
            cameraHandler.initializeCamera()
        } catch (e: Exception) {
            Log.e("SmartService", "Camera initialization failed: ${e.message}")
            sendConnectionStatus("Camera init failed. Stopping service.")
            stopSelf()
            return
        }

        audioHandler = AudioHandler(this)
        if (!audioHandler.ensureMicPermissionGranted()) {
            stopSelf()
            return
        }
        startForegroundServiceWithNotification()
    }

    // Handle cases where user might revoke mic or camera permission while the service is running.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!audioHandler.ensureMicPermissionGranted()) {
            sendConnectionStatus("Mic permission revoked. Stopping service.")
            stopSelf()
        }
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

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.getDefault()).format(Date())
        val audioFile = File(filesDir, "audio_${timestamp}.pcm")
        val sensorDataFile = File(filesDir, "sensor_${timestamp}.txt")

        CoroutineScope(Dispatchers.IO).launch {
            val writer = try {
                BufferedWriter(FileWriter(sensorDataFile))
            } catch (e: IOException) {
                Log.e("SmartService", "Failed to create sensor data file: ${e.message}")
                sendConnectionStatus("Sensor log file creation failed.")
                return@launch
            }

            val sensorJob = launch { sensorHandler.logSensorData(writer) }

            // Handle unexpected exception if the mic recording failed.
            val audioJob = launch {
                try {
                    audioHandler.startMicRecording(audioFile)
                } catch (e: Exception) {
                    Log.e("SmartService", "Mic recording failed: ${e.message}")
                    sendConnectionStatus("Mic recording failed: ${e.message}")
                }
            }

            // Since the capture image is a suspend function, this will be the total runtime for sensor and audio job
            val imageJob = launch {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 60_000L) {
                    try {
                        cameraHandler.captureImage()
                    } catch (e: Exception) {
                        Log.e("CameraHandler", "Capture failed: ${e.message}")
                    }
                    delay(1000L)
                }
            }

            joinAll(sensorJob, audioJob, imageJob) // Wait for all tasks to complete

            writer.flush()
            writer.close()

            withContext(Dispatchers.Main) {
                audioHandler.stopMicRecording()
                sendConnectionStatus("Recording session completed.\nAudio: ${audioFile.name}\nSensor: ${sensorDataFile.name}")
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
        audioHandler.stopMicRecording()
        cameraHandler.releaseCamera()
        cameraHandler.stopBackgroundThread()
    }
}