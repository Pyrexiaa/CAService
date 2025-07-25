package com.example.serviceapp.ui.verifications.utils.verification.audio

import android.content.Context
import android.util.Log
import com.example.serviceapp.ui.verifications.utils.IndexManager
import com.example.serviceapp.ui.verifications.utils.VerificationManager
import com.example.serviceapp.ui.verifications.utils.verification.BaseVerificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioVerificationProcessor(
    context: Context,
    indexManager: IndexManager,
    private val verificationManager: VerificationManager
) : BaseVerificationProcessor(context, indexManager) {

    override val directoryName = "audio"
    override val filePrefix = "audio"
    override val fileExtension = "wav"
    override val confidenceFileName = "audio.txt"

    override fun getCurrentIndex(): Int = indexManager.audioIndex

    override suspend fun processFile(currentIndex: Int): Float {
        return verifyAudioSuspending(currentIndex)
    }

    private suspend fun verifyAudioSuspending(currentIndex: Int): Float {
        val audioDir = File(context.filesDir, directoryName)
        val segmentFiles = (currentIndex - 4..currentIndex).map { index ->
            File(audioDir, "${filePrefix}_${index}.$fileExtension")
        }

        if (segmentFiles.any { !it.exists() }) {
            Log.d("AudioVerificationProcessor", "Insufficient audio files for verification at index: $currentIndex")
            return 0f
        }

        return withContext(Dispatchers.IO) {
            try {
                val mergedFile = File(audioDir, "merged_audio_temp.wav")
                AudioFileProcessor.mergeWavFiles(segmentFiles, mergedFile)

                val isVoiceDetected = VoiceActivityDetector.isVoiceDetectedInFile(mergedFile)
                if (!isVoiceDetected) {
                    Log.d("AudioVerificationProcessor", "No voice detected in merged audio for index: $currentIndex")
                    return@withContext 0f
                }

                verificationManager.getAudioRecognizer().verifyAudioFile(mergedFile)
            } catch (e: Exception) {
                Log.e("AudioVerificationProcessor", "Error during audio verification: ${e.message}")
                0f
            }
        }
    }
}