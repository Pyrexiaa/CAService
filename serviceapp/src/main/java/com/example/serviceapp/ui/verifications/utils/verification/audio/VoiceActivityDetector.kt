package com.example.serviceapp.ui.verifications.utils.verification.audio

import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VoiceActivityDetector {
    fun isVoiceDetectedInFile(audioFile: File): Boolean {
        val inputStream = DataInputStream(FileInputStream(audioFile))

        val header = ByteArray(44)
        inputStream.readFully(header)

        val audioBytes = inputStream.readBytes()
        inputStream.close()

        val shortBuffer = ByteBuffer.wrap(audioBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val audioData = ShortArray(shortBuffer.remaining())
        shortBuffer.get(audioData)

        VadWebRTC(
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_320,
            mode = Mode.VERY_AGGRESSIVE,
            silenceDurationMs = 1000,
            speechDurationMs = 500
        ).use { vad ->
            val frameSize = 320
            var speechFrameCount = 0

            for (i in 0 until audioData.size step frameSize) {
                if (i + frameSize > audioData.size) break

                val frame = audioData.copyOfRange(i, i + frameSize)
                val isSpeech = vad.isSpeech(frame)

                if (isSpeech) speechFrameCount++
            }

            return speechFrameCount > 320
        }
    }
}