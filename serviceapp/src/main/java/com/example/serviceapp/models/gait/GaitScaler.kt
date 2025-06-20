package com.example.serviceapp.models.gait

class GaitScaler {
    // These are the mean values provided by you.
    // Ensure they are of type FloatArray and match the order of your features.
    private val meanValues = floatArrayOf(
        0.961388989f, 0.351790622f, 0.250381498f, 1.84334881f,
        0.929932320f, 0.739899462f, 1.16000384f, 1.59296731f,
        241.809138f, -0.0570978341f, 0.314040867f, -0.749269645f,
        0.630901328f, -0.0496362781f, -0.247922845f, 0.133479334f,
        1.38017097f, 80.6571922f, -0.0124869725f, 0.303575609f,
        -0.649208054f, 0.735084067f, -0.0363844104f, -0.192309630f,
        0.152790227f, 1.38429212f, 69.3995101f, 1.12438075f,
        0.386549984f, 0.551258162f, 2.22400687f
    )

    // These are the scale values provided by you.
    // Ensure they are of type FloatArray and match the order of your features.
    private val scaleValues = floatArrayOf(
        0.17812747f, 0.15900624f, 0.40698387f, 0.54706229f,
        0.19192176f, 0.21250184f, 0.20663844f, 0.76227085f,
        42.88031641f, 0.27627172f, 0.161276f, 0.61449434f,
        0.50336345f, 0.25492259f, 0.30325322f, 0.27818447f,
        0.7460915f, 37.5464384f, 0.20783875f, 0.17162818f,
        0.47295466f, 0.62677882f, 0.18530377f, 0.19256672f,
        0.24762241f, 0.82021483f, 32.94604636f, 0.16631247f,
        0.18141144f, 0.20240686f, 0.74069331f
    )

    /**
     * Applies standard scaling (z-score normalization) to an array of input features.
     *
     * The formula used is: scaled_value = (original_value - mean) / standard_deviation
     *
     * @param inputFeatures A [FloatArray] representing the features to be scaled.
     * The size of this array must match the number of features
     * the scaler was trained on (which corresponds to the size
     * of `meanValues` and `scaleValues`).
     * @return A new [FloatArray] containing the scaled features.
     * @throws IllegalArgumentException if the size of `inputFeatures` does not match
     * the expected number of features.
     */
    fun applyStandardScaling(inputFeatures: FloatArray): FloatArray {
        // Ensure the input array has the correct number of features
        if (inputFeatures.size != meanValues.size || inputFeatures.size != scaleValues.size) {
            throw IllegalArgumentException(
                "Input features size (${inputFeatures.size}) must match scaler parameters size " +
                        "(${meanValues.size}). Check your input data."
            )
        }

        val scaledFeatures = FloatArray(inputFeatures.size)
        for (i in inputFeatures.indices) {
            // Apply the scaling formula: (x - mean) / std_dev
            scaledFeatures[i] = (inputFeatures[i] - meanValues[i]) / scaleValues[i]
        }
        return scaledFeatures
    }
}