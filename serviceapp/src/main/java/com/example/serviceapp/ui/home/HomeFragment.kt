package com.example.serviceapp.ui.home

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.serviceapp.databinding.FragmentHomeBinding
import com.example.serviceapp.service.handlers.CameraHandler
import com.example.serviceapp.ui.home.utils.PermissionHelper
import com.example.serviceapp.ui.home.utils.RecognizerManager
import com.example.serviceapp.ui.home.utils.UIHelper
import com.example.serviceapp.ui.home.utils.camera.CameraManager
import com.example.serviceapp.ui.home.utils.camera.FaceCaptureManager
import com.example.serviceapp.ui.home.utils.camera.FaceProcessor
import com.example.serviceapp.ui.home.utils.recording.RecordingManager

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var recognizerManager: RecognizerManager
    private lateinit var permissionHelper: PermissionHelper
    private lateinit var cameraManager: CameraManager
    private lateinit var faceProcessor: FaceProcessor
    private lateinit var uiHelper: UIHelper
    private lateinit var recordingManager: RecordingManager
    private lateinit var faceCaptureManager: FaceCaptureManager
    private lateinit var cameraHandler: CameraHandler

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeComponents()
        setupClickListeners()
        requestPermissionsIfNeeded()
    }

    private fun initializeComponents() {
        recognizerManager = RecognizerManager(requireContext()).apply { initialize() }
        permissionHelper = PermissionHelper(this)
        cameraManager = CameraManager(requireContext(), viewLifecycleOwner, binding.previewView)
        faceProcessor = FaceProcessor(requireContext())
        uiHelper = UIHelper(requireActivity(), binding)
        recordingManager = RecordingManager(lifecycleScope, uiHelper)
        faceCaptureManager = FaceCaptureManager(requireContext(), faceProcessor, cameraManager, uiHelper)
        cameraHandler = CameraHandler(requireContext())

        binding.faceDirectionGuide.visibility = View.GONE
    }

    private fun setupClickListeners() {
        binding.btnStartCapture.setOnClickListener {
            binding.faceDirectionGuide.visibility = View.GONE
            binding.btnCapture.isEnabled = true

            faceCaptureManager.startEnrollment(recognizerManager) {
                Log.d("PromptFlow", "All prompts done. onFinished triggered.")
                initializeCameraWithRetry()
            }
        }

        binding.btnFaceEnroll.setOnClickListener {
            faceCaptureManager.startEnrollment(recognizerManager) {
                Toast.makeText(requireContext(), "Face enrollment completed", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFaceVerify.setOnClickListener {
            faceCaptureManager.startVerification(recognizerManager)
        }

        setupAudioClickListeners()
        setupGaitClickListeners()
    }

    private fun setupAudioClickListeners() {
        binding.btnStartRecording.setOnClickListener {
            if (permissionHelper.hasAudioPermission()) {
                try {
                    recordingManager.startAudioRecording {
                        binding.btnStopRecording.isEnabled = true
                        binding.tvRecordingTip.visibility = View.VISIBLE
                    }

                    recognizerManager.getAudioRecognizer().startRecording(requireContext())
                    Toast.makeText(requireContext(), "Recording started. Speak now.", Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Toast.makeText(requireContext(), "Audio recording permission denied or revoked.", Toast.LENGTH_SHORT).show()
                    Log.e("StartRecording", "SecurityException: ${e.message}")
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("StartRecording", "Exception: ${e.message}")
                }
            } else {
                permissionHelper.requestAudioPermission()
            }
        }

        binding.btnStopRecording.setOnClickListener {
            val duration = recordingManager.stopAudioRecording()

            if (duration < 5000) {
                recognizerManager.getAudioRecognizer().cancelRecording()
                Toast.makeText(
                    requireContext(),
                    "Recording too short. Please record at least 5 seconds.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                recognizerManager.getAudioRecognizer().stopAndExtractEmbedding(requireContext()) { embedding ->
                    if (embedding != null) {
                        Log.d("AudioEnrollment", "Embedding extracted successfully")
                    } else {
                        Log.e("AudioEnrollment", "Failed to extract embedding")
                    }
                }
            }
        }

        binding.btnAudioVerify.setOnClickListener {
            try {
                startCountdown("Verifying Audio") {
                    try {
                        recognizerManager.getAudioRecognizer()
                            .recordForVerification(requireContext()) { conf, match ->
                                showResult(conf, match)
                            }
                    } catch (e: SecurityException) {
                        Toast.makeText(requireContext(), "Audio permission denied or revoked.", Toast.LENGTH_SHORT).show()
                        Log.e("AudioVerify", "SecurityException during audio verification: ${e.message}")
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Audio verification failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        Log.e("AudioVerify", "Exception during audio verification: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to start countdown: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("AudioVerify", "Exception starting countdown: ${e.message}")
            }
        }

    }

    private fun setupGaitClickListeners() {
        binding.btnGaitStart.setOnClickListener {
            recordingManager.startGaitRecording {
                binding.btnGaitStop.isEnabled = true
                binding.gaitTvRecordingTip.visibility = View.VISIBLE
            }

            recognizerManager.getGaitRecognizer().startGaitCollection(requireContext())
            Toast.makeText(
                requireContext(),
                "Gait recording started. Please walk naturally.",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnGaitStop.setOnClickListener {
            val duration = recordingManager.stopGaitRecording()

            if (duration < 5000) {
                recognizerManager.getGaitRecognizer().cancelGaitCollection()
                Toast.makeText(
                    requireContext(),
                    "Recording too short. Please walk for at least 5 seconds.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                recognizerManager.getGaitRecognizer().stopAndExtractGaitEmbedding(requireContext()) { embedding ->
                    if (embedding != null) {
                        Log.d("GaitEnrollment", "Gait embedding extracted successfully")
                    } else {
                        Log.e("GaitEnrollment", "Failed to extract gait embedding")
                    }
                }
            }
        }

        binding.btnGaitVerify.setOnClickListener {
            startCountdown("Verifying Gait") {
                recognizerManager.getGaitRecognizer().collectForVerification(requireContext()) { conf, match ->
                    showResult(conf, match)
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (!permissionHelper.hasCameraPermission()) {
            permissionHelper.requestCameraPermission()
        }
    }

    private fun initializeCameraWithRetry() {
        cameraHandler.waitForCameraReady(
            retryIntervalMillis = 5000,
            maxRetries = 5,
            onReady = {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Camera is ready!", Toast.LENGTH_SHORT).show()
                }
            },
            onFailed = {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to initialize camera.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showResult(conf: Float, match: Boolean) {
        Toast.makeText(
            requireContext(),
            "Confidence: $conf, Match: $match",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startCountdown(message: String, onComplete: () -> Unit) {
        var count = 3
        val countdownToast = Toast.makeText(requireContext(), "$message in $count...", Toast.LENGTH_SHORT)
        countdownToast.show()

        handler.postDelayed(object : Runnable {
            override fun run() {
                count--
                if (count > 0) {
                    countdownToast.setText("$message in $count...")
                    handler.postDelayed(this, 1000)
                } else {
                    onComplete()
                }
            }
        }, 1000)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PermissionHelper.REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(requireContext(), "Audio permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Audio permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            PermissionHelper.REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(requireContext(), "Camera permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Camera permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}