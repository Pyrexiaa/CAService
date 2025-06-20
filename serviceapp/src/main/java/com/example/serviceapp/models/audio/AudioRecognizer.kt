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
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.UniversalAudioInputStream
import be.tarsos.dsp.mfcc.MFCC
import com.example.serviceapp.models.tflite.TFLiteModelRunner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.sqrt

class AudioRecognizer(private val context: Context, modelPath: String) {

    private val modelRunner: TFLiteModelRunner = TFLiteModelRunner(context, modelPath, 80)
    private val modelScaler: AudioScaler = AudioScaler()
    private val prefs: SharedPreferences = context.getSharedPreferences("AudioPrefs", Context.MODE_PRIVATE)
    private var enrolledEmbedding: FloatArray? = null

    private fun extractMFCCFeaturesFromWav(filePath: String): FloatArray? {
        val sampleRate = 22050
        val bufferSize = 2048
        val overlap = 512
        val nMFCC = 13

        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            Log.e("MFCCExtractor", "Audio file not found: $filePath")
            return null
        }

        // --- Android-specific way to create AudioDispatcher from a WAV file ---
        var dispatcher: AudioDispatcher? = null
        var fileInputStream: FileInputStream? = null
        try {
            fileInputStream = FileInputStream(audioFile)

            // --- MANUAL WAV HEADER PARSING ---
            // This is a simplified version and needs robust error checking for actual production
            val riffChunkID = ByteArray(4)
            fileInputStream.read(riffChunkID) // "RIFF"
            val riffChunkSize = readLittleEndianInt(fileInputStream) // File size - 8 bytes
            val format = ByteArray(4)
            fileInputStream.read(format) // "WAVE"

            val fmtChunkID = ByteArray(4)
            fileInputStream.read(fmtChunkID) // "fmt "
            val fmtChunkSize = readLittleEndianInt(fileInputStream) // Subchunk size (16 for PCM)

            val numChannels = readLittleEndianShort(fileInputStream).toInt()
            val sampleRate = readLittleEndianInt(fileInputStream)
            val blockAlign = readLittleEndianShort(fileInputStream)
            val bitsPerSample = readLittleEndianShort(fileInputStream).toInt()

            // Skip any extra bytes if fmtChunkSize > 16
            if (fmtChunkSize > 16) {
                fileInputStream.skip((fmtChunkSize - 16).toLong())
            }

            val dataChunkID = ByteArray(4)
            fileInputStream.read(dataChunkID) // "data"

            // You now have the necessary header info
            Log.d("WavHeader", "Sample Rate: $sampleRate, Channels: $numChannels, BitsPerSample: $bitsPerSample")

            val actualSampleRate = sampleRate.toFloat()

            val audioDSPFormat = TarsosDSPAudioFormat(
                TarsosDSPAudioFormat.Encoding.PCM_SIGNED,
                actualSampleRate,
                bitsPerSample,
                numChannels,
                blockAlign.toInt(), // frameSize
                actualSampleRate,
                false // Most WAVs on Android are little-endian
            )

            val audioStream = UniversalAudioInputStream(fileInputStream, audioDSPFormat)
            dispatcher = AudioDispatcher(audioStream, bufferSize, overlap)

        } catch (e: Exception) {
            Log.e("MFCCExtractor", "Error setting up AudioDispatcher for WAV: ${e.message}", e)
            fileInputStream?.close()
            return null
        }

        val mfccList = mutableListOf<FloatArray>()
        val mfcc = MFCC(bufferSize, sampleRate.toFloat(), nMFCC, 20, 300f, (sampleRate / 2).toFloat())
        dispatcher.addAudioProcessor(mfcc)

        dispatcher.addAudioProcessor(object : AudioProcessor {
            override fun processingFinished() {
                Log.d("MFCCExtractor", "Audio processing finished.")
                // No need to close stream here, dispatcher.run() will close it.
            }
            override fun process(audioEvent: AudioEvent): Boolean {
                val currentMfcc = mfcc.mfcc
                if (currentMfcc != null && currentMfcc.isNotEmpty()) {
                    mfccList.add(currentMfcc)
                }
                return true
            }
        })

        try {
            dispatcher.run() // Blocking call
        } catch (e: Exception) {
            Log.e("MFCCExtractor", "Error during dispatcher run: ${e.message}")
            return null
        } finally {
            dispatcher.stop() // Always stop the dispatcher
            fileInputStream.close() // Ensure the file input stream is closed
        }

        if (mfccList.isEmpty()) {
            Log.w("MFCCExtractor", "No MFCC frames extracted. Audio file might be too short or silent.")
            return null
        }

        val filteredMfccList = mfccList.filter { it.size == nMFCC }
        if (filteredMfccList.isEmpty()) {
            Log.e("MFCCExtractor", "No valid MFCC frames of size $nMFCC found.")
            return null
        }

        val mfccArray = filteredMfccList.toTypedArray()

        // Transpose the MFCCs
        val transposed = Array(nMFCC) { mfccIndex ->
            mfccArray.mapNotNull { it.getOrNull(mfccIndex) }.toFloatArray()
        }

