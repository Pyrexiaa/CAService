package com.example.serviceapp.service.processors

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// This class is used for starting image loop to display the latest image on dashboard
class ImageProcessor(
    private val context: Context,
    private val imageView: ImageView,
    private val coroutineScope: CoroutineScope
) {

    private var imageJob: Job? = null
    private val imageDir = File(context.filesDir, "image").apply { mkdirs() }

    private val recordingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    private val smartServiceImageIndexKey = "image_index"
    private val maxCircularFiles = 60

    // Store the name of the last successfully displayed file to prevent redundant updates
    private var lastDisplayedFileName: String? = null

    private var lastRecordedImageIndex: Int
        get() {
            val index = recordingPrefs.getInt(smartServiceImageIndexKey, 0)
            Log.d("ImageProcessor", "Getting last recorded image index from RecordingPrefs: $index")
            return index
        }
        set(value) {
            // This property is managed by SmartServiceRecording, so we generally don't set it here.
            Log.w("ImageProcessor", "Attempted to set lastRecordedImageIndex from ImageProcessor. This index is managed by SmartServiceRecording.")
        }


    fun startImageLoop() {
        imageJob = coroutineScope.launch {
            Log.d("ImageProcessor", "Starting image loop.")

            while (isActive) {
                var imageFoundAndDisplayed = false

                // Calculate the index of the most recently *completed* file by the recorder.
                val latestRecordedFileIndex = (lastRecordedImageIndex - 1 + maxCircularFiles) % maxCircularFiles

                val targetFileName = "image_${latestRecordedFileIndex}.jpg"
                val targetImageFile = File(imageDir, targetFileName)

                // --- Optimization Check: Is this the same file we displayed last? ---
                if (targetFileName == lastDisplayedFileName) {
                    Log.d("ImageProcessor", "Image $targetFileName is already displayed. Skipping bitmap setting.")
                    imageFoundAndDisplayed = true // Consider it "displayed" for this cycle
                } else if (targetImageFile.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(targetImageFile.absolutePath)

                        if (bitmap != null) {
                            // Rotate the bitmap 90 degrees anti-clockwise
                            val matrix = Matrix().apply { postRotate(-90f) }
                            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                            // Display the latest bitmap on imageView
                            imageView.setImageBitmap(rotatedBitmap)

                            // Update the last displayed file name
                            lastDisplayedFileName = targetFileName
                            Log.d("ImageProcessor", "Displayed new image (rotated): $targetFileName")
                            imageFoundAndDisplayed = true
                        } else {
                            Log.e("ImageProcessor", "BitmapFactory.decodeFile returned null for ${targetImageFile.name}")
                        }
                    } catch (e: Exception) {
                        // Continue to the next image if decoding fails
                        Log.e("ImageProcessor", "Error decoding image ${targetImageFile.name}: ${e.message}")
                    }
                } else {
                    Log.d("ImageProcessor", "Latest image file not found: ${targetFileName}. Waiting for recorder...")
                }


                if (!imageFoundAndDisplayed) {
                    Log.d("ImageProcessor", "No new image found or successfully displayed in this cycle.")
                }

                delay(200) // Always delay for 200ms before the next check
            }
        }
    }

    fun stopImageLoop() {
        imageJob?.cancel()
        imageJob = null
        lastDisplayedFileName = null // Reset on stop
        Log.d("ImageProcessor", "Image loop stopped.")
    }
}
