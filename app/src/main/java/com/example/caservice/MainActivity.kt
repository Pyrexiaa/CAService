package com.example.caservice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.example.caservice.databinding.ActivityMainBinding
import androidx.core.graphics.toColorInt

class ControllerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceMessenger: Messenger? = null
    private var isLoading = false  // Add loading state tracking

    companion object {
        const val MSG_GET_SENSOR = 1
        const val MSG_SENSOR_RESPONSE = 2
        const val MSG_START_RECORDING = 3
        const val MSG_GET_VERIFICATION_SCORES = 4
        const val MSG_VERIFICATION_SCORES_RESPONSE = 5

        private const val LOADING_DELAY_MS = 1000L
        private const val DEFAULT_SCORE_VALUE = 0f
    }

    private val clientMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        handleIncomingMessage(message)
    })

    private fun handleIncomingMessage(message: Message): Boolean {
        return when (message.what) {
            MSG_SENSOR_RESPONSE -> {
                handleSensorResponse()
                true
            }
            MSG_VERIFICATION_SCORES_RESPONSE -> {
                handleVerificationScoresResponse(message)
                true
            }
            else -> {
                Log.w("ControllerActivity", "Unknown message type: ${message.what}")
                false
            }
        }
    }

    private fun handleSensorResponse() {
        Log.d("ControllerActivity", "Received sensor response")

        showLoadingState()

        // Simulate processing delay
        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvCheckInstruction.text = "Sensors are turned on"
            hideLoadingState()
        }, LOADING_DELAY_MS)
    }

    private fun handleVerificationScoresResponse(message: Message) {
        Log.d("ControllerActivity", "Received verification scores response")

        val bundle = message.data
        val faceScore = bundle.getFloat("face_score", DEFAULT_SCORE_VALUE)
        val audioScore = bundle.getFloat("audio_score", DEFAULT_SCORE_VALUE)
        val gaitScore = bundle.getFloat("gait_score", DEFAULT_SCORE_VALUE)
        val combinedScore = bundle.getFloat("combined_score", DEFAULT_SCORE_VALUE)

        updateUIWithScores(faceScore, audioScore, gaitScore, combinedScore)
        hideLoadingState()
    }

    private fun updateUIWithScores(faceScore: Float, audioScore: Float, gaitScore: Float, combinedScore: Float) {
        val (displayScore, scoreName) = determineDisplayScore(faceScore, audioScore, gaitScore, combinedScore)

        if (displayScore >= 0) {
            val percentageScore = (displayScore * 100).toInt()
            binding.tvScoreValue.text = percentageScore.toString()
            binding.tvScoreValueDenominator.text = "/100"
            Log.d("ControllerActivity", "$scoreName Score: $displayScore ($percentageScore%)")
        } else {
            binding.tvScoreValue.text = "N/A"
            binding.tvScoreValueDenominator.text = ""
            Log.w("ControllerActivity", "No verification scores available")
        }

        updateButtonColor(displayScore)
    }

    private fun determineDisplayScore(faceScore: Float, audioScore: Float, gaitScore: Float, combinedScore: Float): Pair<Float, String> {
        return when {
            combinedScore >= 0 -> {
                Log.d("ControllerActivity", "All scores - Combined: $combinedScore, Face: $faceScore, Audio: $audioScore, Gait: $gaitScore")
                combinedScore to "Combined"
            }
            faceScore >= 0 -> faceScore to "Face"
            audioScore >= 0 -> audioScore to "Audio"
            gaitScore >= 0 -> gaitScore to "Gait"
            else -> DEFAULT_SCORE_VALUE to "None"
        }
    }

    private fun updateButtonColor(score: Float) {
        val color = when {
            score >= 0.8f -> "#008000"  // Green - high confidence
            score >= 0.6f -> "#FFA500"  // Orange - medium confidence
            score >= 0.4f -> "#FF8C00"  // Dark orange - low-medium confidence
            score >= 0f -> "#FF0000"    // Red - low confidence
            else -> "#808080"           // Gray - no data
        }
        binding.fabCheckSensor.backgroundTintList = ColorStateList.valueOf(color.toColorInt())
    }

    private fun showLoadingState() {
        if (isLoading) {
            Log.d("ControllerActivity", "Already in loading state, ignoring")
            return
        }

        isLoading = true
        Log.d("ControllerActivity", "Showing loading state")

        binding.progressBar.visibility = View.VISIBLE
        binding.fabCheckSensor.isEnabled = false
        binding.fabCheckSensor.alpha = 0.6f
        binding.tvScoreValue.text = "Loading..."
        binding.tvScoreValueDenominator.text = ""
        binding.fabCheckSensor.backgroundTintList = ColorStateList.valueOf("#7B1FA2".toColorInt())
    }

    private fun hideLoadingState() {
        if (!isLoading) {
            Log.d("ControllerActivity", "Not in loading state, ignoring")
            return
        }

        isLoading = false
        Log.d("ControllerActivity", "Hiding loading state")

        binding.progressBar.visibility = View.GONE
        binding.fabCheckSensor.isEnabled = true
        binding.fabCheckSensor.alpha = 1.0f
        // Note: Button color will be set by updateButtonColor() based on score
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
            Log.d("ControllerActivity", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            Log.d("ControllerActivity", "Service disconnected")

            // If service disconnects while loading, reset state
            if (isLoading) {
                hideLoadingState()
            }
        }
    }

    private fun sendStartRecordingCommand() {
        serviceMessenger?.let { messenger ->
            try {
                val message = Message.obtain(null, MSG_START_RECORDING).apply {
                    replyTo = clientMessenger
                }
                messenger.send(message)
                Log.d("ControllerActivity", "Start recording command sent")
            } catch (e: RemoteException) {
                Log.e("ControllerActivity", "Failed to send start recording command", e)
                hideLoadingState() // Reset loading state on error
            }
        } ?: run {
            Log.w("ControllerActivity", "Service not connected, cannot send start recording command")
            hideLoadingState() // Reset loading state if service not available
        }
    }

    private fun requestVerificationScores() {
        serviceMessenger?.let { messenger ->
            try {
                val message = Message.obtain(null, MSG_GET_VERIFICATION_SCORES).apply {
                    replyTo = clientMessenger
                }
                messenger.send(message)
                Log.d("ControllerActivity", "Verification scores request sent")
            } catch (e: RemoteException) {
                Log.e("ControllerActivity", "Failed to request verification scores", e)
                hideLoadingState() // Reset loading state on error
            }
        } ?: run {
            Log.w("ControllerActivity", "Service not connected, cannot request verification scores")
            hideLoadingState() // Reset loading state if service not available
        }
    }

    private fun requestSensorStatus() {
        serviceMessenger?.let { messenger ->
            try {
                val message = Message.obtain(null, MSG_GET_SENSOR).apply {
                    replyTo = clientMessenger
                }
                messenger.send(message)
                Log.d("ControllerActivity", "Sensor status request sent")
            } catch (e: RemoteException) {
                Log.e("ControllerActivity", "Failed to request sensor status", e)
                hideLoadingState() // Reset loading state on error
            }
        } ?: run {
            Log.w("ControllerActivity", "Service not connected, cannot request sensor status")
            hideLoadingState() // Reset loading state if service not available
        }
    }

    private fun bindToService() {
        val intent = Intent().apply {
            setClassName("com.example.serviceapp", "com.example.serviceapp.service.SmartService")
        }

        val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (bound) {
            Log.d("ControllerActivity", "Service binding initiated")
        } else {
            Log.e("ControllerActivity", "Failed to bind to service")
        }
    }

    private fun handleButtonClick() {
        if (isLoading) {
            Log.d("ControllerActivity", "Button clicked while loading, ignoring")
            return
        }

        if (serviceMessenger == null) {
            Log.w("ControllerActivity", "Service not connected")
            binding.tvCheckInstruction.text = "Service not available"
            return
        }

        Log.d("ControllerActivity", "Button clicked, starting verification process")
        showLoadingState()

        // Send both commands
        sendStartRecordingCommand()
        requestSensorStatus()
        requestVerificationScores()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        bindToService()
        setupClickListeners()
    }

    private fun setupNavigation() {
        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        navView.setupWithNavController(navController)
    }

    private fun setupClickListeners() {
        binding.fabCheckSensor.setOnClickListener {
            handleButtonClick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
            Log.d("ControllerActivity", "Service unbound")
        } catch (e: IllegalArgumentException) {
            Log.w("ControllerActivity", "Service was not bound")
        }
    }
}