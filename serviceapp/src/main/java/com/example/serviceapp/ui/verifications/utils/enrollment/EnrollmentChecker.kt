package com.example.serviceapp.ui.verifications.utils.enrollment

import android.content.Context
import android.content.SharedPreferences

class EnrollmentChecker(private val context: Context) {

    fun getEnrollmentStatus(): EnrollmentStatus {
        val facePrefs = context.getSharedPreferences("FacePrefs", Context.MODE_PRIVATE)
        val audioPrefs = context.getSharedPreferences("AudioPrefs", Context.MODE_PRIVATE)
        val gaitPrefs = context.getSharedPreferences("GaitPrefs", Context.MODE_PRIVATE)

        return EnrollmentStatus(
            isFaceEnrolled = getBooleanFromStringPrefs(facePrefs, "embedding"),
            isAudioEnrolled = getBooleanFromStringPrefs(audioPrefs, "embedding"),
            isGaitEnrolled = getBooleanFromStringPrefs(gaitPrefs, "embedding")
        )
    }

    private fun getBooleanFromStringPrefs(prefs: SharedPreferences, key: String): Boolean {
        return prefs.contains(key)
    }
}