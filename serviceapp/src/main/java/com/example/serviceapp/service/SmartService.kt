package com.example.serviceapp.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

class SmartService : Service() {

    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private lateinit var recorder: AudioRecord

    private var isCameraOpened = false
    private var isMicRecording = false
    private var sensorScore = 0
    private val activeSensors = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        initializeSensors()
        checkAndStartMic()
        checkAndOpenCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(sensorListener)

        if (isMicRecording) {
            recorder.stop()
            recorder.release()
        }

        if (isCameraOpened) {
            // Ideally handle camera close here via a saved reference
            Log.d("SmartService", "Camera was open, should be closed here")
        }
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
//            .setSmallIcon(R.drawable.ic_notification) // Make sure this icon exists
            .build()

        startForeground(1, notification)
    }
    // endregion

    // region Sensor Handling
    private fun initializeSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD
        )

        sensors.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            handleSensorChange(event)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun handleSensorChange(event: SensorEvent) {
        val type = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "ACC"
            Sensor.TYPE_GYROSCOPE -> "GYRO"
            Sensor.TYPE_MAGNETIC_FIELD -> "MAG"
            else -> null
        }

        type?.let {
            if (activeSensors.add(it)) {
                sensorScore++
                Log.d("SensorScore", "Sensor activated: $it, Score: $sensorScore")
            }
        }
    }
    // endregion

    // region Camera Handling
    private fun checkAndOpenCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            Log.w("SmartService", "Camera permission not granted")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        cameraManager.openCamera(cameraId, cameraStateCallback, null)
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            isCameraOpened = true
            sensorScore++
            Log.d("SmartService", "Camera opened")
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            isCameraOpened = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            isCameraOpened = false
            Log.e("SmartService", "Camera error: $error")
        }
    }
    // endregion

    // region Microphone Handling
    private fun checkAndStartMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicRecording()
        } else {
            Log.w("SmartService", "Microphone permission not granted")
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        recorder.startRecording()
        isMicRecording = true
        sensorScore++
        Log.d("SmartService", "Mic recording started")
    }
    // endregion

    // region IPC via Messenger
    companion object {
        const val MSG_GET_SENSOR = 1
        const val MSG_SENSOR_RESPONSE = 2
    }

    private val messenger = Messenger(IncomingHandler())

    @SuppressLint("HandlerLeak")
    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_GET_SENSOR) {
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
        }
    }

    private fun sendConnectionStatus(status: String) {
        val intent = Intent("SERVICE_STATUS").apply {
            putExtra("status", status)
        }
        sendBroadcast(intent)
    }
    // endregion
}