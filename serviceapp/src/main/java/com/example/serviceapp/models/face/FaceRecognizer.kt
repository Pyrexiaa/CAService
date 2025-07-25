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
import androidx.core.graphics.get

class FaceRecognizer(private val context: Context, modelPath: String) {

    private val modelRunner: TFLiteModelRunner = TFLiteModelRunner(context, modelPath, 80013)
    private val prefs: SharedPreferences = context.getSharedPreferences("FacePrefs", Context.MODE_PRIVATE)
    private var enrolledEmbedding: FloatArray? = null

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val inputSize = 128
        val numChannels = 1
        val batchSize = 1

        // Resize the bitmap to 128x128
        val resizedBitmap = bitmap.scale(inputSize, inputSize)

        // Prepare the ByteBuffer (float32 = 4 bytes)
        val byteBuffer = ByteBuffer.allocateDirect(batchSize * numChannels * inputSize * inputSize * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        // Convert to grayscale and normalize
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = resizedBitmap[x, y]

                // Extract RGB values
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Convert to grayscale using standard luminance formula
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()

                // Normalize to [0, 1]
                val normalized = gray / 255.0f

                byteBuffer.putFloat(normalized)
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun extractEmbedding(bitmap: Bitmap): FloatArray {
        val resizedBitmap = bitmap.scale(128, 128)
        val inputBuffer = preprocessImage(resizedBitmap)
        val embedding = modelRunner.run(inputBuffer)
        return embedding
    }

    fun enrollEmbedding(bitmap: Bitmap, orientation: String) {
        enrolledEmbedding = extractEmbedding(bitmap)
        saveEmbeddingToPrefs(enrolledEmbedding!!, orientation)
        Log.d("EnrollEmbedding", "Embedding extracted for $orientation")
    }


    private fun saveEmbeddingToPrefs(embedding: FloatArray, orientation: String) {
        val str = embedding.joinToString(",")
        val success = prefs.edit().putString("embedding_${orientation}", str).commit()

        if (success) {
            Log.d("FaceEmbedding", "Face embedding saved successfully!")
        } else {
            Log.d("FaceEmbedding", "Failed to save face embedding!")
        }
        Log.d("SaveEmbedding", "Saving embedding for $orientation with size ${embedding.size}")
    }

    fun averageEmbedding() {
        val orientations = listOf("Center", "Left", "Right", "Up", "Down")
        val embeddings = mutableListOf<FloatArray>()

        for (orientation in orientations) {
            val str = prefs.getString("embedding_$orientation", null)
            if (str != null) {
                val floatArray = str.split(",").map { it.toFloat() }.toFloatArray()
                embeddings.add(floatArray)
            } else {
                Toast.makeText(context, "Missing embedding for $orientation", Toast.LENGTH_SHORT).show()
                return // Exit early if any embedding is missing
            }
        }

        if (embeddings.isNotEmpty()) {
            val embeddingSize = embeddings[0].size
            val averaged = FloatArray(embeddingSize)

            for (i in 0 until embeddingSize) {
                averaged[i] = embeddings.map { it[i] }.average().toFloat()
            }

            // Save averaged embedding
            val avgStr = averaged.joinToString(",")
            val success = prefs.edit().putString("embedding", avgStr).commit()

            if (success) {
                Log.d("FaceEmbedding", "Average embedding saved successfully!")
            } else {
                Log.d("FaceEmbedding", "Failed to save average embedding!")
            }
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
