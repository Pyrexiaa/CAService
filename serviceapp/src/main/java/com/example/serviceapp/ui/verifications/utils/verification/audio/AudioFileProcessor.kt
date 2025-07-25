package com.example.serviceapp.ui.verifications.utils.verification.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object AudioFileProcessor {
    fun mergeWavFiles(inputFiles: List<File>, outputFile: File) {
        val outputStream = FileOutputStream(outputFile)
        val combinedAudioData = ByteArrayOutputStream()

        inputFiles.forEachIndexed { index, file ->
            val bytes = file.readBytes()
            if (index == 0) {
                combinedAudioData.write(bytes)
            } else {
                combinedAudioData.write(bytes.copyOfRange(44, bytes.size))
            }
        }

        val fullAudio = combinedAudioData.toByteArray()
        updateWavHeader(fullAudio)

        outputStream.write(fullAudio)
        outputStream.close()
    }

    private fun updateWavHeader(wavData: ByteArray) {
        val totalDataLen = wavData.size - 8
        val totalAudioLen = wavData.size - 44
        val sampleRate = 16000
        val channels = 1
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
}