package com.example.serviceapp.models.audio

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.serviceapp.models.tflite.TFLiteModelRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

class AudioRecognizer(private val context: Context, modelPath: String, mfccPath: String) {

    private val modelRunner: TFLiteModelRunner = TFLiteModelRunner(context, modelPath, 80)
    private val modelScaler: AudioScaler = AudioScaler()
    private val prefs: SharedPreferences = context.getSharedPreferences("AudioPrefs", Context.MODE_PRIVATE)
    private var enrolledEmbedding: FloatArray? = null

    private val modelFileName = "mfcc_model.tflite"
    private val mfccExtractor: MfccFeatureExtractor = MfccFeatureExtractor(context, modelFileName)

    fun saveFeaturesToCsv(context: Context, fileName: String, features: FloatArray) {
        val file = File(context.cacheDir, fileName)
        file.bufferedWriter().use { writer ->
            // Convert FloatArray to comma-separated string and write one line
            writer.write(features.joinToString(separator = ","))
        }
    }

    private fun convertToByteBuffer(floatArray: FloatArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * floatArray.size)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (value in floatArray) {
            byteBuffer.putFloat(value)
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    private fun loadRawAudio(audioFilePath: String): FloatArray? {
        return try {
            val file = File(audioFilePath)
            val inputStream = DataInputStream(FileInputStream(file))
            inputStream.skipBytes(44) // skip WAV header

            val audioBytes = inputStream.readBytes()
            inputStream.close()

            val numSamples = audioBytes.size / 2
            val rawAudio = FloatArray(numSamples)
            val byteBuffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numSamples) {
                rawAudio[i] = byteBuffer.short.toInt() / 32768f
            }
            rawAudio
        } catch (e: Exception) {
            Log.e("AudioLoader", "Failed to load raw audio: ${e.message}", e)
            null
        }
    }

    private fun extractEmbedding(audioFilePath: String): FloatArray? {
        val inputFeatures = mfccExtractor.extractMFCCFeaturesFromWav(audioFilePath) ?: return null
        val aggregatedFeatures = aggregateMfccFeatures(inputFeatures)
        Log.d("ExtractEmbedding", "Extracted MFCC features")
        saveFeaturesToCsv(context, "mfcc_features.csv", aggregatedFeatures)
        val scaledInputFeatures = modelScaler.applyStandardScaling(aggregatedFeatures)
        return convertToByteBuffer(scaledInputFeatures)
            .let { modelRunner.run(it) } // modelRunner should accept ByteBuffer
    }

    fun extractEmbeddingRolling(audioFilePath: String): FloatArray? {
        val rawAudio = loadRawAudio(audioFilePath) ?: return null

        val sampleRate = 26521
        val windowDurationSec = 5
        val stepDurationSec = 1
        val windowSize = sampleRate * windowDurationSec
        val stepSize = sampleRate * stepDurationSec

        val totalSamples = rawAudio.size
        val totalDurationSec = totalSamples / sampleRate.toFloat()

        // Define valid processing range
        val startOffset = if (totalDurationSec > 7) sampleRate else 0
        val endOffset = if (totalDurationSec > 7) totalSamples - sampleRate else totalSamples

        val embeddings = mutableListOf<FloatArray>()
        var start = startOffset

        while (start + windowSize <= endOffset) {
            val window = rawAudio.copyOfRange(start, start + windowSize)
            val currentIndex = start / sampleRate
            val mfcc = mfccExtractor.extractMfccFromWindow(window, currentIndex)
            if (mfcc != null) {
                val aggregatedFeatures = aggregateMfccFeatures(mfcc)
                Log.d("ExtractEmbedding", "Extracted MFCC features")
                saveFeaturesToCsv(context, "mfcc_features_${currentIndex}.csv", aggregatedFeatures)
                val scaled = modelScaler.applyStandardScaling(aggregatedFeatures)
                val embedding = convertToByteBuffer(scaled).let { modelRunner.run(it) }
                embeddings.add(embedding)
            }
            start += stepSize
        }

        if (embeddings.isEmpty()) return null

        // Average the embeddings
        val embeddingSize = embeddings[0].size
        val avgEmbedding = FloatArray(embeddingSize) { i ->
            embeddings.map { it[i] }.average().toFloat()
        }

        Log.d("ExtractEmbedding", "Returned average embedding from ${embeddings.size} windows.")
        return avgEmbedding
    }

