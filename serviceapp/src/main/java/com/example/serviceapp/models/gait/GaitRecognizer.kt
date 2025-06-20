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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

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

        return window
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


    private fun extractFeatures(window: List<List<Double>>): FloatArray? {
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

        return floatFeatures
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

    private fun extractEmbedding(gaitFilePath: String): FloatArray? {
        val inputFeatures = extractWindows(gaitFilePath) ?: return null
        val extractedFeatures = extractFeatures(inputFeatures) ?: return null
        val scaledInputFeatures = modelScaler.applyStandardScaling(extractedFeatures)
        return convertToByteBuffer(scaledInputFeatures).let {
            modelRunner.run(it)
        }
    }

    private fun enrollEmbedding(audioFilePath: String) : FloatArray? {
        enrolledEmbedding = extractEmbedding(audioFilePath)
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
        val isMatch = similarity > 0.6f // threshold
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


