package com.example.serviceapp.models.gait

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.serviceapp.models.tflite.TFLiteModelRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

class GaitRecognizer(private val context: Context, modelPath: String) {

    private val modelRunner: TFLiteModelRunner = TFLiteModelRunner(context, modelPath, 42)
    private val modelScaler: GaitScaler = GaitScaler()
    private val prefs: SharedPreferences = context.getSharedPreferences("GaitPrefs", Context.MODE_PRIVATE)
    private var enrolledEmbedding: FloatArray? = null

    private fun extractWindows(gaitFilePath: String): List<List<Double>>? {
        val lines = File(gaitFilePath).readLines()
            .drop(1) // skip header

        if (lines.isEmpty()) return null

        val timeList = mutableListOf<Double>()
        val xList = mutableListOf<Double>()
        val yList = mutableListOf<Double>()
        val zList = mutableListOf<Double>()

        for (line in lines) {
            val parts = line.split(",")
            if (parts.size == 4) {
                val time = parts[0].toDoubleOrNull() ?: continue
                val x = parts[1].toDoubleOrNull() ?: continue
                val y = parts[2].toDoubleOrNull() ?: continue
                val z = parts[3].toDoubleOrNull() ?: continue
                timeList.add(time)
                xList.add(x)
                yList.add(y)
                zList.add(z)
            }
        }

        if (timeList.size < 2) return null

        // Resample to uniform 50Hz (250 points over 5 seconds)
        val startTime = timeList.first()
        val endTime = startTime + 5.0
        val targetSamplingRate = 50
        val numPoints = 5 * targetSamplingRate
        val uniformTimestamps = List(numPoints) { i -> startTime + i / targetSamplingRate.toDouble() }

        val resampledX = resample(timeList, xList, uniformTimestamps)
        val resampledY = resample(timeList, yList, uniformTimestamps)
        val resampledZ = resample(timeList, zList, uniformTimestamps)

        // Combine to window data
        val window = resampledX.indices.map { i ->
            listOf(resampledX[i], resampledY[i], resampledZ[i])
        }

        saveWindowToCsv(window, "gait_window.csv")

        return window
    }

    private fun extractRollingWindowsResampledGait(gaitFilePath: String): List<List<List<Double>>>? {
        val lines = File(gaitFilePath).readLines().drop(1)
        if (lines.isEmpty()) return null

        val timeList = mutableListOf<Double>()
        val xList = mutableListOf<Double>()
        val yList = mutableListOf<Double>()
        val zList = mutableListOf<Double>()

        for (line in lines) {
            val parts = line.split(",")
            if (parts.size == 4) {
                val time = parts[0].toDoubleOrNull() ?: continue
                val x = parts[1].toDoubleOrNull() ?: continue
                val y = parts[2].toDoubleOrNull() ?: continue
                val z = parts[3].toDoubleOrNull() ?: continue
                timeList.add(time)
                xList.add(x)
                yList.add(y)
                zList.add(z)
            }
        }

        if (timeList.size < 2) return null

        val startTime = timeList.first()
        val endTime = timeList.last()
        val totalDuration = endTime - startTime

        val targetSamplingRate = 50
        val stepSize = 1.0  // 1-second step
        val windowSize = 5.0 // 5-second window
        val offset = if (totalDuration > 7.0) 1.0 else 0.0

        val effectiveStart = startTime + offset
        val effectiveEnd = endTime - offset

        val uniformTimestamps = generateSequence(effectiveStart) { it + (1.0 / targetSamplingRate) }
            .takeWhile { it <= effectiveEnd }
            .toList()

        val resampledX = resample(timeList, xList, uniformTimestamps)
        val resampledY = resample(timeList, yList, uniformTimestamps)
        val resampledZ = resample(timeList, zList, uniformTimestamps)

        val totalSamples = uniformTimestamps.size
        val windowSamples = (windowSize * targetSamplingRate).toInt()
        val stepSamples = (stepSize * targetSamplingRate).toInt()

        val windows = mutableListOf<List<List<Double>>>()
        var index = 0
        var start = 0

        while (start + windowSamples <= totalSamples) {
            val window = List(windowSamples) { i ->
                listOf(
                    resampledX[start + i],
                    resampledY[start + i],
                    resampledZ[start + i]
                )
            }
            windows.add(window)

            // Save each window to CSV
            saveWindowToCsv(window, "gait_window_$index.csv")
            index++
            start += stepSamples
        }

        return if (windows.isNotEmpty()) windows else null
    }

