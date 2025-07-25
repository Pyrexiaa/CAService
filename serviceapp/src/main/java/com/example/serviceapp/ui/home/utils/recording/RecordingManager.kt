package com.example.serviceapp.ui.home.utils.recording

import androidx.lifecycle.LifecycleCoroutineScope
import com.example.serviceapp.ui.home.utils.UIHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordingManager(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val uiHelper: UIHelper
) {
    private var recordingStartTime: Long = 0L
    private var recordingUpdateJob: Job? = null
    private var gaitStartTime: Long = 0L
    private var gaitUpdateJob: Job? = null

    fun startAudioRecording(onStopEnabled: () -> Unit) {
        recordingStartTime = System.currentTimeMillis()
        uiHelper.showRecordingStatus("Recording: 0.0s", 0)
        uiHelper.disableAllButtons()

        recordingUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val progress = elapsed.coerceAtMost(5000).toInt()
                val timeText = "Recording: %.1fs".format(elapsed / 1000f)

                uiHelper.showRecordingStatus(timeText, progress)

                if (elapsed >= 5000) {
                    onStopEnabled()
                }
                delay(100)
            }
        }
    }

    fun stopAudioRecording(): Long {
        val duration = System.currentTimeMillis() - recordingStartTime
        recordingUpdateJob?.cancel()
        uiHelper.hideRecordingStatus()
        uiHelper.enableAllButtons()
        return duration
    }

    fun startGaitRecording(onStopEnabled: () -> Unit) {
        gaitStartTime = System.currentTimeMillis()
        uiHelper.showGaitRecordingStatus("Recording: 0.0s", 0)
        uiHelper.disableAllButtons()

        gaitUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - gaitStartTime
                val progress = elapsed.coerceAtMost(5000).toInt()
                val timeText = "Recording: %.1fs".format(elapsed / 1000f)

                uiHelper.showGaitRecordingStatus(timeText, progress)

                if (elapsed >= 5000) {
                    onStopEnabled()
                }
                delay(100)
            }
        }
    }

    fun stopGaitRecording(): Long {
        val duration = System.currentTimeMillis() - gaitStartTime
        gaitUpdateJob?.cancel()
        uiHelper.hideGaitRecordingStatus()
        uiHelper.enableAllButtons()
        return duration
    }
}