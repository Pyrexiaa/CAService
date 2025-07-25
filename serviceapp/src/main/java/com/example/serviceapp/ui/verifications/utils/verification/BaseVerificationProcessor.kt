package com.example.serviceapp.ui.verifications.utils.verification

import android.content.Context
import android.util.Log
import com.example.serviceapp.ui.verifications.utils.IndexManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// Abstract class for all modalities processor to extend from
abstract class BaseVerificationProcessor(
    protected val context: Context,
    protected val indexManager: IndexManager,
    private val maxScores: Int = 100
) {

    private val _scoresFlow = MutableStateFlow<List<Float>>(emptyList())
    val scoresFlow: StateFlow<List<Float>> = _scoresFlow

    protected val scores = mutableListOf<Float>()
    protected var previousIndex = -1
    protected var loopJob: Job? = null
    protected var processingJob: Job? = null

    abstract val directoryName: String
    abstract val filePrefix: String
    abstract val fileExtension: String
    abstract val confidenceFileName: String

    fun getCurrentScore(): Float = scores.lastOrNull() ?: 0f

    fun getScoreList(): List<Float> = scores.toList()

    abstract suspend fun processFile(currentIndex: Int): Float

    abstract fun getCurrentIndex(): Int

    fun startVerificationLoop(scope: CoroutineScope) {
        val directory = File(context.filesDir, directoryName)
        if (!directory.exists()) {
            Log.w("BaseVerificationProcessor", "$directoryName directory does not exist.")
            return
        }

        startProcessing(scope)
        startIndexMonitoring(scope)
    }

    private fun startIndexMonitoring(scope: CoroutineScope) {
        loopJob?.cancel()
        loopJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val current = getCurrentIndex()
                if (current != previousIndex) {
                    previousIndex = current
                    _newDetectedFlow.emit(current)
                }
                delay(getMonitoringInterval())
            }
        }
    }

    protected open fun getMonitoringInterval(): Long = 100L

    private val _newDetectedFlow = MutableSharedFlow<Int>(replay = 1)

    private fun startProcessing(scope: CoroutineScope) {
        processingJob?.cancel()
        processingJob = scope.launch(Dispatchers.IO) {
            _newDetectedFlow.collectLatest { currentIndex ->
                val file = File(context.filesDir, "$directoryName/${filePrefix}_${currentIndex}.$fileExtension")
                if (!file.exists()) {
                    Log.d("BaseVerificationProcessor", "File does not exist: ${file.name}")
                    return@collectLatest
                }

                try {
                    val similarity = processFile(currentIndex)
                    Log.d("BaseVerificationProcessor", "$directoryName Index: $currentIndex, Similarity: $similarity")

                    saveScore(similarity)
                    addScore(similarity)
                } catch (e: Exception) {
                    Log.e("BaseVerificationProcessor", "Error processing file: ${e.message}")
                }
            }
        }
    }

    private fun saveScore(score: Float) {
        val scoreFile = File(context.filesDir, "confidence_scores/$confidenceFileName")
        scoreFile.parentFile?.mkdirs()
        scoreFile.appendText("$score\n")
    }

    private fun addScore(score: Float) {
        scores.add(score)
        if (scores.size > maxScores) {
            scores.removeAt(0)
        }
        _scoresFlow.value = scores.toList() // Update the flow
    }

    fun cancel() {
        loopJob?.cancel()
        processingJob?.cancel()
    }
}