package com.example.serviceapp.ui.verifications

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.serviceapp.databinding.FragmentVerificationsBinding
import com.example.serviceapp.ui.verifications.utils.VerificationManager
import com.example.serviceapp.ui.verifications.utils.IndexManager
import com.example.serviceapp.ui.verifications.utils.ChartManager
import com.example.serviceapp.ui.verifications.utils.CombinedScoreCalculator
import com.example.serviceapp.ui.verifications.utils.enrollment.EnrollmentChecker
import com.example.serviceapp.ui.verifications.utils.verification.audio.AudioVerificationProcessor
import com.example.serviceapp.ui.verifications.utils.verification.face.FaceVerificationProcessor
import com.example.serviceapp.ui.verifications.utils.verification.gait.GaitVerificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VerificationsFragment : Fragment() {

    private var _binding: FragmentVerificationsBinding? = null
    private val binding get() = _binding!!

    private lateinit var verificationManager: VerificationManager
    private lateinit var enrollmentChecker: EnrollmentChecker
    private lateinit var indexManager: IndexManager
    private lateinit var chartManager: ChartManager
    private lateinit var combinedScoreCalculator: CombinedScoreCalculator

    private lateinit var faceVerifier: FaceVerificationProcessor
    private lateinit var audioVerifier: AudioVerificationProcessor
    private lateinit var gaitVerifier: GaitVerificationProcessor

    private val combinedScores = mutableListOf<Float>()
    private var combinedLoopJob: Job? = null

    // Chart update jobs
    private var faceChartJob: Job? = null
    private var audioChartJob: Job? = null
    private var gaitChartJob: Job? = null

    companion object {
        private var instance: VerificationsFragment? = null
        fun getInstance(): VerificationsFragment? = instance
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVerificationsBinding.inflate(inflater, container, false)
        instance = this
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeComponents()
        startVerificationProcesses()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopAllProcesses()
        instance = null
        _binding = null
    }

    private fun initializeComponents() {
        verificationManager = VerificationManager(requireContext())
        enrollmentChecker = EnrollmentChecker(requireContext())
        indexManager = IndexManager(requireContext())
        chartManager = ChartManager()
        combinedScoreCalculator = CombinedScoreCalculator()

        // Initialize processors
        faceVerifier = FaceVerificationProcessor(requireContext(), indexManager, verificationManager)
        audioVerifier = AudioVerificationProcessor(requireContext(), indexManager, verificationManager)
        gaitVerifier = GaitVerificationProcessor(requireContext(), indexManager, verificationManager)

        verificationManager.initializeVerifiers(faceVerifier, audioVerifier, gaitVerifier)
    }

    private fun startVerificationProcesses() {
        val enrollmentStatus = enrollmentChecker.getEnrollmentStatus()

        Log.d("RefactoredVerificationsFragment", "Face enrolled: ${enrollmentStatus.isFaceEnrolled}")
        Log.d("RefactoredVerificationsFragment", "Audio enrolled: ${enrollmentStatus.isAudioEnrolled}")
        Log.d("RefactoredVerificationsFragment", "Gait enrolled: ${enrollmentStatus.isGaitEnrolled}")

        // Start individual verification loops
        if (enrollmentStatus.isFaceEnrolled) {
            faceVerifier.startVerificationLoop(viewLifecycleOwner.lifecycleScope)
            startFaceChartUpdates()
        }

        if (enrollmentStatus.isAudioEnrolled) {
            audioVerifier.startVerificationLoop(viewLifecycleOwner.lifecycleScope)
            startAudioChartUpdates()
        }

        if (enrollmentStatus.isGaitEnrolled) {
            gaitVerifier.startVerificationLoop(viewLifecycleOwner.lifecycleScope)
            startGaitChartUpdates()
        }

        // Start combined verification if all modalities are enrolled
        if (enrollmentStatus.allEnrolled) {
            viewLifecycleOwner.lifecycleScope.launch {
                delay(1000) // 1 second delay as in original
                startCombinedVerificationLoop()
            }
        }
    }

    private fun startFaceChartUpdates() {
        faceChartJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            faceVerifier.scoresFlow.collect { scores ->
                withContext(Dispatchers.Main) {
                    _binding?.let { binding ->
                        chartManager.setupLineChartAppearance(
                            binding.chartFace,
                            "Real-time Face Confidence"
                        )
                        chartManager.plotLineChartData(binding.chartFace, "Face", scores)
                    }
                }
            }
        }
    }

    private fun startAudioChartUpdates() {
        audioChartJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            audioVerifier.scoresFlow.collect { scores ->
                withContext(Dispatchers.Main) {
                    _binding?.let { binding ->
                        chartManager.setupLineChartAppearance(
                            binding.chartAudio,
                            "Real-time Audio Confidence"
                        )
                        chartManager.plotLineChartData(binding.chartAudio, "Audio", scores)
                    }
                }
            }
        }
    }

    private fun startGaitChartUpdates() {
        gaitChartJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            gaitVerifier.scoresFlow.collect { scores ->
                withContext(Dispatchers.Main) {
                    _binding?.let { binding ->
                        chartManager.setupLineChartAppearance(
                            binding.chartGait,
                            "Real-time Gait Confidence"
                        )
                        chartManager.plotLineChartData(binding.chartGait, "Gait", scores)
                    }
                }
            }
        }
    }

    private fun startCombinedVerificationLoop() {
        combinedLoopJob?.cancel()

        var lastPlottedCombinedScore = 0f
        var hasPlottedOnce = false

        combinedLoopJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val latestScores = verificationManager.getCurrentScores()
                val latestFace = latestScores["face_score"]
                val latestAudio = latestScores["audio_score"]
                val latestGait = latestScores["gait_score"]

                Log.d("CombinedConfidenceScore", "Face Score: $latestFace")
                Log.d("CombinedConfidenceScore", "Audio Score: $latestAudio")
                Log.d("CombinedConfidenceScore", "Gait Score: $latestGait")

                val scoresToCombine = listOfNotNull(latestFace, latestAudio, latestGait)
                    .filter { it in 0f..1f }

                if (scoresToCombine.isNotEmpty()) {
                    val weightedScore = combinedScoreCalculator.calculateWeightedScore(
                        latestFace, latestAudio, latestGait
                    ) ?: 0f  // score = 0 if null

                    // Plot only if it's the first time or the score changed
                    val shouldPlot = !hasPlottedOnce || weightedScore != lastPlottedCombinedScore

                    if (shouldPlot) {
                        lastPlottedCombinedScore = weightedScore
                        hasPlottedOnce = true

                        Log.d("CombinedConfidenceScore", "Weighted Score: $weightedScore")
                        combinedScores.add(weightedScore)

                        if (combinedScores.size > 100) {
                            combinedScores.removeAt(0)
                        }

                        // Save combined score to file
                        saveCombinedScoreToFile(weightedScore)

                        withContext(Dispatchers.Main) {
                            _binding?.let { binding ->
                                updateCombinedCharts(binding, weightedScore, latestFace, latestAudio, latestGait)
                            }
                        }
                    }
                }

                delay(2000)
            }
        }
    }

    private fun updateCombinedCharts(
        binding: FragmentVerificationsBinding,
        weightedScore: Float,
        latestFace: Float?,
        latestAudio: Float?,
        latestGait: Float?
    ) {
        val isAttackDetected = combinedScores.last() < 0.2f

        // Update combined line chart
        chartManager.setupLineChartAppearance(
            binding.chartCombined,
            "Real-time Combined Confidence",
            isAttackDetected
        )
        chartManager.plotLineChartData(binding.chartCombined, "Combined", combinedScores)

        // Update doughnut charts
        chartManager.setupDoughnutChartAppearance(binding.chartDoughnutFace, "Face")
        chartManager.setupDoughnutChartAppearance(binding.chartDoughnutAudio, "Audio")
        chartManager.setupDoughnutChartAppearance(binding.chartDoughnutGait, "Gait")

        chartManager.plotDoughnutChartData(binding.chartDoughnutFace, latestFace ?: 0.0f, "Face")
        chartManager.plotDoughnutChartData(binding.chartDoughnutAudio, latestAudio ?: 0.0f, "Audio")
        chartManager.plotDoughnutChartData(binding.chartDoughnutGait, latestGait ?: 0.0f, "Gait")
    }

    private fun saveCombinedScoreToFile(score: Float) {
        try {
            val combinedTxtFile = File(requireContext().filesDir, "confidence_scores/combined.txt")
            combinedTxtFile.parentFile?.mkdirs()
            combinedTxtFile.appendText("$score\n")
        } catch (e: Exception) {
            Log.e("RefactoredVerificationsFragment", "Error saving combined score: ${e.message}")
        }
    }

    private fun stopAllProcesses() {
        faceVerifier.cancel()
        audioVerifier.cancel()
        gaitVerifier.cancel()

        faceChartJob?.cancel()
        audioChartJob?.cancel()
        gaitChartJob?.cancel()
        combinedLoopJob?.cancel()
    }

    // Method to get current verification scores (for external access)
    fun getCurrentVerificationScores(): Map<String, Float> {
        return verificationManager.getCurrentScores().toMutableMap().apply {
            put("combined_score", combinedScores.lastOrNull() ?: 0f)
        }
    }
}