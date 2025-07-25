package com.example.serviceapp.ui.verifications.utils

import android.content.Context
import android.content.SharedPreferences

class IndexManager(private val context: Context) {
    private val recordingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    private companion object {
        private const val FACE_INDEX_KEY = "image_index"
        private const val AUDIO_INDEX_KEY = "audio_index"
        private const val SENSOR_INDEX_KEY = "sensor_index"
    }

    val faceIndex: Int
        get() = recordingPrefs.getInt(FACE_INDEX_KEY, 0) - 1

    val audioIndex: Int
        get() = recordingPrefs.getInt(AUDIO_INDEX_KEY, 0) - 1

    val gaitIndex: Int
        get() = recordingPrefs.getInt(SENSOR_INDEX_KEY, 0) - 1
}
