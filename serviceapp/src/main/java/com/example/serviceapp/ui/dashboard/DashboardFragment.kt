package com.example.serviceapp.ui.dashboard

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.serviceapp.R
import com.example.serviceapp.databinding.FragmentDashboardBinding
import com.example.serviceapp.main_utils.AudioProcessor
import com.example.serviceapp.main_utils.ImageProcessor
import com.example.serviceapp.main_utils.SensorProcessor

class DashboardFragment : Fragment() {

    private lateinit var binding: FragmentDashboardBinding

    private lateinit var audioProcessor: AudioProcessor
    private lateinit var sensorProcessor: SensorProcessor
    private lateinit var imageProcessor: ImageProcessor

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateInterval = 500L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateStatusIndicators()

        audioProcessor = AudioProcessor(requireContext(), binding.audioChart)
        sensorProcessor = SensorProcessor(requireContext())
        imageProcessor = ImageProcessor(requireContext(), binding.imageView, viewLifecycleOwner.lifecycleScope)

        startAutoUpdate()
    }



    private fun updateStatusIndicators() {
        val context = requireContext()

        val cameraAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val micAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val hasMagnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null

        setIndicator(binding.cameraIndicator, cameraAvailable)
        setIndicator(binding.micIndicator, micAvailable)
        setIndicator(binding.accelIndicator, hasAccelerometer)
        setIndicator(binding.gyroIndicator, hasGyroscope)
        setIndicator(binding.magnetIndicator, hasMagnetometer)
    }


    private fun setIndicator(imageView: ImageView, isActive: Boolean) {
        imageView.setImageResource(
            if (isActive) R.drawable.ic_circle_green_16dp else R.drawable.ic_circle_red_16dp
        )
    }


    private fun startAutoUpdate() {
        updateHandler.post(object : Runnable {
            override fun run() {
                try {
                    imageProcessor.startImageLoop()
                    audioProcessor.updateAudioSequence()
                    updateSensor()
                } catch (e: Exception) {
                    Log.e("DashboardFragment", "Error in update loop", e)
                }
                updateHandler.postDelayed(this, updateInterval)
            }
        })
    }

    private fun updateSensor() {
        try {
            sensorProcessor.updateSensor(
                binding.sensorChartAccel,
                binding.sensorChartGyro,
                binding.sensorChartMag
            )
        } catch (e: Exception) {
            Log.e("SensorUpdate", "Failed to update sensor data", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateHandler.removeCallbacksAndMessages(null)
    }
}