    fun saveWindowToCsv(window: List<List<Double>>, outputPath: String) {
        val file = File(context.cacheDir, outputPath)
        file.bufferedWriter().use { out ->
            out.write("x,y,z\n") // write header
            for (row in window) {
                out.write(row.joinToString(","))
                out.write("\n")
            }
        }
    }

    private fun resample(time: List<Double>, values: List<Double>, targetTimes: List<Double>): List<Double> {
        val result = mutableListOf<Double>()
        var j = 0
        for (t in targetTimes) {
            while (j < time.size - 2 && time[j + 1] < t) j++
            val t0 = time[j]
            val t1 = time[j + 1]
            val v0 = values[j]
            val v1 = values[j + 1]
            val value = if (t1 != t0) {
                v0 + (v1 - v0) * (t - t0) / (t1 - t0)
            } else {
                v0
            }
            result.add(value)
        }
        return result
    }


    private fun extractFeatures(window: List<List<Double>>, index: Int): FloatArray? {
        if (window.isEmpty()) return null

        val features = mutableListOf<Double>()

        // Transpose to get axis-wise signals
        val signalX = window.map { it[0] }
        val signalY = window.map { it[1] }
        val signalZ = window.map { it[2] }

        listOf(signalX, signalY, signalZ).forEach { signal ->
            features.add(signal.average()) // Mean
            features.add(std(signal)) // Std
            features.add(signal.minOrNull() ?: 0.0)
            features.add(signal.maxOrNull() ?: 0.0)
            features.add(median(signal))
            features.add(percentile(signal, 25.0).toDouble())
            features.add(percentile(signal, 75.0).toDouble())
            features.add((signal.maxOrNull() ?: 0.0) - (signal.minOrNull() ?: 0.0)) // Range
            features.add(signal.sumOf { abs(it) }) // Signal Magnitude Area
        }

        // Compute magnitude = sqrt(x^2 + y^2 + z^2)
        val magnitude = window.map { (x, y, z) -> sqrt(x * x + y * y + z * z) }
        features.add(magnitude.average())
        features.add(std(magnitude))
        features.add(magnitude.minOrNull() ?: 0.0)
        features.add(magnitude.maxOrNull() ?: 0.0)

        val floatFeatures = features.map { it.toFloat() }.toFloatArray()
        Log.d("FeatureExtraction", "Extracted ${floatFeatures.size} features")

        saveFeaturesToCsv(floatFeatures, "gait_extracted_features_$index.csv")
        return floatFeatures
    }

    fun saveFeaturesToCsv(features: FloatArray, outputPath: String) {
        val file = File(context.cacheDir, outputPath)
        file.bufferedWriter().use { out ->
            out.write(features.joinToString(","))  // Save as one line
            out.write("\n")
        }
    }


    private fun std(signal: List<Double>): Double {
        val mean = signal.average()
        return sqrt(signal.map { (it - mean).pow(2) }.average())
    }

