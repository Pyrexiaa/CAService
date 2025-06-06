package com.example.serviceapp.service

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
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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


    fun releaseCamera() {
        cameraSession?.close()
        cameraDevice?.close()
        imageReader.close()
        stopBackgroundThread()
    }
}