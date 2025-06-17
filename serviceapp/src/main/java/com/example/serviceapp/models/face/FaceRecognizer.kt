package com.example.serviceapp.models.face

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import com.example.serviceapp.models.tflite.TFLiteModelRunner
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.core.graphics.scale
import androidx.core.content.edit

class FaceRecognizer(private val context: Context, modelPath: String) {

    private val modelRunner: TFLiteModelRunner = TFLiteModelRunner(context, modelPath, 80013)
    private val prefs: SharedPreferences = context.getSharedPreferences("FacePrefs", Context.MODE_PRIVATE)
    private var enrolledEmbedding: FloatArray? = null

    fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val inputSize = 16
        val resizedBitmap = bitmap.scale(inputSize, inputSize)

        // We'll create a float array for 3 channels * 16 * 16 = 768 values
        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val tensor768 = FloatArray(3 * inputSize * inputSize)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (Color.red(color) / 255.0f - 0.5f) / 0.5f  // normalize ~ [-1, 1]
            val g = (Color.green(color) / 255.0f - 0.5f) / 0.5f
            val b = (Color.blue(color) / 255.0f - 0.5f) / 0.5f
            // Store in CHW order like PyTorch (C=3, H=16, W=16)
            val x = i % inputSize
            val y = i / inputSize
            tensor768[0 * 256 + y * inputSize + x] = r
            tensor768[1 * 256 + y * inputSize + x] = g
            tensor768[2 * 256 + y * inputSize + x] = b
        }

        // Now reduce 768 → 256 by average pooling along channel dimension
        val tensor256 = FloatArray(256)
        for (i in 0 until 256) {
            // average r,g,b values at corresponding positions
            tensor256[i] = (tensor768[i] + tensor768[i + 256] + tensor768[i + 512]) / 3f
        }

        // Convert to ByteBuffer for model input
        val byteBuffer = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
        tensor256.forEach { byteBuffer.putFloat(it) }
        byteBuffer.rewind()

        return byteBuffer
    }


    fun extractEmbedding(bitmap: Bitmap): FloatArray {
        val resizedBitmap = bitmap.scale(112, 112)
        val inputBuffer = preprocessImage(resizedBitmap)
        val embedding = modelRunner.run(inputBuffer)
        return embedding
//        return l2Normalize(embedding)
    }

    fun enrollEmbedding(bitmap: Bitmap) {
        enrolledEmbedding = extractEmbedding(bitmap)
        saveEmbeddingToPrefs(enrolledEmbedding!!)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) {
            sum += v * v
        }
        val norm = sqrt(sum)
        return vector.map { it / norm }.toFloatArray()
    }


    private fun saveEmbeddingToPrefs(embedding: FloatArray) {
        val str = embedding.joinToString(",")
        val success = prefs.edit().putString("embedding", str).commit()  // commit() returns Boolean

        if (success) {
            Toast.makeText(context, "Face embedding saved successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to save face embedding!", Toast.LENGTH_SHORT).show()
        }
    }

    fun debugPrintEmbeddingPrefs() {
        val stored = prefs.getString("embedding", null)
        Log.d("FaceRecognizer", "Stored embedding string: $stored")
    }

    fun loadEmbeddingFromPrefs(): FloatArray? {
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

    fun verifyFace(bitmap: Bitmap): Float {
        val newEmbedding = extractEmbedding(bitmap)
        debugPrintEmbeddingPrefs()
        val savedEmbedding = loadEmbeddingFromPrefs()
        if (savedEmbedding == null) {
            Toast.makeText(context, "No saved embedding to verify against!", Toast.LENGTH_SHORT).show()
            return 0f
        }
        Log.d("New Embedding", newEmbedding.toString())
        Log.d("Loaded Embedding", savedEmbedding.toString())
        val similarity = cosineSimilarity(newEmbedding, savedEmbedding)
        return similarity
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
