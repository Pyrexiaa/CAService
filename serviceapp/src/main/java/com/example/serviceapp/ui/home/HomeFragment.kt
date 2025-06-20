package com.example.serviceapp.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.biometric.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.serviceapp.databinding.FragmentHomeBinding
import com.example.serviceapp.main_utils.AudioProcessor
import com.example.serviceapp.models.audio.AudioRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.serviceapp.models.face.FaceRecognizer
import com.example.serviceapp.models.gait.GaitRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var captureImageLauncher: ActivityResultLauncher<Void?>
    private lateinit var captureImageLauncherForVerification: ActivityResultLauncher<Void?>

    private lateinit var bitmap: Bitmap
    private lateinit var faceRecognizer: FaceRecognizer
    private lateinit var audioRecognizer: AudioRecognizer
    private lateinit var gaitRecognizer: GaitRecognizer

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this)[HomeViewModel::class.java]

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Register the launcher BEFORE fragment is created
        captureImageLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { imageBitmap ->
            if (imageBitmap != null) {
                bitmap = imageBitmap
                processFace(bitmap)
            } else {
                Toast.makeText(requireContext(), "Failed to capture image", Toast.LENGTH_SHORT).show()
            }
        }

        captureImageLauncherForVerification = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { imageBitmap ->
            if (imageBitmap != null) {
                verifyFace(imageBitmap) { confidence, isMatch ->
                    Toast.makeText(requireContext(), "Confidence: $confidence, Match: $isMatch", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Failed to capture image", Toast.LENGTH_SHORT).show()
            }
        }

        return root
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private val REQUEST_RECORD_AUDIO_PERMISSION = 200

    private fun requestAudioPermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(requireContext(), "Audio permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Audio permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        faceRecognizer = FaceRecognizer(requireContext(), "custom_face_last_linear_layer_float16.tflite")
        audioRecognizer = AudioRecognizer(requireContext(), "custom_audio_model_float16.tflite")
        gaitRecognizer = GaitRecognizer(requireContext(), "custom_gait_model_float16.tflite")

        // Face buttons
        binding.btnFaceEnroll.setOnClickListener { captureImageLauncher.launch(null) }
        binding.btnFaceVerify.setOnClickListener { captureImageLauncherForVerification.launch(null) }

        // Audio buttons
        binding.btnAudioEnroll.setOnClickListener {
            if (hasAudioPermission()) {
                startCountdown("Enrolling Audio") {
                    @Suppress("MissingPermission")
                    audioRecognizer.recordForEnrollment(requireContext()) { embedding ->
                        // This is the onSuccess callback
                        if (embedding != null) {
                            // Use the embedding (e.g., store it, compare it)
                            Log.d("AudioEnrollment", "Embedding extracted successfully: ${embedding.contentToString()}")
                        } else {
                            // Handle the case where the embedding is null (e.g., recording failed)
                            Log.e("AudioEnrollment", "Embedding extraction failed.")
                        }
                    }
                }
            } else {
                requestAudioPermission()
            }
        }
        @Suppress("MissingPermission")
        binding.btnAudioVerify.setOnClickListener {
            startCountdown("Verifying Audio") {
                audioRecognizer.recordForVerification(requireContext()) {
                    conf: Float, match: Boolean -> showResult(conf, match)
                }
            }
        }

        // Gait buttons
        binding.btnGaitEnroll.setOnClickListener {
            startCountdown("Enrolling Gait") {
                gaitRecognizer.collectForEnrollment(requireContext())
            }
        }
        binding.btnGaitVerify.setOnClickListener {
            startCountdown("Verifying Gait") {
                gaitRecognizer.collectForVerification(requireContext()) {
                     conf, match -> showResult(conf, match)
                }
            }
        }
    }

    private fun showResult(conf: Float, match: Boolean) {
        Toast.makeText(requireContext(), "Confidence: $conf, Match: $match", Toast.LENGTH_SHORT).show()
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


    @RequiresApi(Build.VERSION_CODES.P)
    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(requireContext())

        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Toast.makeText(requireContext(), "Authentication succeeded", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(requireContext(), "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(requireContext(), "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify with Face or Fingerprint")
            .setSubtitle("Use biometric authentication")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // Facial Detection and Recognition

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    // Use face recognition model, not face detection
    private val faceDetector = FaceDetection.getClient(detectorOptions)

    private fun processFace(imageBitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(imageBitmap, 0)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val faceBitmap = cropFace(imageBitmap, face.boundingBox)
                    // Extract and enroll the face embedding using FaceRecognizer
                    faceRecognizer.enrollEmbedding(faceBitmap)

                    Toast.makeText(requireContext(), "Face enrolled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "No face detected", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Detection failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }



    private fun verifyFace(bitmap: Bitmap, onResult: (confidence: Float, isMatch: Boolean) -> Unit) {

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val faceBitmap = cropFace(bitmap, face.boundingBox)
                    val similarity = faceRecognizer.verifyFace(faceBitmap)
                    val isMatch = similarity > 0.50f  // You can adjust the threshold
                    onResult(similarity, isMatch)
                } else {
                    onResult(0f, false)
                }
            }
            .addOnFailureListener {
                onResult(0f, false)
            }
    }

    private fun cropFace(original: Bitmap, box: android.graphics.Rect): Bitmap {
        val safeBox = android.graphics.Rect(
            box.left.coerceAtLeast(0),
            box.top.coerceAtLeast(0),
            box.right.coerceAtMost(original.width),
            box.bottom.coerceAtMost(original.height)
        )
        return Bitmap.createBitmap(original, safeBox.left, safeBox.top, safeBox.width(), safeBox.height())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}