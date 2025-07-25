package com.example.serviceapp.ui.verifications.utils

import android.content.Context
import com.example.serviceapp.models.audio.AudioRecognizer
import com.example.serviceapp.models.face.FaceRecognizer
import com.example.serviceapp.models.gait.GaitRecognizer
import com.example.serviceapp.ui.verifications.utils.verification.audio.AudioVerificationProcessor
import com.example.serviceapp.ui.verifications.utils.verification.face.FaceVerificationProcessor
import com.example.serviceapp.ui.verifications.utils.verification.gait.GaitVerificationProcessor

class VerificationManager(private val context: Context) {
    private val faceRecognizer = FaceRecognizer(context, "light_cnn_float16.tflite")
    private val audioRecognizer = AudioRecognizer(context, "custom_audio_model_float16.tflite", "mfcc_model.tflite")
    private val gaitRecognizer = GaitRecognizer(context, "custom_gait_model_float16.tflite")

    fun getFaceRecognizer() = faceRecognizer
    fun getAudioRecognizer() = audioRecognizer
    fun getGaitRecognizer() = gaitRecognizer

    fun getCurrentScores(): Map<String, Float> {
        return mapOf(
            "face_score" to faceVerifier.getCurrentScore(),
            "audio_score" to audioVerifier.getCurrentScore(),
            "gait_score" to gaitVerifier.getCurrentScore()
        )
    }

    private lateinit var faceVerifier: FaceVerificationProcessor
    private lateinit var audioVerifier: AudioVerificationProcessor
    private lateinit var gaitVerifier: GaitVerificationProcessor

    fun initializeVerifiers(
        faceVerifier: FaceVerificationProcessor,
        audioVerifier: AudioVerificationProcessor,
        gaitVerifier: GaitVerificationProcessor
    ) {
        this.faceVerifier = faceVerifier
        this.audioVerifier = audioVerifier
        this.gaitVerifier = gaitVerifier
    }
}