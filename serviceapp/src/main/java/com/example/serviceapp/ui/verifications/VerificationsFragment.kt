package com.example.serviceapp.ui.verifications

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.serviceapp.databinding.FragmentVerificationsBinding
import com.example.serviceapp.models.audio.AudioRecognizer
import com.example.serviceapp.models.face.FaceRecognizer
import com.example.serviceapp.models.gait.GaitRecognizer
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import androidx.core.graphics.scale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileInputStream
import java.io.FileOutputStream

class VerificationsFragment : Fragment() {

    private var _binding: FragmentVerificationsBinding? = null
    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    private var faceLoopJob: Job? = null
    private val faceScores = mutableListOf<Float>()
    private var previousFaceIndex = -1

    private val audioScores = mutableListOf<Float>()
    private val gaitScores = mutableListOf<Float>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVerificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    // Use face recognition model, not face detection
    private val faceDetector = FaceDetection.getClient(detectorOptions)

    private lateinit var faceRecognizer: FaceRecognizer
    private lateinit var audioRecognizer: AudioRecognizer
    private lateinit var gaitRecognizer: GaitRecognizer

    // Use the same SharedPreferences file name as SmartServiceRecording
    private val recordingPrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    // Key to read the index from RecordingPrefs set
    private val FACE_INDEX_KEY = "image_index"
    private val AUDIO_INDEX_KEY = "audio_index"
    private val SENSOR_INDEX_KEY = "sensor_index"

    private var faceIndex: Int
        get() {
            val index = recordingPrefs.getInt(FACE_INDEX_KEY, 0)
            return index - 1
        }
        // This class should not modify the index
        set(value) {
            Log.w("VerificationsFragment", "Attempted to set faceIndex from VerificationsFragment. This index is managed by SmartServiceRecording.")
        }

    private var audioIndex: Int
        get() {
            val index = recordingPrefs.getInt(AUDIO_INDEX_KEY, 0)
            return index - 1
        }
        // This class should not modify the index
        set(value) {
            Log.w("VerificationsFragment", "Attempted to set audioIndex from VerificationsFragment. This index is managed by SmartServiceRecording.")
        }

    private var gaitIndex: Int
        get() {
            val index = recordingPrefs.getInt(SENSOR_INDEX_KEY, 0)
            return index - 1
        }
        // This class should not modify the index
        set(value) {
            Log.w("VerificationsFragment", "Attempted to set sensorIndex from VerificationsFragment. This index is managed by SmartServiceRecording.")
        }

    private fun getBooleanFromStringPrefs(prefs: SharedPreferences, key: String): Boolean {
        return prefs.contains(key)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val facePrefs = requireContext().getSharedPreferences("FacePrefs", Context.MODE_PRIVATE)
        val audioPrefs = requireContext().getSharedPreferences("AudioPrefs", Context.MODE_PRIVATE)
        val gaitPrefs = requireContext().getSharedPreferences("GaitPrefs", Context.MODE_PRIVATE)

        val isFaceEnrolled = getBooleanFromStringPrefs(facePrefs, "embedding")
        val isAudioEnrolled = getBooleanFromStringPrefs(audioPrefs, "embedding")
        val isGaitEnrolled = getBooleanFromStringPrefs(gaitPrefs, "embedding")

        Log.d("VerificationsFragment", "Face enrolled: $isFaceEnrolled")
        Log.d("VerificationsFragment", "Audio enrolled: $isAudioEnrolled")
        Log.d("VerificationsFragment", "Gait enrolled: $isGaitEnrolled")

        faceRecognizer = FaceRecognizer(requireContext(), "light_cnn_float16.tflite")
        audioRecognizer = AudioRecognizer(requireContext(), "custom_audio_model_float16.tflite", "mfcc_model.tflite")
        gaitRecognizer = GaitRecognizer(requireContext(), "custom_gait_model_float16.tflite")

        // Check if at least one modality is enrolled to load historical data
        if (isFaceEnrolled || isAudioEnrolled || isGaitEnrolled) {
            startFaceVerificationLoop()

            val audioData = if (isAudioEnrolled) loadConfidenceData("audio.txt") else emptyList()
            val gaitData = if (isGaitEnrolled) loadConfidenceData("gait.txt") else emptyList()

            val combinedData = combineConfidenceScores(faceScores, audioScores, gaitScores)
//
//            // Setup and plot historical charts
//            if (faceData.isNotEmpty()) {
//                setupChartAxesAndAppearance(binding.chartFace, "Real-time Face Confidence")
//                plotChartData(binding.chartFace, "Face", faceData)
//            }
            if (audioData.isNotEmpty()) {
                setupChartAxesAndAppearance(binding.chartAudio, "Real-time Audio Confidence")
                plotChartData(binding.chartAudio, "Audio", audioData)
            }
            if (gaitData.isNotEmpty()) {
                setupChartAxesAndAppearance(binding.chartGait, "Real-time Gait Confidence")
                plotChartData(binding.chartGait, "Gait", gaitData)
            }
            if (combinedData.isNotEmpty()) {
                setupChartAxesAndAppearance(binding.chartCombined, "Real-time Combined Confidence")
                plotChartData(binding.chartCombined, "Combined", combinedData)
            }
        }
    }

