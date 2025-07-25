package com.example.serviceapp.ui.home.utils.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.serviceapp.models.face.FaceRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceProcessor(private val context: Context) {
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val faceDetector = FaceDetection.getClient(detectorOptions)

    fun processFaceForEnrollment(
        imageBitmap: Bitmap,
        orientation: String,
        faceRecognizer: FaceRecognizer,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        val rotatedBitmap = ImageUtils.rotateBitmap90AntiClockwise(imageBitmap)
        val rotation = ImageUtils.getRotationDegrees(context)
        val inputImage = InputImage.fromBitmap(rotatedBitmap, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val originalBitmap = inputImage.bitmapInternal ?: run {
                        onComplete(false, "Failed to get bitmap")
                        return@addOnSuccessListener
                    }
                    val faceBitmap = ImageUtils.cropFace(originalBitmap, face.boundingBox)
                    faceRecognizer.enrollEmbedding(faceBitmap, orientation)
                    onComplete(true, "Face enrolled: $orientation")
                } else {
                    onComplete(false, "No face detected ($orientation)")
                }
                Log.d("FaceProcessor", "Processing face for orientation: $orientation")
            }
            .addOnFailureListener { e ->
                onComplete(false, "Detection failed ($orientation): ${e.message}")
            }
    }

    fun verifyFace(
        bitmap: Bitmap,
        faceRecognizer: FaceRecognizer,
        onResult: (confidence: Float, isMatch: Boolean) -> Unit
    ) {
        val rotatedBitmap = ImageUtils.rotateBitmap90AntiClockwise(bitmap)
        val rotation = ImageUtils.getRotationDegrees(context)
        val inputImage = InputImage.fromBitmap(rotatedBitmap, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val originalBitmap = inputImage.bitmapInternal ?: run {
                        onResult(0f, false)
                        return@addOnSuccessListener
                    }
                    val faceBitmap = ImageUtils.cropFace(originalBitmap, face.boundingBox)
                    val similarity = faceRecognizer.verifyFace(faceBitmap)
                    val isMatch = similarity > 0.7548f
                    onResult(similarity, isMatch)
                } else {
                    onResult(0f, false)
                }
            }
            .addOnFailureListener {
                onResult(0f, false)
            }
    }
}