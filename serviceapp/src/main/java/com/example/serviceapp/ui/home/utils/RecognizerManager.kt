package com.example.serviceapp.ui.home.utils

import android.content.Context
import com.example.serviceapp.models.audio.AudioRecognizer
import com.example.serviceapp.models.face.FaceRecognizer
import com.example.serviceapp.models.gait.GaitRecognizer

class RecognizerManager(private val context: Context) {
    private lateinit var faceRecognizer: FaceRecognizer
    private lateinit var audioRecognizer: AudioRecognizer
    private lateinit var gaitRecognizer: GaitRecognizer

    fun initialize() {
        faceRecognizer = FaceRecognizer(context, "light_cnn_float16.tflite")
        audioRecognizer = AudioRecognizer(context, "custom_audio_model_float16.tflite", "mfcc_model.tflite")
        gaitRecognizer = GaitRecognizer(context, "custom_gait_model_float16.tflite")
    }

    fun getFaceRecognizer() = faceRecognizer
    fun getAudioRecognizer() = audioRecognizer
    fun getGaitRecognizer() = gaitRecognizer
}