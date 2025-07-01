package com.example.serviceapp.main_utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import com.example.serviceapp.models.face.FaceRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageProcessor(
    private val context: Context,
    private val imageView: ImageView,
    private val coroutineScope: CoroutineScope
) {
    private lateinit var faceRecognizer: FaceRecognizer

    private var imageJob: Job? = null
    private val imageDir = File(context.filesDir, "image").apply { mkdirs() }

    // Directory for confidence scores
    private val confidenceDir = File(context.filesDir, "confidence_scores").apply { mkdirs() }
    private val confidenceScoreFileName = "face.txt"
    private val confidenceScoreFile = File(confidenceDir, confidenceScoreFileName)

    private val recordingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    private val SMART_SERVICE_IMAGE_INDEX_KEY = "image_index"
    private val MAX_CIRCULAR_FILES = 60 // Max index 59, so (MAX_CIRCULAR_FILES - 1)

    // Store the name of the last successfully displayed file to prevent redundant updates
    private var lastDisplayedFileName: String? = null

    private var lastRecordedImageIndex: Int
        get() {
            val index = recordingPrefs.getInt(SMART_SERVICE_IMAGE_INDEX_KEY, 0)
            Log.d("ImageProcessor", "Getting last recorded image index from RecordingPrefs: $index")
            return index
        }
        set(value) {
            // This property is managed by SmartServiceRecording, so we generally don't set it here.
            Log.w("ImageProcessor", "Attempted to set lastRecordedImageIndex from ImageProcessor. This index is managed by SmartServiceRecording.")
        }


    fun startImageLoop() {
        faceRecognizer = FaceRecognizer(context, "light_cnn_float16.tflite")

        imageJob = coroutineScope.launch {
            // Initial log - currentDisplayIndex is not actually used in the loop as much now.
            // The logic focuses on `latestRecordedFileIndex`.
            Log.d("ImageProcessor", "Starting image loop.")

            while (isActive) {
                var imageFoundAndDisplayed = false

                // Calculate the index of the most recently *completed* file by the recorder.
                val latestRecordedFileIndex = (lastRecordedImageIndex - 1 + MAX_CIRCULAR_FILES) % MAX_CIRCULAR_FILES

                val targetFileName = "image_${latestRecordedFileIndex}.jpg"
                val targetImageFile = File(imageDir, targetFileName)

                // --- Optimization Check: Is this the same file we displayed last? ---
                if (targetFileName == lastDisplayedFileName) {
                    Log.d("ImageProcessor", "Image ${targetFileName} is already displayed. Skipping bitmap setting.")
                    imageFoundAndDisplayed = true // Consider it "displayed" for this cycle
                } else if (targetImageFile.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(targetImageFile.absolutePath)

                        if (bitmap != null) {

//                            // --- Process Face Recognition and Log Score ---
//                            val similarity = faceRecognizer.verifyFace(bitmap)
//                            writeConfidenceScore(targetFileName, similarity)
//                            // ------

                            imageView.setImageBitmap(bitmap)
                            lastDisplayedFileName = targetFileName // Update the last displayed file name
                            Log.d("ImageProcessor", "Displayed new image: ${targetFileName}")
                            imageFoundAndDisplayed = true
                        } else {
//                            writeConfidenceScore(targetFileName, 0f)
                            Log.e("ImageProcessor", "BitmapFactory.decodeFile returned null for ${targetImageFile.name}")
                        }
                    } catch (e: Exception) {
                        Log.e("ImageProcessor", "Error decoding image ${targetImageFile.name}: ${e.message}")
                        // Continue to the next image if decoding fails
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

    private fun writeConfidenceScore(imageFileName: String, score: Float) {
        val newEntry = "$imageFileName, Score: $score\n"

        // --- NEW LOGIC: Create the confidence score file on start if needed ---
        try {
            if (!confidenceScoreFile.exists()) {
                confidenceScoreFile.createNewFile()
                Log.d("ImageProcessorConfidence", "Created new confidence score file: ${confidenceScoreFile.absolutePath}")
            }
        } catch (e: IOException) {
            Log.e("ImageProcessorConfidence", "Error creating confidence score file: ${e.message}", e)
        }
        // --- END NEW LOGIC ---

        try {
            // Step 1: Read all lines (if the file exists)
            val existingLines = if (confidenceScoreFile.exists()) {
                confidenceScoreFile.readLines()
            } else {
                emptyList()
            }

            // Step 2: Filter out previous entry for this imageFileName
            val updatedLines = existingLines.filterNot { it.startsWith("$imageFileName,") }

            // Step 3: Add the new entry at the end
            val finalLines = updatedLines + newEntry.trimEnd()

            // Step 4: Overwrite the file with the updated content
            confidenceScoreFile.writeText(finalLines.joinToString("\n") + "\n")

            Log.d("ImageProcessor", "Updated confidence score for $imageFileName: $score")
        } catch (e: IOException) {
            Log.e("ImageProcessor", "Error updating confidence score file: ${e.message}", e)
        }
    }

    fun stopImageLoop() {
        imageJob?.cancel()
        imageJob = null
        lastDisplayedFileName = null // Reset on stop
        Log.d("ImageProcessor", "Image loop stopped.")
    }
}
