package com.example.serviceapp.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.serviceapp.service.handlers.HandlerManager
import com.example.serviceapp.service.ipc.IpcManager
import com.example.serviceapp.service.preferences.PreferencesManager
import com.example.serviceapp.service.recordings.RecordingManager

// This class is used to store image, audio and sensor readings into internal storage
// Acts as a coordinator, along with classes such as handlers, ipc, preferences and recording manager
class SmartService : Service() {

    companion object {
        private const val TAG = "SmartService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smart_service_channel"
        private const val CHANNEL_NAME = "Smart Service"

        // IPC Message Types
        const val MSG_GET_SENSOR = 1
        const val MSG_SENSOR_RESPONSE = 2
        const val MSG_START_RECORDING = 3
        const val MSG_GET_VERIFICATION_SCORES = 4
        const val MSG_VERIFICATION_SCORES_RESPONSE = 5
    }

    // Modular components
    private lateinit var handlerManager: HandlerManager
    private lateinit var recordingSessionManager: RecordingManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var ipcHandler: IpcManager

    private val messenger = Messenger(IncomingHandler())

    override fun onCreate() {
        super.onCreate()
        initializeManagers()
        startForegroundServiceWithNotification()
    }

    private fun initializeManagers() {
        preferencesManager = PreferencesManager(this)
        handlerManager = HandlerManager(this)
        recordingSessionManager = RecordingManager(this, handlerManager, preferencesManager)
        ipcHandler = IpcManager(this, handlerManager, recordingSessionManager) { status ->
            sendConnectionStatus(status)
        }

        // Initialize all handlers
        handlerManager.initializeAll(
            onSensorActivated = { sensorType ->
                ipcHandler.incrementSensorScore()
                Log.d(TAG, "Sensor activated: $sensorType")
            },
            onStatusUpdate = { status ->
                sendConnectionStatus(status)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handlerManager.checkPermissionStatus { status ->
            sendConnectionStatus(status)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun startForegroundServiceWithNotification() {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Service Running")
            .setContentText("Monitoring sensors")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    @SuppressLint("HandlerLeak")
    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_GET_SENSOR -> ipcHandler.handleSensorRequest(msg)
                MSG_START_RECORDING -> ipcHandler.handleStartRecording()
                MSG_GET_VERIFICATION_SCORES -> ipcHandler.handleVerificationScoresRequest(msg)
                else -> Log.w(TAG, "Unknown message type: ${msg.what}")
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
        recordingSessionManager.stopRecording()
        handlerManager.cleanup()
    }
}