package com.example.serviceapp.service.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class CameraHandler(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also {
            it.start()
            backgroundHandler = Handler(it.looper)
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    fun initializeCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CameraHandler", "Camera permission not granted")
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Try front camera first, then fallback to back camera
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            Log.e("CameraHandler", "No available camera found")
            return
        }

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSize = configMap?.getOutputSizes(ImageFormat.JPEG)?.firstOrNull() ?: Size(640, 480)

        imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val imageFile = nextImageOutputFile
            if (imageFile != null) {
                try {
                    FileOutputStream(imageFile).use { it.write(bytes) }
                } catch (e: IOException) {
                    Log.e("CameraHandler", "Failed to save image: ${e.message}")
                }
            } else {
                Log.e("CameraHandler", "No output file specified for image")
            }
            FileOutputStream(imageFile).use { it.write(bytes) }
            image.close()
        }, backgroundHandler)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    val surface = imageReader.surface

                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraSession = session
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("CameraHandler", "Camera session configuration failed")
                        }
                    }, backgroundHandler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    cameraSession?.close()
                    cameraSession = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e("CameraHandler", "Camera error: $error")
                    device.close()
                    cameraDevice = null
                    cameraSession?.close()
                    cameraSession = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Failed to open camera: ${e.message}")
        }

    }

    private var nextImageOutputFile: File? = null

    fun captureImage(outputFile: File) {
        try {
            if (cameraDevice == null || cameraSession == null) {
                Log.w("CameraHandler", "Camera not ready for capture")
                return
            }

            nextImageOutputFile = outputFile // Save the file path for later use in callback

            val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
            }

            cameraSession?.capture(request.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraHandler", "Camera access exception during capture: ${e.message}")
        } catch (e: Exception) {
            Log.e("CameraHandler", "Unexpected exception during capture: ${e.message}")
        }
    }

    fun restartCamera2() {
        // 1. Clean up the camera session and device if open
        try {
            cameraSession?.close()
            cameraSession = null
        } catch (e: Exception) {
            Log.e("CameraHandler", "Error closing cameraSession: ${e.message}")
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e("CameraHandler", "Error closing cameraDevice: ${e.message}")
        }

        // 2. Close imageReader safely if initialized
        if (::imageReader.isInitialized) {
            try {
                imageReader.close()
            } catch (e: Exception) {
                Log.e("CameraHandler", "Error closing imageReader: ${e.message}")
            }
        }

        // 3. Stop and restart the background thread
        try {
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e("CameraHandler", "Error stopping background thread: ${e.message}")
        }

        try {
            startBackgroundThread()
        } catch (e: Exception) {
            Log.e("CameraHandler", "Error starting background thread: ${e.message}")
        }

        // 4. Re-initialize the camera after a brief delay
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                initializeCamera()
            } catch (e: Exception) {
                Log.e("CameraHandler", "Error initializing camera: ${e.message}")
            }
        }, 400) // Small delay ensures previous resources are released
    }

    fun waitForCameraReady(
        retryIntervalMillis: Long = 5000,
        maxRetries: Int = 5,
        onReady: () -> Unit,
        onFailed: (() -> Unit)? = null
    ) {
        var attempt = 0
        val handler = Handler(Looper.getMainLooper())

        val checkRunnable = object : Runnable {
            override fun run() {
                if (cameraDevice != null && cameraSession != null) {
                    Log.d("CameraHandler", "Camera is ready.")
                    onReady()
                } else {
                    attempt++
                    Log.w("CameraHandler", "Camera not ready. Retrying... ($attempt/$maxRetries)")
                    restartCamera2()
                    if (attempt < maxRetries) {
                        handler.postDelayed(this, retryIntervalMillis)
                    } else {
                        Log.e("CameraHandler", "Camera failed to become ready after $maxRetries attempts.")
                        onFailed?.invoke()
                    }
                }
            }
        }

        // Start first check immediately
        handler.post(checkRunnable)
    }

    fun releaseCamera() {
        cameraSession?.close()
        cameraDevice?.close()
        imageReader.close()
        stopBackgroundThread()
    }
}