    private fun aggregateMfccFeatures(inputFeatures: FloatArray, numFrames: Int = 256, numCoeffs: Int = 13): FloatArray {
        // Reshape 1D array to 2D: [numFrames][numCoeffs]
        val mfcc2D = Array(numFrames) { FloatArray(numCoeffs) }
        for (i in 0 until numFrames) {
            for (j in 0 until numCoeffs) {
                mfcc2D[i][j] = inputFeatures[i * numCoeffs + j]
            }
        }

        saveMfcc2DToCsv(context, "mfcc_raw_frames.csv", mfcc2D)

        println("--- mfcc2D Content ---")
        mfcc2D.forEachIndexed { frameIndex, frame ->
            println("Frame $frameIndex: ${frame.joinToString(", ")}")
        }
        println("----------------------")

        val means = FloatArray(numCoeffs)
        val stds = FloatArray(numCoeffs)
        val skews = FloatArray(numCoeffs)
        val kurtoses = FloatArray(numCoeffs)

        for (j in 0 until numCoeffs) {
            // Extract all values for the current coefficient 'j' across all frames
            val coeffValues = mfcc2D.map { it[j] }
            val n = coeffValues.size.toDouble() // Number of frames

            // Calculate Mean
            val mean = coeffValues.average()
            means[j] = mean.toFloat()

            // Calculate Variance and Standard Deviation
            val variance = coeffValues.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance)
            stds[j] = std.toFloat()

            // Calculate Skewness and Kurtosis, handling cases with very small std dev
            if (std > 1e-6) { // Use a small epsilon to avoid division by zero
                // Skewness: E[((X - mu)/sigma)^3]
                val skewNumerator = coeffValues.map { (it - mean).pow(3) }.average()
                val skew = skewNumerator / (std.pow(3))
                skews[j] = skew.toFloat()

                // Kurtosis: E[((X - mu)/sigma)^4] - 3 (excess kurtosis)
                val kurtNumerator = coeffValues.map { (it - mean).pow(4) }.average()
                val kurtosis = kurtNumerator / (std.pow(4)) - 3
                kurtoses[j] = kurtosis.toFloat()
            } else {
                // If std is negligible, set skew and kurtosis to 0
                skews[j] = 0f
                kurtoses[j] = 0f
            }

            println("Coeff $j - Mean: ${means[j]}, Std: ${stds[j]}, Skew: ${skews[j]}, Kurtosis: ${kurtoses[j]}")
        }

