package com.example.serviceapp.ui.home.utils

import android.app.Activity
import android.view.View
import com.example.serviceapp.R
import com.example.serviceapp.databinding.FragmentHomeBinding
import com.google.android.material.bottomnavigation.BottomNavigationView

class UIHelper(private val activity: Activity, private val binding: FragmentHomeBinding) {

    fun showCameraFullscreen() {
        activity.findViewById<BottomNavigationView>(R.id.nav_view)?.visibility = View.GONE
        binding.previewViewContainer.visibility = View.VISIBLE
        binding.btnCapture.visibility = View.VISIBLE
    }

    fun hideCameraFullscreen() {
        activity.findViewById<BottomNavigationView>(R.id.nav_view)?.visibility = View.VISIBLE
        binding.previewViewContainer.visibility = View.GONE
        binding.btnCapture.visibility = View.GONE
    }

    fun showLoadingOverlay() {
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    fun hideLoadingOverlay() {
        binding.loadingOverlay.visibility = View.GONE
    }

    fun setupCaptureButton(text: String, onClickListener: () -> Unit) {
        binding.btnCapture.text = text
        binding.btnCapture.isEnabled = true
        binding.btnCapture.setOnClickListener { onClickListener() }
    }

    fun setCaptureButtonEnabled(enabled: Boolean) {
        binding.btnCapture.isEnabled = enabled
    }

    fun enableAllButtons() {
        binding.btnFaceEnroll.isEnabled = true
        binding.btnFaceVerify.isEnabled = true
        binding.btnStartRecording.isEnabled = true
        binding.btnAudioVerify.isEnabled = true
        binding.btnGaitStart.isEnabled = true
        binding.btnGaitVerify.isEnabled = true
    }

    fun disableAllButtons() {
        binding.btnFaceEnroll.isEnabled = false
        binding.btnFaceVerify.isEnabled = false
        binding.btnStartRecording.isEnabled = false
        binding.btnStopRecording.isEnabled = false
        binding.btnAudioVerify.isEnabled = false
        binding.btnGaitStart.isEnabled = false
        binding.btnGaitVerify.isEnabled = false
    }

    fun showRecordingStatus(time: String, progress: Int) {
        binding.audioRecordingStatusLayout.visibility = View.VISIBLE
        binding.tvRecordingTip.visibility = View.GONE
        binding.progressRecording.progress = progress
        binding.tvRecordingTime.text = time
    }

    fun hideRecordingStatus() {
        binding.audioRecordingStatusLayout.visibility = View.GONE
    }

    fun showGaitRecordingStatus(time: String, progress: Int) {
        binding.gaitRecordingStatusLayout.visibility = View.VISIBLE
        binding.gaitTvRecordingTip.visibility = View.GONE
        binding.gaitProgressRecording.progress = progress
        binding.gaitTvRecordingTime.text = time
    }

    fun hideGaitRecordingStatus() {
        binding.gaitRecordingStatusLayout.visibility = View.GONE
    }
}