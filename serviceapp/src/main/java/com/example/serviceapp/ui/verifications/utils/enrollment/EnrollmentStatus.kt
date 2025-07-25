package com.example.serviceapp.ui.verifications.utils.enrollment

data class EnrollmentStatus(
    val isFaceEnrolled: Boolean,
    val isAudioEnrolled: Boolean,
    val isGaitEnrolled: Boolean
) {
    val allEnrolled: Boolean get() = isFaceEnrolled && isAudioEnrolled && isGaitEnrolled
}