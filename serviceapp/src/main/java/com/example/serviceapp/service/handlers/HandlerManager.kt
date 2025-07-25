package com.example.serviceapp.service.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

// Handles initialization and management of sensor/camera/audio handlers
// So SmartService just have to initialize HandlerManager
class HandlerManager(private val context: Context) {

    companion object {
        private const val TAG = "HandlerManager"
    }

    private var _sensorHandler: SensorHandler? = null
    private var _cameraHandler: CameraHandler? = null
    private var _audioHandler: AudioHandler? = null

    val sensorHandler: SensorHandler get() = _sensorHandler!!
    val cameraHandler: CameraHandler? get() = _cameraHandler
    val audioHandler: AudioHandler? get() = _audioHandler

    var cameraStatus = false
        private set
    var audioStatus = false
        private set

    fun initializeAll(
        onSensorActivated: (String) -> Unit,
        onStatusUpdate: (String) -> Unit
    ) {
        initializeSensorHandler(onSensorActivated)
        initializeCameraHandler(onStatusUpdate)
        initializeAudioHandler(onStatusUpdate)
    }

    private fun initializeSensorHandler(onSensorActivated: (String) -> Unit) {
        _sensorHandler = SensorHandler(context, onSensorActivated)
        Log.d(TAG, "Sensor handler initialized successfully")
    }

    private fun initializeCameraHandler(onStatusUpdate: (String) -> Unit) {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            onStatusUpdate("Camera permission not granted. Continuing without camera.")
            return
        }

        try {
            _cameraHandler = CameraHandler(context).apply {
                startBackgroundThread()
                initializeCamera()
            }
            cameraStatus = true
            Log.d(TAG, "Camera initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera initialization failed: ${e.message}")
            onStatusUpdate("Camera init failed. Continuing without camera.")
            cameraStatus = false
        }
    }

    private fun initializeAudioHandler(onStatusUpdate: (String) -> Unit) {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            onStatusUpdate("Mic permission not granted. Continuing without mic.")
            return
        }

        _audioHandler = AudioHandler(context)
        audioStatus = true
        Log.d(TAG, "Audio handler initialized successfully")
    }

    fun checkPermissionStatus(onStatusUpdate: (String) -> Unit) {
        if (audioStatus && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audioStatus = false
            onStatusUpdate("Mic permission revoked. Continuing without microphone.")
        }

        if (cameraStatus && !hasPermission(Manifest.permission.CAMERA)) {
            cameraStatus = false
            onStatusUpdate("Camera permission revoked. Continuing without camera.")
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun cleanup() {
        _sensorHandler?.stopListening()
        _audioHandler?.stopMicRecording()
        _cameraHandler?.let {
            it.releaseCamera()
            it.stopBackgroundThread()
        }
    }
}