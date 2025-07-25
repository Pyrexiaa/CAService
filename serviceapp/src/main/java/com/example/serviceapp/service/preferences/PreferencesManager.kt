package com.example.serviceapp.service.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

// Handles SharedPreferences operations
// Standardize all SharedPreferences settings and naming here
class PreferencesManager(context: Context) {
    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFS_NAME = "RecordingPrefs"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentAudioIndex: Int
        get() = getPreferenceIndex("audio_index")
        set(value) = setPreferenceIndex("audio_index", value)

    var currentSensorIndex: Int
        get() = getPreferenceIndex("sensor_index")
        set(value) = setPreferenceIndex("sensor_index", value)

    var currentImageIndex: Int
        get() = getPreferenceIndex("image_index")
        set(value) = setPreferenceIndex("image_index", value)

    private fun getPreferenceIndex(key: String): Int {
        val value = prefs.getInt(key, 0)
        Log.d(TAG, "Getting $key: $value")
        return value
    }

    private fun setPreferenceIndex(key: String, value: Int) {
        Log.d(TAG, "Setting $key to: $value")
        val success = prefs.edit().putInt(key, value).commit()
        if (!success) {
            Log.e(TAG, "Failed to commit $key: $value")
        }
    }
}