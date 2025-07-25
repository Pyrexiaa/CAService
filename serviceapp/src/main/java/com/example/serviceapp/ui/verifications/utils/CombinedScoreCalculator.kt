package com.example.serviceapp.ui.verifications.utils

class CombinedScoreCalculator {
    data class Weights(
        val face: Float = 0.5f,
        val audio: Float = 0.3f,
        val gait: Float = 0.2f
    )

    fun calculateWeightedScore(
        faceScore: Float?,
        audioScore: Float?,
        gaitScore: Float?,
        weights: Weights = Weights()
    ): Float? {
        var weightedSum = 0f
        var totalWeight = 0f

        faceScore?.let {
            if (it in 0f..1f) {
                weightedSum += it * weights.face
                totalWeight += weights.face
            }
        }

        audioScore?.let {
            if (it in 0f..1f) {
                weightedSum += it * weights.audio
                totalWeight += weights.audio
            }
        }

        gaitScore?.let {
            if (it in 0f..1f) {
                weightedSum += it * weights.gait
                totalWeight += weights.gait
            }
        }

        return if (totalWeight > 0f) weightedSum / totalWeight else null
    }
}