    fun prepareImageToMatchPreview(imageFile: File, targetWidth: Int = 124, targetHeight: Int = 165): Bitmap? {
        if (!imageFile.exists()) return null

        val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null

        // Force rotate 90 degrees (clockwise)
        val correctedBitmap = rotateBitmap(originalBitmap, -90f)

        return correctedBitmap.scale(targetWidth, targetHeight)
    }


    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
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
                if (!continuation.isActive) return@addOnSuccessListener // Cancelled during detection

                if (faces.isNotEmpty()) {
                    val faceBitmap = cropFace(bitmap, faces[0].boundingBox)

                    // This part is usually fast; still safe-guard
                    val similarity = if (continuation.isActive) {
                        faceRecognizer.verifyFace(faceBitmap)
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

    // --- New Channel for handling image processing requests ---
    // Using Channel.CONFLATED ensures that if new image indices arrive faster than
    // they can be processed, only the latest index is kept, discarding older ones.
    private val _newImageDetectedChannel = Channel<Int>(Channel.CONFLATED)
    private var faceProcessingJob: Job? = null // This job will collect from the channel

    // --- Modified startFaceVerificationLoop to send to the channel ---

    private fun startFaceVerificationLoop() {
        val imageDir = File(requireContext().filesDir, "image")
        if (!imageDir.exists()) {
            Log.w("VerificationsFragment", "Image directory does not exist.")
            return
        }

        // Ensure the processing job is started when the loop starts
        // You might want to call startFaceProcessing() from your Fragment's onResume or similar
        startFaceProcessing() // Call this once to start listening for image indices

        faceLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = faceIndex // Get your current image index
                if (current != previousFaceIndex) {
                    previousFaceIndex = current
                    // Send the new image index to the channel.
                    // trySend is non-suspending and will succeed or fail immediately.
                    // With CONFLATED, it will overwrite the previous value if not consumed.
                    _newImageDetectedChannel.trySend(current).getOrThrow()
                }
                delay(100) // Check for new images every 100ms
            }
        }
    }

    // --- New function to handle the actual face detection and plotting ---
    private fun startFaceProcessing() {
        // Cancel any existing processing job to ensure a fresh start if called multiple times
        faceProcessingJob?.cancel()
        faceProcessingJob = lifecycleScope.launch(Dispatchers.IO) {
            // Collect from the channel. This loop will process one image index at a time.
            // If a new index comes while processing, it will be picked up next,
            // effectively skipping any intermediate images if processing is slow.
            _newImageDetectedChannel.receiveAsFlow().collect { currentIndex ->
                val imageFile = File(requireContext().filesDir, "image/image_${currentIndex}.jpg")
                if (!imageFile.exists()) {
                    Log.d("VerificationsFragment", "Image file does not exist: image_${currentIndex}.jpg")
                    return@collect // Continue to the next item in the channel
                }

                val bitmap = prepareImageToMatchPreview(imageFile)
                if (bitmap == null) {
                    Log.d("VerificationsFragment", "Failed to prepare bitmap for index: $currentIndex")
                    return@collect // Continue
                }

                // No need for 'latestFaceIndex' checks here.
                // The channel handles ensuring we process the 'latest' relevant event.
                // And this 'collect' block ensures sequential processing.
                val similarity = verifyFaceSuspending(bitmap)
                Log.d("VerificationsFragment: ","Image Index: $currentIndex, Similarity: $similarity")

                // Ensure the coroutine is still active before updating UI/files
                // This is implicitly handled by the 'isActive' of the outer launch.
                // If the processing job is cancelled, this block won't continue.

                val faceTxtFile = File(requireContext().filesDir, "confidence_scores/face.txt")
                // Ensure the parent directory exists
                faceTxtFile.parentFile?.mkdirs()
                faceTxtFile.appendText("$similarity\n")
                faceScores.add(similarity)

                withContext(Dispatchers.Main) {
                    setupChartAxesAndAppearance(binding.chartFace, "Real-time Face Confidence")
                    plotChartData(binding.chartFace, "Face", faceScores)
                }
            }
        }
    }

