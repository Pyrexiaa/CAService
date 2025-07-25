package com.example.serviceapp.ui.home.utils.camera

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.example.serviceapp.ui.home.utils.RecognizerManager
import com.example.serviceapp.ui.home.utils.UIHelper

class FaceCaptureManager(
    private val context: Context,
    private val faceProcessor: FaceProcessor,
    private val cameraManager: CameraManager,
    private val uiHelper: UIHelper
) {
    companion object {
        private val FACE_CAPTURE_PROMPTS = listOf("Center", "Left", "Right", "Up", "Down")
    }

    private var currentPromptIndex = 0
    private val faceCaptures = mutableListOf<Pair<Bitmap, String>>()

    fun startEnrollment(recognizerManager: RecognizerManager, onComplete: () -> Unit) {
        currentPromptIndex = 0
        faceCaptures.clear()
        promptAndCaptureNext(recognizerManager, onComplete)
    }

    private fun promptAndCaptureNext(
        recognizerManager: RecognizerManager,
        onFinished: () -> Unit
    ) {
        if (currentPromptIndex < FACE_CAPTURE_PROMPTS.size) {
            val prompt = FACE_CAPTURE_PROMPTS[currentPromptIndex]

            AlertDialog.Builder(context)
                .setTitle("Face Capture")
                .setMessage("Please face $prompt and press OK to continue.")
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    captureForOrientation(prompt, recognizerManager, onFinished)
                }
                .show()
        } else {
            Toast.makeText(context, "All face captures done!", Toast.LENGTH_SHORT).show()
            recognizerManager.getFaceRecognizer().averageEmbedding()
            onFinished()
        }
    }

    private fun captureForOrientation(
        prompt: String,
        recognizerManager: RecognizerManager,
        onFinished: () -> Unit
    ) {
        uiHelper.showCameraFullscreen()

        if (!cameraManager.isCameraBound()) {
            cameraManager.startCameraPreview()
        }

        // Set up the capture button click listener
        uiHelper.setupCaptureButton("Capture") {
            // Show loading and disable button when capture starts
            uiHelper.showLoadingOverlay()
            uiHelper.setCaptureButtonEnabled(false)

            // Now capture the photo
            cameraManager.capturePhoto(object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()

                    if (bitmap == null) {
                        Toast.makeText(context, "Failed to convert image", Toast.LENGTH_SHORT).show()
                        uiHelper.setCaptureButtonEnabled(true)
                        uiHelper.hideLoadingOverlay()
                        return
                    }

                    faceProcessor.processFaceForEnrollment(
                        bitmap,
                        prompt,
                        recognizerManager.getFaceRecognizer()
                    ) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                        if (success) {
                            faceCaptures.add(Pair(bitmap, prompt))
                        }

                        uiHelper.hideCameraFullscreen()
                        uiHelper.hideLoadingOverlay()
                        uiHelper.setCaptureButtonEnabled(true)
                        currentPromptIndex++

                        promptAndCaptureNext(recognizerManager, onFinished)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("FaceCapture", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                    uiHelper.setCaptureButtonEnabled(true)
                    uiHelper.hideLoadingOverlay()
                }
            })
        }
    }

    fun startVerification(recognizerManager: RecognizerManager) {
        uiHelper.showCameraFullscreen()

        if (!cameraManager.isCameraBound()) {
            cameraManager.startCameraPreview()
        }

        // Set up the capture button click listener
        uiHelper.setupCaptureButton("Verify") {
            // Show loading and disable button when capture starts
            uiHelper.showLoadingOverlay()
            uiHelper.setCaptureButtonEnabled(false)

            cameraManager.capturePhoto(object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()

                    faceProcessor.verifyFace(
                        bitmap,
                        recognizerManager.getFaceRecognizer()
                    ) { confidence, isMatch ->
                        Toast.makeText(
                            context,
                            "Confidence: $confidence, Match: $isMatch",
                            Toast.LENGTH_SHORT
                        ).show()

                        uiHelper.hideCameraFullscreen()
                        uiHelper.hideLoadingOverlay()
                        uiHelper.setCaptureButtonEnabled(true)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Verification", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                    uiHelper.setCaptureButtonEnabled(true)
                    uiHelper.hideLoadingOverlay()
                }
            })
        }
    }

}