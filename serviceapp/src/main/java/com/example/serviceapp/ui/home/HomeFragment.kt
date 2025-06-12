package com.example.serviceapp.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.biometric.BiometricPrompt
import android.os.Build
import android.os.Bundle
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.sqrt
import androidx.core.graphics.scale
import androidx.core.graphics.get
import androidx.core.content.edit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private lateinit var captureImageLauncher: ActivityResultLauncher<Void?>
    private lateinit var captureImageLauncherForVerification: ActivityResultLauncher<Void?>


    override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
    ): View {
        val homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)

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

    private var enrolledFaceEmbedding: FloatArray? = null
    private lateinit var bitmap: Bitmap

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        enrolledFaceEmbedding = loadEmbeddingFromPrefs(requireContext())

        binding.btnFaceEnroll.setOnClickListener {
            captureImageLauncher.launch(null)
        }


        binding.btnFaceVerify.setOnClickListener {
            captureImageLauncherForVerification.launch(null)
        }
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

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val faceDetector = FaceDetection.getClient(detectorOptions)

    private fun processFace(imageBitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(imageBitmap, 0)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val faceBitmap = cropFace(imageBitmap, face.boundingBox)
                    enrolledFaceEmbedding = extractEmbedding(faceBitmap)
                    saveEmbeddingToPrefs(requireContext(), enrolledFaceEmbedding!!)
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
        if (enrolledFaceEmbedding == null) {
            onResult(0f, false)
            return
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val faceBitmap = cropFace(bitmap, face.boundingBox)
                    val currentEmbedding = extractEmbedding(faceBitmap)

                    val similarity = cosineSimilarity(enrolledFaceEmbedding!!, currentEmbedding)
                    val isMatch = similarity > 0.85f  // You can adjust the threshold

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

    // Simplified: Resize and flatten grayscale
    private fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        val resized = faceBitmap.scale(50, 50)
        val grayscale = FloatArray(50 * 50)

        for (y in 0 until 50) {
            for (x in 0 until 50) {
                val pixel = resized[x, y]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (r + g + b) / 3f / 255f  // Normalize
                grayscale[y * 50 + x] = gray
            }
        }
        return grayscale
    }

    private fun cosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        require(vec1.size == vec2.size) { "Vectors must be of the same length" }

        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return (dot / (sqrt(norm1) * sqrt(norm2))).toFloat()
    }

    private fun saveEmbeddingToPrefs(context: Context, embedding: FloatArray) {
        val sharedPref = context.getSharedPreferences("face_prefs", Context.MODE_PRIVATE)
        val str = embedding.joinToString(",")
        sharedPref.edit { putString("embedding", str) }
    }

    private fun loadEmbeddingFromPrefs(context: Context): FloatArray? {
        val sharedPref = context.getSharedPreferences("face_prefs", Context.MODE_PRIVATE)
        val str = sharedPref.getString("embedding", null) ?: return null
        return str.split(",").map { it.toFloat() }.toFloatArray()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}