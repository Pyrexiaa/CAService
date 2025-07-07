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
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter


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
        if (isFaceEnrolled) {
            startFaceVerificationLoop()
        }

        if (isAudioEnrolled) {
            startAudioVerificationLoop()
        }

        if (isAudioEnrolled || isGaitEnrolled) {
            startGaitVerificationLoop()

            val combinedData = combineConfidenceScores(faceScores, audioScores, gaitScores)

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
                    plotChartData(binding.chartFace, "Face", faceScores, currentIndex)
                }
            }
        }
    }

    // Audio Job
    private var audioLoopJob: Job? = null
    private var audioProcessingJob: Job? = null
    private val _newAudioDetectedChannel = Channel<Int>(Channel.CONFLATED)
    private var previousAudioIndex = -1

    private fun startAudioVerificationLoop() {
        val audioDir = File(requireContext().filesDir, "audio")
        if (!audioDir.exists()) {
            Log.w("VerificationsFragment", "Audio directory does not exist.")
            return
        }

        // Start audio processing once
        startAudioProcessing()

        audioLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = audioIndex // Replace with your method to get current audio index
                if (current != previousAudioIndex) {
                    previousAudioIndex = current
                    _newAudioDetectedChannel.trySend(current).getOrThrow()
                }
                delay(100) // Check every 100ms for new audio
            }
        }
    }

    private fun startAudioProcessing() {
        audioProcessingJob?.cancel()
        audioProcessingJob = lifecycleScope.launch(Dispatchers.IO) {
            _newAudioDetectedChannel.receiveAsFlow().collect { currentIndex ->
                val audioFile = File(requireContext().filesDir, "audio/audio_${currentIndex}.wav")
                if (!audioFile.exists()) {
                    Log.d("VerificationsFragment", "Audio file does not exist: audio_${currentIndex}.wav")
                    return@collect
                }

                val similarity = verifyAudioSuspending(currentIndex)
                Log.d("VerificationsFragment", "Audio Index: $currentIndex, Similarity: $similarity")

                val audioTxtFile = File(requireContext().filesDir, "confidence_scores/audio.txt")
                audioTxtFile.parentFile?.mkdirs()
                audioTxtFile.appendText("$similarity\n")
                audioScores.add(similarity)

                withContext(Dispatchers.Main) {
                    setupChartAxesAndAppearance(binding.chartAudio, "Real-time Audio Confidence")
                    plotChartData(binding.chartAudio, "Audio", audioScores, currentIndex)
                }
            }
        }
    }

    private suspend fun verifyAudioSuspending(currentIndex: Int): Float {
        val audioDir = File(requireContext().filesDir, "audio")
        val segmentFiles = (currentIndex - 4..currentIndex).map { index ->
            File(audioDir, "audio_${index}.wav")
        }

        // If any of the 5 files is missing, return 0 confidence
        if (segmentFiles.any { !it.exists() }) {
            Log.d("VerificationsFragment", "Insufficient audio files for verification at index: $currentIndex")
            return 0f
        }

        return withContext(Dispatchers.IO) {
            try {
                // --- Step 1: Concatenate the 5 audio files into one 5s WAV file ---
                val mergedFile = File(audioDir, "merged_audio_temp.wav")
                mergeWavFiles(segmentFiles, mergedFile)

                // --- Step 2: Run verification (placeholder function) ---
                val similarity = audioRecognizer.verifyAudioFile(mergedFile)
                similarity
            } catch (e: Exception) {
                Log.e("VerificationsFragment", "Error during audio verification: ${e.message}")
                0f
            }
        }
    }

    private fun mergeWavFiles(inputFiles: List<File>, outputFile: File) {
        val outputStream = FileOutputStream(outputFile)
        val combinedAudioData = ByteArrayOutputStream()

        // Skip the header (first 44 bytes) for all except the first file
        inputFiles.forEachIndexed { index, file ->
            val bytes = file.readBytes()
            if (index == 0) {
                combinedAudioData.write(bytes) // Keep header of first file
            } else {
                combinedAudioData.write(bytes.copyOfRange(44, bytes.size)) // Skip header
            }
        }

        // Update WAV header for new combined length (optional but ideal)
        val fullAudio = combinedAudioData.toByteArray()
        updateWavHeader(fullAudio)

        outputStream.write(fullAudio)
        outputStream.close()
    }

    private fun updateWavHeader(wavData: ByteArray) {
        val totalDataLen = wavData.size - 8
        val totalAudioLen = wavData.size - 44
        val sampleRate = 16000 // Adjust to your actual sample rate
        val channels = 1       // Mono
        val byteRate = 16 * sampleRate * channels / 8

        fun writeIntLE(value: Int, offset: Int) {
            wavData[offset] = (value and 0xff).toByte()
            wavData[offset + 1] = ((value shr 8) and 0xff).toByte()
            wavData[offset + 2] = ((value shr 16) and 0xff).toByte()
            wavData[offset + 3] = ((value shr 24) and 0xff).toByte()
        }

        writeIntLE(totalDataLen, 4)
        writeIntLE(totalAudioLen, 40)
    }

    // Gait Job

    // Gait Job
    private var gaitLoopJob: Job? = null
    private var gaitProcessingJob: Job? = null
    private val _newGaitDetectedChannel = Channel<Int>(Channel.CONFLATED)
    private var previousGaitIndex = -1

    private fun startGaitVerificationLoop() {
        val gaitDir = File(requireContext().filesDir, "sensor")
        if (!gaitDir.exists()) {
            Log.w("VerificationsFragment", "Gait directory does not exist.")
            return
        }

        startGaitProcessing()

        gaitLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = gaitIndex // Replace with your logic to track gait index
                if (current != previousGaitIndex) {
                    previousGaitIndex = current
                    _newGaitDetectedChannel.trySend(current).getOrThrow()
                }
                delay(100) // Check every 100ms for new gait data
            }
        }
    }

    private fun startGaitProcessing() {
        gaitProcessingJob?.cancel()
        gaitProcessingJob = lifecycleScope.launch(Dispatchers.IO) {
            _newGaitDetectedChannel.receiveAsFlow().collect { currentIndex ->
                val gaitFile = File(requireContext().filesDir, "sensor/sensor_${currentIndex}.txt")
                if (!gaitFile.exists()) {
                    Log.d("VerificationsFragment", "Gait file does not exist: sensor_${currentIndex}.txt")
                    return@collect
                }

                val similarity = verifyGaitSuspending(currentIndex)
                Log.d("VerificationsFragment", "Gait Index: $currentIndex, Similarity: $similarity")

                val gaitTxtFile = File(requireContext().filesDir, "confidence_scores/gait.txt")
                gaitTxtFile.parentFile?.mkdirs()
                gaitTxtFile.appendText("$similarity\n")
                gaitScores.add(similarity)

                withContext(Dispatchers.Main) {
                    setupChartAxesAndAppearance(binding.chartGait, "Real-time Gait Confidence")
                    plotChartData(binding.chartGait, "Gait", gaitScores, currentIndex)
                }
            }
        }
    }

    private suspend fun verifyGaitSuspending(currentIndex: Int): Float {
        val gaitDir = File(requireContext().filesDir, "sensor")
        val segmentFiles = (currentIndex - 4..currentIndex).map { index ->
            File(gaitDir, "sensor_${index}.txt")
        }

        if (segmentFiles.any { !it.exists() }) {
            Log.d("VerificationsFragment", "Insufficient gait files for verification at index: $currentIndex")
            return 0f
        }

        return withContext(Dispatchers.IO) {
            try {
                val mergedFile = File(gaitDir, "merged_gait_temp.txt")
                mergeTxtFiles(segmentFiles, mergedFile)

                val similarity = gaitRecognizer.verifyGaitFile(mergedFile)
                similarity
            } catch (e: Exception) {
                Log.e("VerificationsFragment", "Error during gait verification: ${e.message}")
                0f
            }
        }
    }

    private fun mergeTxtFiles(inputFiles: List<File>, outputFile: File) {
        val mergedLines = mutableListOf<List<String>>()

        for (file in inputFiles) {
            val lines = file.readLines()

            for (line in lines) {
                val parts = line.split(",")
                if (parts.size != 5) continue

                val timestamp = parts[0]
                val sensorType = parts[1]
                val x = parts[2]
                val y = parts[3]
                val z = parts[4]

                if (sensorType != "LSM6DSL Acceleration Sensor") continue
                mergedLines.add(listOf(timestamp, x, y, z))
            }
        }

        // Sort by timestamp (to make sure data is in order)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")
        val baseTime = mergedLines.firstOrNull()?.get(0)?.let {
            LocalDateTime.parse(it, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: return

        outputFile.bufferedWriter().use { writer ->
            writer.write("time,x,y,z\n")
            for ((timestampStr, x, y, z) in mergedLines) {
                val timeMillis = try {
                    LocalDateTime.parse(timestampStr, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (e: Exception) {
                    continue
                }
                val elapsed = (timeMillis - baseTime) / 1000.0
                writer.write("$elapsed,$x,$y,$z\n")
            }
        }
    }

    // --- Renamed from setupConfidenceChartBasics to avoid ambiguity ---
    // This function sets up the basic axes and appearance of ANY LineChart.
    private fun setupChartAxesAndAppearance(chart: LineChart, chartTitle: String) {
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setNoDataText("No data available")

        // X-Axis
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.setDrawAxisLine(true)
        xAxis.setAvoidFirstLastClipping(true)
        xAxis.textColor = Color.WHITE
        xAxis.textSize = 12f
        xAxis.granularity = 1f // Ensures ticks appear at every 1 unit
        xAxis.isGranularityEnabled = true
        xAxis.labelRotationAngle = 0f
        xAxis.setLabelCount(6, false) // Let it auto adjust
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}s"
            }
        }

        // Y-Axis (Left)
        val leftAxis = chart.axisLeft
        leftAxis.isEnabled = true // Must be true to show labels
        leftAxis.setDrawAxisLine(true)
        leftAxis.setDrawGridLines(true)
        leftAxis.textColor = Color.WHITE
        leftAxis.textSize = 12f
        leftAxis.axisMinimum = 0f
        leftAxis.axisMaximum = 1f
        leftAxis.granularity = 0.1f
        leftAxis.isGranularityEnabled = true
        leftAxis.setLabelCount(6, true)
        leftAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format("%.1f", value)
            }
        }

        // Y-Axis (Right) disabled
        val rightAxis = chart.axisRight
        rightAxis.isEnabled = true
        rightAxis.setDrawAxisLine(false)
        rightAxis.setDrawGridLines(false)

        // Chart Legend
        chart.legend.textColor = Color.WHITE

        // Chart Title
        chart.description.isEnabled = true
        chart.description.text = chartTitle
        chart.description.textColor = Color.WHITE
        chart.description.textSize = 12f
    }

    // --- NEW: Function to plot data on a given chart (for historical data) ---
    private fun plotChartData(chart: LineChart, dataSetName: String, data: List<Float>, currentIndex: Int = 0) {
        if (data.isEmpty()) {
            chart.data = null
            chart.invalidate()
            return
        }

        // Use currentIndex to align x-axis properly with time
        val entries = data.mapIndexed { i, value ->
            Entry((currentIndex - data.size + i + 1).toFloat(), value)
            // This ensures the latest data aligns with the currentIndex
        }

        val dataSet = LineDataSet(entries, dataSetName).apply {
            color = when (dataSetName) {
                "Face" -> Color.BLUE
                "Audio" -> Color.GREEN
                "Gait" -> Color.RED
                "Combined" -> Color.YELLOW
                else -> Color.CYAN
            }
            valueTextColor = Color.BLACK
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = color
            fillAlpha = 50
        }

        val lineData = LineData(dataSet)
        chart.data = lineData

        chart.notifyDataSetChanged()
        chart.invalidate()

        Log.d("VerificationsFragment", "Chart '$dataSetName' updated at index $currentIndex with ${entries.size} entries.")
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