    private fun median(signal: List<Double>): Double {
        val sorted = signal.sorted()
        val n = sorted.size
        return if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        } else {
            sorted[n / 2]
        }
    }

    private fun percentile(data: List<Double>, percentile: Double): Double {
        val sorted = data.sorted()
        val rank = percentile / 100.0 * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        val weight = rank - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    private var isCollecting = false
    private val accelData = mutableListOf<String>()
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var sensorListener: SensorEventListener? = null
    private var startTime: Long = 0L

    fun collectForEnrollment(context: Context) {
        if (isCollecting) return

        Toast.makeText(context, "Collecting gait for enrollment...", Toast.LENGTH_SHORT).show()

        accelData.clear()
        isCollecting = true
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val startTime = System.currentTimeMillis()

        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val currentTime = System.currentTimeMillis()
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val elapsed = (currentTime - startTime) / 1000.0
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    accelData.add("$elapsed,$x,$y,$z")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)

        Handler(Looper.getMainLooper()).postDelayed({
            stopCollection(context)
        }, 5000)
    }

    fun startGaitCollection(context: Context) {
        if (isCollecting) return

        Toast.makeText(context, "Collecting gait data...", Toast.LENGTH_SHORT).show()
        accelData.clear()
        isCollecting = true

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        startTime = System.currentTimeMillis()

        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!isCollecting || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

                val currentTime = System.currentTimeMillis()
                val elapsed = (currentTime - startTime) / 1000.0
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                accelData.add("$elapsed,$x,$y,$z")
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stopAndExtractGaitEmbedding(context: Context, onSuccess: (FloatArray?) -> Unit) {
        if (!isCollecting) {
            onSuccess(null)
            return
        }

        isCollecting = false
        sensorListener?.let { sensorManager?.unregisterListener(it) }

        val durationMs = System.currentTimeMillis() - startTime
        val minDurationMs = 5000

        if (durationMs < minDurationMs) {
            Toast.makeText(context, "Recording too short. Walk at least 5 seconds.", Toast.LENGTH_SHORT).show()
            onSuccess(null)
            return
        }

        val fileName = "enrollment_gait_temp.txt"
        val file = File(context.cacheDir, fileName)

        try {
            // Step 1: Parse accelData into (timestamp, x, y, z)
            val parsedData = accelData.mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size == 4) {
                    val time = parts[0].toDoubleOrNull()
                    val x = parts[1].toDoubleOrNull()
                    val y = parts[2].toDoubleOrNull()
                    val z = parts[3].toDoubleOrNull()
                    if (time != null && x != null && y != null && z != null) {
                        listOf(time, x, y, z)
                    } else null
                } else null
            }

            val trimmedData = if (parsedData.isNotEmpty()) {
                val firstTime = parsedData.first()[0]
                val lastTime = parsedData.last()[0]
                val totalDuration = lastTime - firstTime

                if (totalDuration > 7.0) {
                    // Step 2: Trim first and last 1s
                    parsedData.filter { it[0] >= firstTime + 1 && it[0] <= lastTime - 1 }
                } else {
                    parsedData
                }
            } else {
                emptyList()
            }

            // Step 3: Save filtered data to file
            file.bufferedWriter().use { out ->
                out.write("timestamp,acc_x,acc_y,acc_z\n")
                trimmedData.forEach { row ->
                    out.write("${row[0]},${row[1]},${row[2]},${row[3]}\n")
                }
            }

            Log.d("GaitData", "Gait data saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("GaitData", "Failed to save gait data", e)
            Toast.makeText(context, "Failed to save gait data.", Toast.LENGTH_SHORT).show()
            onSuccess(null)
            return
        }

        Thread {
            val embedding = enrollEmbedding(file.absolutePath)
            Handler(Looper.getMainLooper()).post {
                if (embedding != null) {
                    Log.d("Embedding", "Gait embedding extracted: ${embedding.joinToString()}")
                    Toast.makeText(context, "Gait enrolled", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("Embedding", "Failed to extract gait embedding.")
                    Toast.makeText(context, "Failed to extract gait embedding.", Toast.LENGTH_SHORT).show()
                }
                onSuccess(embedding)
            }
        }.start()
    }

    fun cancelGaitCollection() {
        isCollecting = false
        sensorManager?.unregisterListener(sensorListener)
        accelData.clear()
    }

    private fun stopCollection(context: Context) {
        isCollecting = false
        sensorListener?.let { sensorManager?.unregisterListener(it) }

        val fileName = "enrollment_gait_temp.txt"
        val file = File(context.cacheDir, fileName)
        file.bufferedWriter().use { out ->
            out.write("timestamp,acc_x,acc_y,acc_z\n")
            accelData.forEach { out.write(it + "\n") }
        }

        Toast.makeText(context, "Gait enrolled", Toast.LENGTH_SHORT).show()

        // Now enroll embedding
        val embedding = enrollEmbedding(file.absolutePath)
        if (embedding != null) {
            Log.d("Embedding", embedding.joinToString())
        } else {
            Log.e("Embedding", "Failed to extract gait embedding.")
        }
    }

    private fun convertToByteBuffer(floatArray: FloatArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * floatArray.size)
        byteBuffer.order(ByteOrder.nativeOrder())
        for (value in floatArray) {
            byteBuffer.putFloat(value)
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    // For verification purpose only
    private fun extractEmbedding(gaitFilePath: String): FloatArray? {
        val inputFeatures = extractWindows(gaitFilePath) ?: return null
        val extractedFeatures = extractFeatures(inputFeatures, 0) ?: return null
        val scaledInputFeatures = modelScaler.applyStandardScaling(extractedFeatures)
        return convertToByteBuffer(scaledInputFeatures).let {
            modelRunner.run(it)
        }
    }

    // For enrollment purpose
    private fun extractEmbeddingRolling(gaitFilePath: String): FloatArray? {
        val resampled = extractRollingWindowsResampledGait(gaitFilePath) ?: return null

        val embeddings = mutableListOf<FloatArray>()

        for ((currentIndex, window) in resampled.withIndex()) {
            val features = extractFeatures(window, currentIndex) ?: continue
            val scaled = modelScaler.applyStandardScaling(features)
            val embedding = convertToByteBuffer(scaled).let { modelRunner.run(it) }

            embeddings.add(embedding)
        }

        if (embeddings.isEmpty()) return null

        val embeddingSize = embeddings[0].size
        return FloatArray(embeddingSize) { i ->
            embeddings.map { it[i] }.average().toFloat()
        }
    }

    private fun enrollEmbedding(audioFilePath: String) : FloatArray? {
        enrolledEmbedding = extractEmbeddingRolling(audioFilePath)
        saveEmbeddingToPrefs(enrolledEmbedding!!)
        return enrolledEmbedding
    }

    private fun saveEmbeddingToPrefs(embedding: FloatArray) {
        val str = embedding.joinToString(",")
        val success = prefs.edit().putString("embedding", str).commit()  // commit() returns Boolean

        Handler(Looper.getMainLooper()).post {
            if (success) {
                Toast.makeText(context, "Gait embedding saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save gait embedding!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun collectForVerification(
        context: Context,
        onResult: (confidence: Float, isMatch: Boolean) -> Unit
    ) {
        if (isCollecting) return

        Toast.makeText(context, "Collecting gait for verification...", Toast.LENGTH_SHORT).show()

        accelData.clear()
        isCollecting = true
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val startTime = System.currentTimeMillis()

        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val currentTime = System.currentTimeMillis()
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val elapsed = (currentTime - startTime) / 1000.0
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    accelData.add("$elapsed,$x,$y,$z")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)

        Handler(Looper.getMainLooper()).postDelayed({
            stopVerificationCollection(context, onResult)
        }, 5000)
    }

    suspend fun verifyGaitFile(file: File): Float = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.e("verifyGaitFile", "Gait file does not exist: ${file.absolutePath}")
            return@withContext 0f
        }

        try {
            // Extract new embedding from the gait file
            val newEmbedding = extractEmbedding(file.absolutePath)
            val savedEmbedding = loadEmbeddingFromPrefs()

            val confidence = if (newEmbedding != null && savedEmbedding != null) {
                cosineSimilarity(newEmbedding, savedEmbedding)
            } else {
                0f
            }

            Log.d("verifyGaitFile", "Gait verification confidence: $confidence")
            return@withContext confidence
        } catch (e: Exception) {
            Log.e("verifyGaitFile", "Exception during gait verification: ${e.message}")
            return@withContext 0f
        }
    }


    private fun stopVerificationCollection(
        context: Context,
        onResult: (confidence: Float, isMatch: Boolean) -> Unit
    ) {
        isCollecting = false
        sensorListener?.let { sensorManager?.unregisterListener(it) }

        val fileName = "verification_gait_temp.txt"
        val file = File(context.cacheDir, fileName)
        file.bufferedWriter().use { out ->
            out.write("timestamp,acc_x,acc_y,acc_z\n")
            accelData.forEach { out.write(it + "\n") }
        }

        // Load new embedding
        val newEmbedding = extractEmbedding(file.absolutePath)
        if (newEmbedding == null) {
            Toast.makeText(context, "Verification failed: Invalid data", Toast.LENGTH_SHORT).show()
            onResult(0f, false)
            return
        }

        // Load stored enrolled embedding (replace with your own storage mechanism)
        val enrolledEmbedding = loadEmbeddingFromPrefs()
        if (enrolledEmbedding == null) {
            Toast.makeText(context, "No enrolled gait data found", Toast.LENGTH_SHORT).show()
            onResult(0f, false)
            return
        }

        val similarity = cosineSimilarity(newEmbedding, enrolledEmbedding)
        val isMatch = similarity > 0.80f // threshold
        onResult(similarity, isMatch)
    }

    fun loadEmbeddingFromPrefs(): FloatArray? {
        val str = prefs.getString("embedding", null) ?: return null
        val parts = str.split(",")

        try {
            val embedding = parts.map { it.toFloat() }.toFloatArray()
            enrolledEmbedding = embedding
            return embedding
        } catch (e: NumberFormatException) {
            Log.d("Load Gait Embedding", "Error occurred: $e")
            return null
        }
    }

    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }
        return dot / (sqrt(normA) * sqrt(normB))
    }

    fun close() {
        modelRunner.close()
    }
}