    // --- Renamed from setupConfidenceChartBasics to avoid ambiguity ---
    // This function sets up the basic axes and appearance of ANY LineChart.
    private fun setupChartAxesAndAppearance(chart: LineChart, chartTitle: String) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.setBackgroundColor(Color.TRANSPARENT)

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.setDrawAxisLine(true)
        xAxis.setLabelCount(5, true)
        xAxis.textColor = Color.WHITE

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.setDrawAxisLine(true)
        leftAxis.textColor = Color.WHITE
        leftAxis.axisMinimum = 0f
        leftAxis.axisMaximum = 1f // Confidence scores are usually 0-1

        chart.axisRight.isEnabled = false
        chart.legend.textColor = Color.WHITE
        chart.description.text = chartTitle // Use the passed title
        chart.description.textColor = Color.WHITE
        chart.description.textSize = 12f
    }

    // --- NEW: Function to plot data on a given chart (for historical data) ---
    private fun plotChartData(chart: LineChart, dataSetName: String, data: List<Float>) {
        if (data.isEmpty()) {
            chart.data = null
            chart.invalidate()
            return
        }

        val entries = data.mapIndexed { index, value ->
            Entry(index.toFloat(), value)
        }

        val dataSet = LineDataSet(entries, dataSetName)
        dataSet.color = when (dataSetName) { // Customize colors based on data type
            "Face" -> Color.BLUE
            "Audio" -> Color.GREEN
            "Gait" -> Color.RED
            "Combined" -> Color.YELLOW
            else -> Color.CYAN
        }
        dataSet.valueTextColor = Color.BLACK // Values on points
        dataSet.setDrawValues(false) // Typically don't draw values on points for many data points
        dataSet.setDrawCircles(false) // Don't draw individual circles
        dataSet.lineWidth = 2f

        dataSet.setDrawFilled(true)
        dataSet.fillColor = dataSet.color // Use the dataset color for fill
        dataSet.fillAlpha = 50 // Semi-transparent

        chart.data = LineData(dataSet)
        chart.notifyDataSetChanged()
        chart.invalidate() // Refresh chart
        Log.d("VerificationsFragment", "Chart '${dataSetName}' updated with ${entries.size} entries.")
    }

    // This loadConfidenceData now assumes files like "face.txt", "audio.txt", "gait.txt"
    // that contain only float values, one per line.
    private fun loadConfidenceData(fileName: String): List<Float> {
        val confidenceDir = File(requireContext().filesDir, "confidence_scores")
        val file = File(confidenceDir, fileName)
        if (!file.exists()) {
            Log.d("VerificationsFragment", "Confidence data file not found: ${file.name}")
            return emptyList()
        }
        if (file.length() == 0L) {
            Log.d("VerificationsFragment", "Confidence data file is empty: ${file.name}")
            return emptyList()
        }

        return try {
            file.readLines()
                .mapNotNull { it.toFloatOrNull() }
        } catch (e: Exception) {
            Log.e("VerificationsFragment", "Error reading or parsing ${file.name}: ${e.message}", e)
            emptyList()
        }
    }

    private fun combineConfidenceScores(
        face: List<Float>,
        audio: List<Float>,
        gait: List<Float>
    ): List<Float> {
        val maxLength = maxOf(face.size, audio.size, gait.size)
        val combined = mutableListOf<Float>()

        for (i in 0 until maxLength) {
            val faceScore = face.getOrNull(i) ?: 0f
            val audioScore = audio.getOrNull(i) ?: 0f
            val gaitScore = gait.getOrNull(i) ?: 0f

            val activeScores = mutableListOf<Float>()
            face.getOrNull(i)?.let { activeScores.add(it) }
            audio.getOrNull(i)?.let { activeScores.add(it) }
            gait.getOrNull(i)?.let { activeScores.add(it) }

            val average = if (activeScores.isNotEmpty())
                activeScores.average().toFloat()
            else 0f // If no active scores, consider average 0

            combined.add(average)
        }
        Log.d("VerificationsFragment", "Combined scores: ${combined.size} entries.")
        return combined
    }

    override fun onDestroyView() {
        super.onDestroyView()
        faceLoopJob?.cancel()
        faceProcessingJob?.cancel()
        _newImageDetectedChannel.close() // Close the channel when done
        _binding = null
    }
}