package com.example.serviceapp.ui.verifications.utils.verification.gait

import android.content.Context
import android.util.Log
import com.example.serviceapp.ui.verifications.utils.IndexManager
import com.example.serviceapp.ui.verifications.utils.VerificationManager
import com.example.serviceapp.ui.verifications.utils.verification.BaseVerificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GaitVerificationProcessor(
    context: Context,
    indexManager: IndexManager,
    private val verificationManager: VerificationManager
) : BaseVerificationProcessor(context, indexManager) {

    override val directoryName = "sensor"
    override val filePrefix = "sensor"
    override val fileExtension = "txt"
    override val confidenceFileName = "gait.txt"

    override fun getCurrentIndex(): Int = indexManager.gaitIndex

    override suspend fun processFile(currentIndex: Int): Float {
        return verifyGaitSuspending(currentIndex)
    }

    private suspend fun verifyGaitSuspending(currentIndex: Int): Float {
        val gaitDir = File(context.filesDir, directoryName)
        val segmentFiles = (currentIndex - 4..currentIndex).map { index ->
            File(gaitDir, "${filePrefix}_${index}.$fileExtension")
        }

        if (segmentFiles.any { !it.exists() }) {
            Log.d("GaitVerificationProcessor", "Insufficient gait files for verification at index: $currentIndex")
            return 0f
        }

        return withContext(Dispatchers.IO) {
            try {
                val mergedFile = File(gaitDir, "merged_gait_temp.txt")
                GaitFileProcessor.mergeTxtFiles(segmentFiles, mergedFile)
                verificationManager.getGaitRecognizer().verifyGaitFile(mergedFile)
            } catch (e: Exception) {
                Log.e("GaitVerificationProcessor", "Error during gait verification: ${e.message}")
                0f
            }
        }
    }
}