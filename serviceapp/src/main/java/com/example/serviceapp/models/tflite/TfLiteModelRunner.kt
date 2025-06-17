package com.example.serviceapp.models.tflite

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteModelRunner(context: Context, modelPath: String, private val outputSize: Int, numThreads: Int = 4) {

    private var tflite: Interpreter? = null

    init {
        val tfliteModel = loadModelFile(context, modelPath)
        val options = Interpreter.Options().apply {
            this.numThreads = numThreads
            // Optional: add GPU delegate here if needed
        }
        tflite = Interpreter(tfliteModel, options)
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(modelPath)
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel: FileChannel = inputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
        }
    }

    fun run(input: ByteBuffer): FloatArray {
        val outputArray = Array(1) { FloatArray(outputSize) }
        tflite?.run(input, outputArray)
        return outputArray[0]
    }

    fun close() {
        tflite?.close()
        tflite = null
    }
}
