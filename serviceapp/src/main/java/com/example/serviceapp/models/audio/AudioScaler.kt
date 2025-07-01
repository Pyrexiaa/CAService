package com.example.serviceapp.models.audio

class AudioScaler {
    // Ensure they are of type FloatArray and match the order of your features.
    private val meanValues = floatArrayOf(
        -3.410451f, 2.6187403f, 0.603894f, 0.2356823f, -0.7913762f, -0.36315453f,
        -0.11075303f, 0.14448634f, 0.03100868f, 0.057084225f, 0.17582405f, -0.014881774f,
        -0.011237533f, 19.66925f, 3.1213944f, 2.5540123f, 1.7533385f, 1.5120116f,
        1.2345783f, 1.1001695f, 1.0217867f, 0.9163089f, 0.8712544f, 0.84041715f,
        0.7991825f, 0.8005849f, -0.9299938f, -0.17756519f, -0.11916505f, -0.49573f,
        -0.253846f, -0.1526157f, -0.22198378f, 0.035987366f, 0.03154215f, -0.053444088f,
        -0.08736836f, -0.10988175f, -0.16267541f, 2.21634f, -0.12712751f, -0.1539201f,
        0.5613318f, -0.07951672f, 0.09006522f, 0.080918744f, 0.08101347f, 0.19164771f,
        0.1641026f, 0.18776709f, 0.2818324f, 0.3368712f
    )

    private val scaleValues = floatArrayOf(
        12.609692f, 1.908339f, 1.1204399f, 0.843499f, 0.65446115f, 0.5899842f,
        0.50716144f, 0.4797772f, 0.39318824f, 0.46007726f, 0.39975387f, 0.36736196f,
        0.36221078f, 17.72857f, 0.6524363f, 0.4776588f, 0.39368793f, 0.2732377f,
        0.20335358f, 0.1752057f, 0.1763391f, 0.14970729f, 0.14085384f, 0.15233217f,
        0.16227412f, 0.19491564f, 1.3512567f, 0.3677438f, 0.3462874f, 0.46831727f,
        0.3426959f, 0.37748292f, 0.33356863f, 0.35708332f, 0.39225954f, 0.36626387f,
        0.37103343f, 0.39834672f, 0.4216146f, 9.558881f, 0.6726317f, 0.54479253f,
        1.0318491f, 0.5753562f, 0.66504216f, 0.5817371f, 0.5841993f, 0.6276672f,
        0.6379768f, 0.70306927f, 0.77516055f, 0.87987965f
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