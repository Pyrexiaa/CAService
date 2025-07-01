package com.example.serviceapp.models.audio

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.File
import java.io.FileInputStream
import java.io.DataInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MfccFeatureExtractor(private val context: Context, private val modelFileName: String) {

    private var tflite: Interpreter? = null

    init {
        try {
            // Load the TFLite model from the assets folder or a file path
            val assetFileDescriptor = context.assets.openFd(modelFileName)
            val inputStream = assetFileDescriptor.createInputStream()
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                useXNNPACK = false
                useNNAPI = false
                numThreads = 1
            }
            options.addDelegate(FlexDelegate())
            tflite = Interpreter(modelBuffer, options)

            // --- IMPORTANT DEBUGGING STEP: INSPECT MODEL INPUTS ---
            Log.d("MfccFeatureExtractor", "TFLite model loaded successfully.")
            if (tflite != null) {
                val inputTensorCount = tflite!!.inputTensorCount
                Log.d("MfccFeatureExtractor", "Input Tensor Count: $inputTensorCount")
                for (i in 0 until inputTensorCount) {
                    val inputTensor = tflite!!.getInputTensor(i)
                    Log.d("MfccFeatureExtractor", "Input Tensor $i Name: ${inputTensor.name()}")
                    Log.d("MfccFeatureExtractor", "Input Tensor $i Shape: ${inputTensor.shape().contentToString()}")
                    Log.d("MfccFeatureExtractor", "Input Tensor $i Type: ${inputTensor.dataType()}")
                    Log.d("MfccFeatureExtractor", "Input Tensor $i Bytes: ${inputTensor.numBytes()}")
                }
            }

        } catch (e: Exception) {
            Log.e("MfccFeatureExtractor", "Error loading TFLite model: ${e.message}", e)
            tflite = null
        }
    }

    /**
     * Converts a FloatArray to a ByteBuffer suitable for TensorFlow Lite.
     * TFLite typically expects FLOAT32, which is 4 bytes per float.
     */
    private fun convertFloatArrayToByteBuffer(array: FloatArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(array.size * 4)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        array.forEach { byteBuffer.putFloat(it) }
        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * Extracts MFCC features from a WAV audio file using the loaded TFLite model.
     *
     * @param audioFilePath The path to the WAV audio file.
     * @return A FloatArray containing the extracted MFCC features, or null if an error occurs.
     */
    fun extractMFCCFeaturesFromWav(audioFilePath: String): FloatArray? {
        if (tflite == null) {
            Log.e("MfccFeatureExtractor", "TFLite interpreter is not initialized.")
            return null
        }

        try {
            val file = File(audioFilePath)
            val inputStream = DataInputStream(FileInputStream(file))

            // Skip WAV header (44 bytes for standard WAV)
            val header = ByteArray(44)
            inputStream.readFully(header)

            // Read raw PCM 16-bit audio bytes
            val audioBytes = inputStream.readBytes()
            inputStream.close()

            val numSamples = audioBytes.size / 2 // 2 bytes per 16-bit sample
            val rawAudio = FloatArray(numSamples)
            val byteBuffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numSamples) {
                val sample = byteBuffer.short.toInt()
                rawAudio[i] = sample / 32768f  // Normalize to [-1, 1]
            }

            // Pad or trim to fixed length (must be power of two for FFT)
            val desiredNumFrames = 256  // power of two
            val n_fft = 2048
            val hop_length = 512
            val targetNumSamples = (desiredNumFrames - 1) * hop_length + n_fft  // 132608
            val fixedLengthAudio = FloatArray(targetNumSamples)

            if (rawAudio.size >= targetNumSamples) {
                System.arraycopy(rawAudio, 0, fixedLengthAudio, 0, targetNumSamples)
            } else {
                System.arraycopy(rawAudio, 0, fixedLengthAudio, 0, rawAudio.size)
                // Remaining values are already zero-initialized (silence padding)
            }
            saveFeaturesToCsv(context, "mfcc_raw_fixed_audio.csv", fixedLengthAudio)

            // Prepare the input ByteBuffer for the TFLite model
            val inputBuffer = convertFloatArrayToByteBuffer(fixedLengthAudio)

            // Output shape: [1, num_frames, n_mfcc]
            val outputNumFrames = 256
            val outputNMfcc = 13
            val outputArray = Array(1) { Array(outputNumFrames) { FloatArray(outputNMfcc) } }

            Log.d("MFCCDebug", "Output shape: [${outputArray.size}, ${outputArray[0].size}, ${outputArray[0][0].size}]")
            Log.d("MFCCDebug", "Input fixedLengthAudio size: ${fixedLengthAudio.size}")
            Log.d("MFCCDebug", "Input tensor shape: ${tflite!!.getInputTensor(0).shape().contentToString()}")

            // Run inference
            tflite!!.run(inputBuffer, outputArray)

            // Flatten to 1D FloatArray
            val flattenedMfccs = outputArray[0].flatMap { it.asIterable() }.toFloatArray()
            Log.d("MfccFeatureExtractor", "MFCC extraction successful. Output size: ${flattenedMfccs.size}")
            saveFeaturesToCsv(context, "mfcc_raw_features.csv", flattenedMfccs)
            return flattenedMfccs

        } catch (e: Exception) {
            Log.e("MfccFeatureExtractor", "Error extracting MFCC features: ${e.message}", e)
            return null
        }
    }

    fun extractMfccFromWindow(windowAudio: FloatArray, index: Int): FloatArray? {
        try {
            val desiredNumFrames = 256
            val n_fft = 2048
            val hop_length = 512
            val targetNumSamples = (desiredNumFrames - 1) * hop_length + n_fft

            val fixedLengthAudio = FloatArray(targetNumSamples)
            if (windowAudio.size >= targetNumSamples) {
                System.arraycopy(windowAudio, 0, fixedLengthAudio, 0, targetNumSamples)
            } else {
                System.arraycopy(windowAudio, 0, fixedLengthAudio, 0, windowAudio.size)
            }

            val inputBuffer = convertFloatArrayToByteBuffer(fixedLengthAudio)

            val outputArray = Array(1) { Array(256) { FloatArray(13) } }
            tflite?.run(inputBuffer, outputArray)

            val flattenedMfccs = outputArray[0].flatMap { it.asIterable() }.toFloatArray()
            Log.d("MfccFeatureExtractor", "MFCC extraction successful. Output size: ${flattenedMfccs.size}")
            saveFeaturesToCsv(context, "mfcc_raw_features_$index.csv", flattenedMfccs)
            return flattenedMfccs

        } catch (e: Exception) {
            Log.e("MFCCWindow", "Error extracting MFCC from window: ${e.message}", e)
            return null
        }
    }


    private fun saveFeaturesToCsv(context: Context, filename: String, values: FloatArray) {
        try {
            val file = File(context.cacheDir, filename)
            val writer = OutputStreamWriter(FileOutputStream(file))

            // Write each value on a new line
            for (value in values) {
                writer.write("%.6f\n".format(value))
            }

            writer.close()
            println("Saved features to ${file.absolutePath}")
        } catch (e: Exception) {
            println("Error saving features to CSV: ${e.message}")
        }
    }

    fun close() {
        tflite?.close()
        tflite = null
    }
}