package com.example.serviceapp.service

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresPermission
import java.io.File
import java.io.FileOutputStream
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

    @RequiresPermission(Manifest.permission.CAMERA)
    fun initializeCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.first() // Use the first available camera

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSize = configMap?.getOutputSizes(ImageFormat.JPEG)?.firstOrNull() ?: Size(640, 480)

        imageReader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val photoTimestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.getDefault()).format(Date())
            val imageFile = File(context.filesDir, "photo_${photoTimestamp}.jpg")
            FileOutputStream(imageFile).use { it.write(bytes) }
            image.close()
        }, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val surface = imageReader.surface
                val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                }

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
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
        }, backgroundHandler)
    }

    suspend fun captureImage() {
        cameraSession?.capture(
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
            }.build(),
            null,
            backgroundHandler
        )
    }

    fun releaseCamera() {
        cameraSession?.close()
        cameraDevice?.close()
        imageReader.close()
        stopBackgroundThread()
    }
}