        // Concatenate all stats: mean + std + skew + kurtosis (each of length numCoeffs)
        return means + stds + skews + kurtoses
    }

    private fun enrollEmbedding(audioFilePath: String) : FloatArray? {
        val embedding = extractEmbeddingRolling(audioFilePath)
        if (embedding != null) {
            enrolledEmbedding = embedding
            saveEmbeddingToPrefs(embedding)
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Failed to extract embedding from audio", Toast.LENGTH_SHORT).show()
            }
        }
        return embedding
    }

    private fun saveEmbeddingToPrefs(embedding: FloatArray) {
        val str = embedding.joinToString(",")
        val success = prefs.edit().putString("embedding", str).commit()  // commit() returns Boolean

        Handler(Looper.getMainLooper()).post {
            if (success) {
                Toast.makeText(context, "Audio embedding saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save audio embedding!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadEmbeddingFromPrefs(): FloatArray? {
        val str = prefs.getString("embedding", null) ?: return null
        val parts = str.split(",")

        try {
            val embedding = parts.map { it.toFloat() }.toFloatArray()
            enrolledEmbedding = embedding
            return embedding
        } catch (e: NumberFormatException) {
            Log.d("Load Embedding", "Error occurred: $e")
            return null
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun recordForEnrollment(context: Context, onSuccess: (FloatArray?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Microphone permission not granted", Toast.LENGTH_SHORT).show()
            onSuccess(null)
            return
        }

        val fileName = "enrollment_temp.wav"
        val filePath = File(context.cacheDir, fileName).absolutePath

        Toast.makeText(context, "Recording audio for enrollment...", Toast.LENGTH_SHORT).show()

        val sampleRate = 26521
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )

        // Check if AudioRecord is properly initialized
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(context, "AudioRecord not initialized", Toast.LENGTH_SHORT).show()
            onSuccess(null)
            return
        }

        val durationSeconds = 5
        val totalBytesToRecord = sampleRate * durationSeconds * 2 // 2 bytes per sample for 16-bit mono PCM
        val audioData = ByteArray(totalBytesToRecord)

        Thread {
            try {
                audioRecord.startRecording()

                var bytesRead = 0
                while (bytesRead < totalBytesToRecord) {
                    val bytesToRead = minOf(bufferSize, totalBytesToRecord - bytesRead)
                    val result = audioRecord.read(audioData, bytesRead, bytesToRead)
                    if (result < 0) break
                    bytesRead += result
                }

                audioRecord.stop()
                audioRecord.release()

                // Save raw PCM as WAV file
                writeWavFile(filePath, audioData, sampleRate, 1, 16)
                Log.e("recordForEnrollment", "Recording saved to $filePath")

                // Extract embedding
                val embedding = enrollEmbedding(filePath)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Audio enrolled", Toast.LENGTH_SHORT).show()
                    onSuccess(embedding)
                }
            } catch (e: Exception) {
                Log.e("recordForEnrollment", "Recording failed", e)
                audioRecord.release()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Recording failed: ${e.message ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                    onSuccess(null)
                }
            }
        }.start()
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private lateinit var audioData: ByteArray
    private var sampleRate = 26521
    private val fileName = "enrollment_temp.wav"
    private var startTime: Long = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(context: Context) {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(context, "AudioRecord not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        startTime = System.currentTimeMillis()
        audioData = ByteArray(60 * sampleRate * 2) // max 60s of recording

        var bytesRead = 0

        recordingThread = Thread {
            try {
                audioRecord?.startRecording()
                while (isRecording && bytesRead < audioData.size) {
                    val result = audioRecord?.read(audioData, bytesRead, bufferSize) ?: break
                    if (result < 0) break
                    bytesRead += result
                }
                // Final byte size will be trimmed later
            } catch (e: Exception) {
                Log.e("AudioRecognizer", "Recording error", e)
            }
        }
        recordingThread?.start()

        Toast.makeText(context, "Recording started...", Toast.LENGTH_SHORT).show()
    }

    fun stopAndExtractEmbedding(context: Context, onSuccess: (FloatArray?) -> Unit) {
        isRecording = false
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()

        val durationMs = System.currentTimeMillis() - startTime
        val minDurationMs = 5000

        if (durationMs < minDurationMs) {
            Toast.makeText(context, "Recording too short. Please record at least 5 seconds.", Toast.LENGTH_SHORT).show()
            onSuccess(null)
            return
        }

        val recordedLength = ((durationMs / 1000.0) * sampleRate * 2).toInt()
        val trimmedAudio = audioData.copyOf(recordedLength)

        val filePath = File(context.cacheDir, fileName).absolutePath
        writeWavFile(filePath, trimmedAudio, sampleRate, 1, 16)

        Log.d("AudioRecognizer", "Recording saved to $filePath")

        Thread {
            val embedding = enrollEmbedding(filePath)

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Audio enrolled", Toast.LENGTH_SHORT).show()
                onSuccess(embedding)
            }
        }.start()
    }

    fun cancelRecording() {
        isRecording = false
        recordingThread?.interrupt()
        audioRecord?.stop()
        audioRecord?.release()
        Log.d("AudioRecognizer", "Recording cancelled.")
    }


    private fun writeWavFile(path: String, audioData: ByteArray, sampleRate: Int, channels: Int, bitDepth: Int) {
        val totalDataLen = audioData.size + 36
        val byteRate = sampleRate * channels * bitDepth / 8

        FileOutputStream(path).use { out ->
            val header = ByteArray(44)

            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            writeInt(header, 4, totalDataLen)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            writeInt(header, 16, 16)
            writeShort(header, 20, 1)
            writeShort(header, 22, channels.toShort())
            writeInt(header, 24, sampleRate)
            writeInt(header, 28, byteRate)
            writeShort(header, 32, (channels * bitDepth / 8).toShort())
            writeShort(header, 34, bitDepth.toShort())
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            writeInt(header, 40, audioData.size)

            out.write(header)
            out.write(audioData)
        }
    }


    private fun writeInt(header: ByteArray, index: Int, value: Int) {
        header[index] = (value and 0xff).toByte()
        header[index + 1] = ((value shr 8) and 0xff).toByte()
        header[index + 2] = ((value shr 16) and 0xff).toByte()
        header[index + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(header: ByteArray, index: Int, value: Short) {
        header[index] = (value.toInt() and 0xff).toByte()
        header[index + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun recordForVerification(context: Context, onResult: (confidence: Float, isMatch: Boolean) -> Unit) {
        Toast.makeText(context, "Recording audio for verification...", Toast.LENGTH_SHORT).show()

        val file = File(context.cacheDir, "verification_temp.wav")
        val filePath = file.absolutePath

        // Delete any previous file
        if (file.exists()) {
            val deleted = file.delete()
            Log.d("recordForVerification", "Old file deleted: $deleted")
        }

        val sampleRate = 26521
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )

        val durationSeconds = 5
        val totalBytesToRecord = sampleRate * durationSeconds * 2 // 2 bytes per sample for 16-bit mono PCM
        val audioData = ByteArray(totalBytesToRecord)

        Thread {
            try {
                Log.d("recordForVerification", "Starting recording")
                audioRecord.startRecording()

                var bytesRead = 0
                while (bytesRead < totalBytesToRecord) {
                    val bytesToRead = minOf(bufferSize, totalBytesToRecord - bytesRead)
                    val result = audioRecord.read(audioData, bytesRead, bytesToRead)
                    if (result <= 0) {
                        Log.e("recordForVerification", "AudioRecord read failed: $result")
                        break
                    }
                    bytesRead += result
                }

                audioRecord.stop()
                audioRecord.release()
                Log.d("recordForVerification", "Recording stopped, total bytes: $bytesRead")

                if (bytesRead > 0) {
                    writeWavFile(filePath, audioData, sampleRate, 1, 16)
                    Log.d("recordForVerification", "Recording saved to $filePath")
                } else {
                    Log.e("recordForVerification", "No audio data recorded")
                    throw Exception("No audio recorded")
                }

                // Logging WAV file size
                Log.d("recordForVerification", "Saved file size: ${file.length()}")

                // Extract embedding and perform verification
                val newEmbedding = extractEmbedding(filePath)
                val savedEmbedding = loadEmbeddingFromPrefs()

                val confidence = if (newEmbedding != null && savedEmbedding != null) {
                    cosineSimilarity(newEmbedding, savedEmbedding)
                } else {
                    0f
                }
                val isMatch = confidence > 0.90f
                Log.e("recordForVerification", "Confidence Score: $confidence")

                Handler(Looper.getMainLooper()).post {
                    onResult(confidence, isMatch)
                }
            } catch (e: Exception) {
                Log.e("recordForVerification", "Exception: ${e.message}")
                audioRecord.release()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    onResult(0f, false)
                }
            }
        }.start()
    }

    suspend fun verifyAudioFile(audioFile: File): Float {
        return withContext(Dispatchers.IO) {
            if (!audioFile.exists()) {
                Log.e("verifyAudioFile", "Audio file does not exist: ${audioFile.absolutePath}")
                return@withContext 0f
            }

            try {
                val newEmbedding = extractEmbedding(audioFile.absolutePath)
                val savedEmbedding = loadEmbeddingFromPrefs()

                val confidence = if (newEmbedding != null && savedEmbedding != null) {
                    cosineSimilarity(newEmbedding, savedEmbedding)
                } else {
                    0f
                }

                Log.d("verifyAudioFile", "Confidence Score: $confidence")
                confidence
            } catch (e: Exception) {
                Log.e("verifyAudioFile", "Exception: ${e.message}")
                0f
            }
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

    fun saveMfcc2DToCsv(context: Context, filename: String, mfcc2D: Array<FloatArray>) {
        try {
            val file = File(context.cacheDir, filename)
            val writer = OutputStreamWriter(FileOutputStream(file))

            // Optional header row
            val header = (0 until mfcc2D[0].size).joinToString(",") { "Coeff_$it" }
            writer.write("$header\n")

            // Write each frame
            for (frame in mfcc2D) {
                val row = frame.joinToString(",") { "%.6f".format(it) }
                writer.write("$row\n")
            }

            writer.close()
            println("Saved MFCC 2D array to ${file.absolutePath}")
        } catch (e: Exception) {
            println("Error saving MFCC 2D to CSV: ${e.message}")
        }
    }

    fun close() {
        modelRunner.close()
    }
}