        val featureVector = mutableListOf<Float>()
        for (coeff in transposed) {
            if (coeff.isEmpty()) {
                featureVector.addAll(listOf(0f, 0f, 0f, 0f))
                continue
            }
            val mean = coeff.average().toFloat()
            val std = if (coeff.size > 1) {
                sqrt(coeff.map { (it - mean).pow(2) }.average()).toFloat()
            } else {
                0f
            }

            val safeStd = if (std == 0f) 1e-6f else std

            val skew = if (coeff.size > 2) {
                coeff.map { (it - mean).pow(3) }.average() / safeStd.pow(3)
            } else {
                0.0
            }
            val kurt = if (coeff.size > 3) {
                coeff.map { (it - mean).pow(4) }.average() / safeStd.pow(4)
            } else {
                0.0
            }

            featureVector.addAll(listOf(mean, std, skew.toFloat(), kurt.toFloat()))
        }

        return featureVector.toFloatArray()
    }

    // Helper function to read an int from an InputStream (little-endian)
    private fun readLittleEndianInt(stream: FileInputStream): Int {
        val b1 = stream.read().toByte()
        val b2 = stream.read().toByte()
        val b3 = stream.read().toByte()
        val b4 = stream.read().toByte()
        return (b4.toInt() shl 24) or ((b3.toInt() and 0xFF) shl 16) or ((b2.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)
    }

    // Helper function to read a short from an InputStream (little-endian)
    private fun readLittleEndianShort(stream: FileInputStream): Short {
        val b1 = stream.read().toByte()
        val b2 = stream.read().toByte()
        return (((b2.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)).toShort()
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

    private fun extractEmbedding(audioFilePath: String): FloatArray? {
        val inputFeatures = extractMFCCFeaturesFromWav(audioFilePath) ?: return null
        val scaledInputFeatures = modelScaler.applyStandardScaling(inputFeatures)
        return convertToByteBuffer(scaledInputFeatures)
            .let { modelRunner.run(it) } // modelRunner should accept ByteBuffer
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
            throw SecurityException("RECORD_AUDIO permission is not granted")
        }
        val fileName = "enrollment_temp.wav"
        val filePath = File(context.cacheDir, fileName).absolutePath

        Toast.makeText(context, "Recording audio for enrollment...", Toast.LENGTH_SHORT).show()

        val sampleRate = 16000
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
                audioRecord.startRecording()

                var bytesRead = 0
                while (bytesRead < totalBytesToRecord) {
                    val bytesToRead = minOf(bufferSize, totalBytesToRecord - bytesRead)
                    val result = audioRecord.read(audioData, bytesRead, bytesToRead)
                    if (result < 0) {
                        // Handle error appropriately, or break
                        break
                    }
                    bytesRead += result
                }

                audioRecord.stop()
                audioRecord.release()

                // Save raw PCM as WAV file
                writeWavFile(filePath, audioData, sampleRate, 1, 16)

                val embedding = enrollEmbedding(filePath)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Audio enrolled", Toast.LENGTH_SHORT).show()
                    onSuccess(embedding)
                }
            } catch (e: Exception) {
                audioRecord.release()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    onSuccess(null)
                }
            }
        }.start()
    }


    private fun writeWavFile(path: String, audioData: ByteArray, sampleRate: Int, channels: Int, bitDepth: Int) {
        val totalDataLen = audioData.size + 36
        val byteRate = sampleRate * channels * bitDepth / 8

        val out = FileOutputStream(path)
        val header = ByteArray(44)

        // WAV header
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16) // PCM chunk size
        writeShort(header, 20, 1) // PCM format
        writeShort(header, 22, channels.toShort())
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, (channels * bitDepth / 8).toShort()) // block align
        writeShort(header, 34, bitDepth.toShort())
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, audioData.size)

        out.write(header)
        out.write(audioData)
        out.flush()
        out.close()
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

        val filePath = File(context.cacheDir, "verification_temp.wav").absolutePath

        val sampleRate = 16000
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
                audioRecord.startRecording()

                var bytesRead = 0
                while (bytesRead < totalBytesToRecord) {
                    val bytesToRead = minOf(bufferSize, totalBytesToRecord - bytesRead)
                    val result = audioRecord.read(audioData, bytesRead, bytesToRead)
                    if (result < 0) {
                        // Error occurred during read
                        break
                    }
                    bytesRead += result
                }

                audioRecord.stop()
                audioRecord.release()

                // Save the recorded raw PCM data as WAV file
                writeWavFile(filePath, audioData, sampleRate, 1, 16)

                // Extract embedding and perform verification
                val newEmbedding = extractEmbedding(filePath)
                val savedEmbedding = loadEmbeddingFromPrefs()

                val confidence = if (newEmbedding != null && savedEmbedding != null) {
                    cosineSimilarity(newEmbedding, savedEmbedding)
                } else {
                    0f
                }
                val isMatch = confidence > 0.6f

                Handler(Looper.getMainLooper()).post {
                    onResult(confidence, isMatch)
                }
            } catch (e: Exception) {
                audioRecord.release()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    onResult(0f, false)
                }
            }
        }.start()
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