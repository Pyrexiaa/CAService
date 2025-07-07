package com.example.serviceapp.ui.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.serviceapp.R
import com.example.serviceapp.ServiceActivity
import com.example.serviceapp.databinding.FragmentHomeBinding
import com.example.serviceapp.models.audio.AudioRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.example.serviceapp.models.face.FaceRecognizer
import com.example.serviceapp.models.gait.GaitRecognizer
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File
import java.io.FileOutputStream

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var captureImageLauncherForVerification: ActivityResultLauncher<Intent>

    private val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra("android.intent.extras.CAMERA_FACING", Camera.CameraInfo.CAMERA_FACING_FRONT) // Works on some
        putExtra("android.intent.extras.LENS_FACING_FRONT", 1) // Works on some
        putExtra("android.intent.extra.USE_FRONT_CAMERA", true) // Works on newer devices
    }

    // Camera X
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraProvider: ProcessCameraProvider
    private var cameraBound = false
    private lateinit var outputDirectory: File
    private val faceCapturePrompts = listOf("Center", "Left", "Right", "Up", "Down")
    private var currentPromptIndex = 0
    private val faceCaptures = mutableListOf<Pair<Bitmap, String>>()

    private lateinit var faceRecognizer: FaceRecognizer
    private lateinit var audioRecognizer: AudioRecognizer
    private lateinit var gaitRecognizer: GaitRecognizer

    private val handler = Handler(Looper.getMainLooper())

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
        outputDirectory = requireContext().getExternalFilesDir("face_captures")!!

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.CAMERA), 123)
        }

        return root
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(requireActivity().windowManager.defaultDisplay.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageCapture)
                cameraBound = true
            } catch (e: Exception) {
                Log.e("CameraX", "Failed to bind camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun showCameraFullscreen() {
        // Hide app's bottom nav
        (requireActivity() as ServiceActivity).findViewById<BottomNavigationView>(R.id.nav_view)?.visibility = View.GONE

        // Show your preview UI
        binding.previewView.visibility = View.VISIBLE
        binding.btnCapture.visibility = View.VISIBLE

    }

    private fun hideCameraFullscreen() {
        // Show app's bottom nav again
        (requireActivity() as ServiceActivity).findViewById<BottomNavigationView>(R.id.nav_view)?.visibility = View.VISIBLE

        // Hide preview UI
        binding.previewView.visibility = View.GONE
        binding.btnCapture.visibility = View.GONE

    }

    private fun capturePhoto() {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()

                    if (bitmap == null) {
                        Toast.makeText(requireContext(), "Failed to convert image", Toast.LENGTH_SHORT).show()
                        binding.btnCapture.isEnabled = true
                        return
                    }

                    val orientation = faceCapturePrompts[currentPromptIndex]

                    processFace(bitmap, orientation) {
                        faceCaptures.add(Pair(bitmap, orientation))

                        hideCameraFullscreen()
                        currentPromptIndex++
                        binding.btnCapture.isEnabled = true

                        // Hide loading and show next prompt
                        binding.loadingOverlay.visibility = View.GONE
                        promptAndCaptureNext()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("FaceCapture", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
                    binding.btnCapture.isEnabled = true
                }
            }
        )
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

    private var recordingStartTime: Long = 0L
    private var gaitStartTime: Long = 0L

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        faceRecognizer = FaceRecognizer(requireContext(), "light_cnn_float16.tflite")
        audioRecognizer = AudioRecognizer(requireContext(), "custom_audio_model_float16.tflite", "mfcc_model.tflite")
        gaitRecognizer = GaitRecognizer(requireContext(), "custom_gait_model_float16.tflite")

        binding.faceDirectionGuide.visibility = View.GONE
        binding.btnStartCapture.setOnClickListener {
            binding.faceDirectionGuide.visibility = View.GONE
            binding.btnCapture.isEnabled = true
            promptAndCaptureNext()
        }

        // Face buttons
        binding.btnFaceEnroll.setOnClickListener {
            startFaceEnrollmentCapture()
        }
        binding.btnFaceVerify.setOnClickListener {
            startFaceVerificationCapture()
        }

        // Audio buttons
        binding.btnStartRecording.setOnClickListener {
            if (hasAudioPermission()) {
                recordingStartTime = System.currentTimeMillis()

                @Suppress("MissingPermission")
                audioRecognizer.startRecording(requireContext())
                Toast.makeText(requireContext(), "Recording started. Speak now.", Toast.LENGTH_SHORT).show()
            } else {
                requestAudioPermission()
            }
        }

        binding.btnStopRecording.setOnClickListener {
            val duration = System.currentTimeMillis() - recordingStartTime
            val minDurationMillis = 5000  // 5 seconds

            if (duration < minDurationMillis) {
                audioRecognizer.cancelRecording()
                Toast.makeText(requireContext(), "Recording too short. Please record at least 5 seconds.", Toast.LENGTH_SHORT).show()
            } else {
                audioRecognizer.stopAndExtractEmbedding(requireContext()) { embedding ->
                    if (embedding != null) {
                        Log.d("AudioEnrollment", "Embedding extracted successfully: ${embedding.contentToString()}")
                        // Save or process embedding
                    } else {
                        Log.e("AudioEnrollment", "Failed to extract embedding.")
                    }
                }
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

        binding.btnGaitStart.setOnClickListener {
            gaitStartTime = System.currentTimeMillis()
            gaitRecognizer.startGaitCollection(requireContext())
            Toast.makeText(requireContext(), "Gait recording started.", Toast.LENGTH_SHORT).show()
        }

        binding.btnGaitStop.setOnClickListener {
            val duration = System.currentTimeMillis() - gaitStartTime
            if (duration < 5000) {
                gaitRecognizer.cancelGaitCollection()
                Toast.makeText(requireContext(), "Recording too short. Please walk for at least 5 seconds.", Toast.LENGTH_SHORT).show()
            } else {
                gaitRecognizer.stopAndExtractGaitEmbedding(requireContext()) { embedding ->
                    if (embedding != null) {
                        Log.d("GaitEnrollment", "Gait embedding extracted: ${embedding.contentToString()}")
                    } else {
                        Log.e("GaitEnrollment", "Failed to extract gait embedding.")
                    }
                }
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

    // Facial Detection and Recognition

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    // Use face recognition model, not face detection
    private val faceDetector = FaceDetection.getClient(detectorOptions)

    private fun rotationDegrees(rotation: Int): Int {
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun processFace(imageBitmap: Bitmap, orientation: String, onComplete: () -> Unit) {
        // Rotate the bitmap 90 degrees anticlockwise
        val rotatedBitmap = rotateBitmap90AntiClockwise(imageBitmap)

//        // Save bitmap for debugging
//        saveBitmapForDebugging(rotatedBitmap, orientation)

        val rotation = rotationDegrees(requireActivity().windowManager.defaultDisplay.rotation)
        val inputImage = InputImage.fromBitmap(rotatedBitmap, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val faceBitmap = cropFace(imageBitmap, face.boundingBox)
                    faceRecognizer.enrollEmbedding(faceBitmap, orientation)

                    Toast.makeText(requireContext(), "Face enrolled: $orientation", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "No face detected ($orientation)", Toast.LENGTH_SHORT).show()
                }
                Log.d("ProcessFace", "Processing face for orientation: $orientation")
                onComplete()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Detection failed ($orientation): ${e.message}", Toast.LENGTH_SHORT).show()
                onComplete()
            }
    }

    private fun rotateBitmap90AntiClockwise(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            postRotate(-90f)  // Negative 90 degrees for anticlockwise rotation
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveBitmapForDebugging(bitmap: Bitmap, orientation: String) {
        try {
            val debugDir = File(requireContext().filesDir, "debug_faces")
            if (!debugDir.exists()) debugDir.mkdirs()
            val debugFile = File(debugDir, "face_debug_${orientation}_${System.currentTimeMillis()}.png")
            FileOutputStream(debugFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            Log.d("DebugSave", "Saved debug bitmap: ${debugFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("DebugSave", "Failed to save debug bitmap: ${e.message}", e)
        }
    }

    private fun verifyFace(bitmap: Bitmap, onResult: (confidence: Float, isMatch: Boolean) -> Unit) {
        // Rotate the bitmap 90 degrees anticlockwise
        val rotatedBitmap = rotateBitmap90AntiClockwise(bitmap)

//        // Save bitmap for debugging
//        saveBitmapForDebugging(rotatedBitmap, "verify")

        val rotation = rotationDegrees(requireActivity().windowManager.defaultDisplay.rotation)
        val inputImage = InputImage.fromBitmap(rotatedBitmap, rotation)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val faceBitmap = cropFace(bitmap, face.boundingBox)
                    val similarity = faceRecognizer.verifyFace(faceBitmap)
                    val isMatch = similarity > 0.7548f  // Face threshold
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

    private fun startFaceVerificationCapture() {
        // Show camera and capture UI
        binding.faceDirectionGuide.visibility = View.GONE // optional
        showCameraFullscreen()

        if (!cameraBound) startCameraPreview()

        binding.previewView.visibility = View.VISIBLE
        binding.btnCapture.visibility = View.VISIBLE
        binding.btnCapture.text = "Verify"
        binding.btnCapture.isEnabled = true

        binding.btnCapture.setOnClickListener {
            binding.btnCapture.isEnabled = false
            // Show loading while processing
            binding.loadingOverlay.visibility = View.VISIBLE
            captureVerificationPhoto()
        }
    }

    private fun captureVerificationPhoto() {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val bitmap = imageProxy.toBitmap()
                    imageProxy.close()

                    if (bitmap == null) {
                        Toast.makeText(requireContext(), "Failed to convert image", Toast.LENGTH_SHORT).show()
                        return
                    }

                    val previewDir = File(requireContext().filesDir, "preview").apply { mkdirs() }
                    val previewImageFile = File(previewDir, "preview_bitmap.png")
                    FileOutputStream(previewImageFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                    }

                    Log.d("Verification", "Saved image to: ${previewImageFile.absolutePath}")

                    verifyFace(bitmap) { confidence, isMatch ->
                        Toast.makeText(requireContext(), "Confidence: $confidence, Match: $isMatch", Toast.LENGTH_SHORT).show()
                    }
                    binding.loadingOverlay.visibility = View.GONE

                    binding.previewView.visibility = View.GONE
                    binding.btnCapture.visibility = View.GONE
                    hideCameraFullscreen()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("Verification", "Photo capture failed: ${exception.message}", exception)
                    Toast.makeText(requireContext(), "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun startFaceEnrollmentCapture() {
        currentPromptIndex = 0
        faceCaptures.clear()
        binding.faceDirectionGuide.visibility = View.VISIBLE
    }

    private fun promptAndCaptureNext() {
        if (currentPromptIndex < faceCapturePrompts.size) {
            val prompt = faceCapturePrompts[currentPromptIndex]
            AlertDialog.Builder(requireContext())
                .setTitle("Face Capture")
                .setMessage("Please face $prompt and press OK to continue.")
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    // Show UI
                    showCameraFullscreen()

                    if (!cameraBound) startCameraPreview()

                    // Set capture button listener
                    binding.btnCapture.setOnClickListener {
                        // Show loading while processing
                        binding.loadingOverlay.visibility = View.VISIBLE
                        binding.btnCapture.isEnabled = false
                        capturePhoto()
                    }
                }
                .show()
        } else {
            Toast.makeText(requireContext(), "All face captures done!", Toast.LENGTH_SHORT).show()
            faceRecognizer.averageEmbedding()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}