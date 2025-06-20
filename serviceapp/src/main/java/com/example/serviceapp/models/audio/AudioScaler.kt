package com.example.serviceapp.models.audio

class AudioScaler {
    // These are the mean values provided by you.
    // Ensure they are of type FloatArray and match the order of your features.
    private val meanValues = floatArrayOf(
        -331.041484f, 130.812582f, -26.8863852f, 36.4499501f,
        -17.7047759f, 10.3406056f, -20.8721076f, 0.704806446f,
        -10.4609440f, -2.09978170f, -3.15053126f, -5.84105036f,
        0.677585790f, 83.4342815f, 44.7307421f, 30.5274477f,
        26.0822672f, 21.7919746f, 18.9647275f, 16.7349257f,
        13.5308775f, 12.2614681f, 10.7921730f, 9.90277259f,
        9.10128794f, 8.74317364f, -0.624623018f, -0.748021263f,
        -0.190057784f, 0.236610678f, -0.398914147f, 0.345736320f,
        -0.160101216f, 0.0354209773f, -0.138736308f, -0.104558623f,
        -0.0786096156f, 0.0336507468f, 0.0154477870f, 0.238692329f,
        0.179934583f, -0.425912760f, -0.165290939f, -0.196385557f,
        0.294912938f, -0.373429611f, 0.0255186744f, -0.206362601f,
        -0.0864807865f, 0.0480190150f, 0.0369984274f, 0.0132854706f
    )

    // These are the scale values provided by you.
    // Ensure they are of type FloatArray and match the order of your features.
    private val scaleValues = floatArrayOf(
        80.19196685f, 20.33006951f, 20.7290541f, 14.22961267f,
        12.57240557f, 11.67299168f, 8.84151627f, 8.2374913f,
        7.18348762f, 5.79028129f, 5.12973151f, 5.19645687f,
        5.00452991f, 31.84730774f, 11.67940353f, 6.65286164f,
        4.6299994f, 4.26249362f, 3.69922764f, 3.03738347f,
        2.42626523f, 2.28747155f, 1.81384389f, 1.97520145f,
        1.58474337f, 1.58659931f, 0.6292929f, 0.40709275f,
        0.33729855f, 0.37492294f, 0.34825314f, 0.45227227f,
        0.33093087f, 0.37891389f, 0.33107027f, 0.33502502f,
        0.37396118f, 0.37121368f, 0.35102484f, 1.91782249f,
        1.17662724f, 0.49680773f, 0.57780335f, 0.64341729f,
        0.7785988f, 0.49411617f, 0.61433317f, 0.535211f,
        0.52680154f, 0.60256603f, 0.60033811f, 0.60051133f
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