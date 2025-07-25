package com.example.serviceapp.service.ipc

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.Message
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresPermission
import com.example.serviceapp.service.SmartService
import com.example.serviceapp.service.handlers.HandlerManager
import com.example.serviceapp.service.recordings.RecordingManager
import com.example.serviceapp.ui.verifications.VerificationsFragment

// Handles inter-process communication between controller and service app
class IpcManager(
    private val context: Context,
    private val handlerManager: HandlerManager,
    private val recordingSessionManager: RecordingManager,
    private val onStatusUpdate: (String) -> Unit
) {
    companion object {
        private const val TAG = "IPCMessageHandler"
    }

    private var sensorScore = 0

    fun incrementSensorScore() {
        sensorScore++
    }

    fun handleSensorRequest(msg: Message) {
        try {
            val response = Message.obtain(null, SmartService.MSG_SENSOR_RESPONSE).apply {
                data = Bundle().apply {
                    putString("sensor_status", "Opened")
                    putInt("sensor_score", sensorScore)
                }
            }
            msg.replyTo?.send(response)
            onStatusUpdate("Replied with score: $sensorScore")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to send sensor response", e)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun handleStartRecording() {
        recordingSessionManager.startRecordingSession(onStatusUpdate)
        onStatusUpdate("Started recording session")
    }

    fun handleVerificationScoresRequest(msg: Message) {
        Log.d(TAG, "Received verification scores request")

        val scores = getVerificationScores()
        val response = Message.obtain(null, SmartService.MSG_VERIFICATION_SCORES_RESPONSE).apply {
            data = createScoresBundle(scores)
        }

        try {
            msg.replyTo?.send(response)
            Log.d(TAG, "Verification scores sent: $scores")
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to send verification scores", e)
        }

        onStatusUpdate("Gotten the latest confidence score")
    }

    private fun getVerificationScores(): Map<String, Float> {
        return VerificationsFragment.getInstance()?.getCurrentVerificationScores()
            ?: getDefaultScores()
    }

    private fun getDefaultScores(): Map<String, Float> {
        return mapOf(
            "face_score" to 0f,
            "audio_score" to 0f,
            "gait_score" to 0f,
            "combined_score" to 0f
        )
    }

    private fun createScoresBundle(scores: Map<String, Float>): Bundle {
        return Bundle().apply {
            putFloat("face_score", scores["face_score"] ?: 0f)
            putFloat("audio_score", scores["audio_score"] ?: 0f)
            putFloat("gait_score", scores["gait_score"] ?: 0f)
            putFloat("combined_score", scores["combined_score"] ?: 0f)
        }
    }
}