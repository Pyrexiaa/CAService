package com.example.serviceapp.ui.verifications.utils.verification.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.example.serviceapp.ui.verifications.utils.IndexManager
import com.example.serviceapp.ui.verifications.utils.VerificationManager
import com.example.serviceapp.ui.verifications.utils.verification.BaseVerificationProcessor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class FaceVerificationProcessor(
    context: Context,
    indexManager: IndexManager,
    private val verificationManager: VerificationManager
) : BaseVerificationProcessor(context, indexManager) {

    override val directoryName = "image"
    override val filePrefix = "image"
    override val fileExtension = "jpg"
    override val confidenceFileName = "face.txt"

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val faceDetector = FaceDetection.getClient(detectorOptions)

    override fun getCurrentIndex(): Int = indexManager.faceIndex

    override fun getMonitoringInterval(): Long = 500L

    override suspend fun processFile(currentIndex: Int): Float {
        val imageFile = File(context.filesDir, "$directoryName/${filePrefix}_${currentIndex}.$fileExtension")
        val bitmap = prepareImageToMatchPreview(imageFile)
            ?: throw IllegalArgumentException("Failed to prepare bitmap")

        return verifyFaceSuspending(bitmap)
    }

    private fun prepareImageToMatchPreview(imageFile: File, targetWidth: Int = 124, targetHeight: Int = 165): Bitmap? {
        if (!imageFile.exists()) return null

        val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        val correctedBitmap = rotateBitmap(originalBitmap, -90f)
        return correctedBitmap.scale(targetWidth, targetHeight)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun cropFace(original: Bitmap, box: android.graphics.Rect): Bitmap {
        val safeBox = android.graphics.Rect(
            box.left.coerceAtLeast(0),
            box.top.coerceAtLeast(0),
            box.right.coerceAtMost(original.width),
            box.bottom.coerceAtMost(original.height)
        )
        return Bitmap.createBitmap(original, safeBox.left, safeBox.top, safeBox.width(), safeBox.height())
    }

    private suspend fun verifyFaceSuspending(bitmap: Bitmap): Float = suspendCancellableCoroutine { continuation ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (!continuation.isActive) return@addOnSuccessListener

                if (faces.isNotEmpty()) {
                    val originalBitmap = inputImage.bitmapInternal ?: return@addOnSuccessListener
                    val faceBitmap = cropFace(originalBitmap, faces[0].boundingBox)

                    val similarity = if (continuation.isActive) {
                        verificationManager.getFaceRecognizer().verifyFace(faceBitmap)
                    } else {
                        0f
                    }
                    continuation.resume(similarity)
                } else {
                    continuation.resume(0f)
                }
            }
            .addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(0f)
                }
            }
    }
}