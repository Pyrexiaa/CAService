package com.example.serviceapp.main_utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.Log
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.io.IOException

class AudioProcessor(
    private val context: Context,
    private val chart: LineChart
) {
    // We can still keep a window if we want to display a history of FFTs
    private val audioWindow = LinkedList<DoubleArray>()
    // The `maxSeconds` here refers to the number of 1-second audio files to keep in the window.
    private val maxSeconds = 10 // Display a window of the last 10 seconds of audio FFT

    // --- START: MODIFIED PREFERENCE LOGIC ---
    // Use the same SharedPreferences file name as SmartServiceRecording
    private val recordingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    // Key to read the audio index from RecordingPrefs set by SmartServiceRecording
    private val SMART_SERVICE_AUDIO_INDEX_KEY = "audio_index"
    // The total number of files in the circular buffer (0 to 59 inclusive)
    private val MAX_CIRCULAR_FILES = 60 // Max index 59, so (MAX_CIRCULAR_FILES - 1)

    // This property will now read the 'audio_index' from RecordingPrefs
    private var smartServiceAudioIndex: Int
        get() {
            val index = recordingPrefs.getInt(SMART_SERVICE_AUDIO_INDEX_KEY, 0)
            Log.d("AudioProcessor", "Getting SmartService audio index from RecordingPrefs: $index")
            return index
        }
        // This class should not modify the index set by SmartServiceRecording
        set(value) {
            Log.w("AudioProcessor", "Attempted to set smartServiceAudioIndex from AudioProcessor. This index is managed by SmartServiceRecording.")
        }

    // A local variable to keep track of the last index *we* processed for display
    // This prevents re-processing the same file if SmartServiceRecording hasn't written a new one yet.
    private var lastDisplayedAudioIndex: Int = -1 // Initialize to -1 to ensure the first file is processed
    // --- END: MODIFIED PREFERENCE LOGIC ---

    fun updateAudioSequence() {
        // --- START: MODIFIED LOGIC WITHIN updateAudioSequence ---

        // Read the latest index from SmartServiceRecording (which indicates the *next* file to be written)
        val recorderIndex = smartServiceAudioIndex

        // The recorderIndex points to the *next* file to be written.
        // So, the latest *completed* file is at the index before that.
        val latestRecorderFileIndex = (recorderIndex - 1 + MAX_CIRCULAR_FILES) % MAX_CIRCULAR_FILES

        Log.d("AudioProcessor", "SmartService's latest index (next to write): $recorderIndex. Attempting to read file at index: $latestRecorderFileIndex")

        // Check if this file is the same as the one we last successfully processed.
        // If it is, no new data has been written by the recorder, so just return.
        if (latestRecorderFileIndex == lastDisplayedAudioIndex) {
            Log.d("AudioProcessor", "No new audio file to process. Current: $latestRecorderFileIndex, Last Displayed: $lastDisplayedAudioIndex")
            // If no new file, but we still have data in audioWindow, re-plot it to keep the chart fresh
            if (audioWindow.isNotEmpty()) {
                val fullMagnitude = audioWindow.flatMap { it.asList() }
                val maxPoints = 5000 // Max points for rendering
                val sampledMagnitude = if (fullMagnitude.size > maxPoints) {
                    val step = fullMagnitude.size / maxPoints
                    fullMagnitude.filterIndexed { i, _ -> i % step == 0 }
                } else {
                    fullMagnitude
                }
                plotDataOnChart(chart, sampledMagnitude)
            }
            return
        }

        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val targetFile = File(audioDir, "audio_${latestRecorderFileIndex}.pcm")

        if (!targetFile.exists()) {
            Log.w("AudioProcessor", "Audio file ${targetFile.name} does not exist yet. Waiting for recorder.")
            return // File not yet written or deleted
        }
        if (targetFile.length() == 0L) {
            Log.w("AudioProcessor", "Audio file ${targetFile.name} is empty. Waiting for data.")
            return // File exists but is empty
        }

        try {
            val bytes = targetFile.readBytes()
            if (bytes.isEmpty()) {
                Log.w("AudioProcessor", "Audio file ${targetFile.name} is empty after read. Skipping.")
                return
            }

            // Ensure buffer capacity is an even number for shorts (2 bytes per short)
            if (bytes.size % 2 != 0) {
                Log.e("AudioProcessor", "Audio file ${targetFile.name} has an odd number of bytes. Skipping.")
                return
            }

            val shorts = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().let { buf ->
                    val arr = ShortArray(buf.limit())
                    buf.get(arr)
                    arr
                }

            val magnitude = try {
                computeFFT(shorts)
            } catch (e: Exception) {
                Log.e("AudioProcessor", "FFT failed for ${targetFile.name}: ${e.message}", e)
                return // Skip this file if FFT fails
            }

            if (magnitude.any { it.isNaN() || it.isInfinite() }) {
                Log.e("AudioProcessor", "Invalid FFT output for ${targetFile.name} (NaN/Infinite values). Skipping.")
                return
            }

            // Add to the rolling window
            if (audioWindow.size >= maxSeconds) {
                audioWindow.removeFirst() // Remove the oldest entry
            }
            audioWindow.addLast(magnitude) // Add the new entry

            // Successfully processed, so update the last displayed index for THIS processor
            lastDisplayedAudioIndex = latestRecorderFileIndex
            Log.d("AudioProcessor", "Successfully processed and added audio file: ${targetFile.name}. Last displayed index updated to: $lastDisplayedAudioIndex")

            // Flatten the window data for plotting
            val fullMagnitude = audioWindow.flatMap { it.asList() }
            val maxPoints = 5000 // Max points for rendering (e.g., for performance)
            val sampledMagnitude = if (fullMagnitude.size > maxPoints) {
                val step = fullMagnitude.size / maxPoints
                fullMagnitude.filterIndexed { i, _ -> i % step == 0 }
            } else {
                fullMagnitude
            }

            plotDataOnChart(chart, sampledMagnitude)

        } catch (e: IOException) {
            Log.e("AudioProcessor", "Error reading audio file ${targetFile.name}: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("AudioProcessor", "Unexpected error processing audio file ${targetFile.name}: ${e.message}", e)
        }
        // --- END: MODIFIED LOGIC WITHIN updateAudioSequence ---
    }

    private fun computeFFT(samples: ShortArray): DoubleArray {
        val n = samples.size
        val real = DoubleArray(n) { samples[it].toDouble() }
        val imag = DoubleArray(n)

        fft(real, imag)
        return real.zip(imag) { r, i -> sqrt(r * r + i * i) }.take(n / 2).toDoubleArray()
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return

        val evenReal = DoubleArray(n / 2)
        val evenImag = DoubleArray(n / 2)
        val oddReal = DoubleArray(n / 2)
        val oddImag = DoubleArray(n / 2)

        for (i in 0 until n / 2) {
            evenReal[i] = real[2 * i]
            evenImag[i] = imag[2 * i]
            oddReal[i] = real[2 * i + 1]
            oddImag[i] = imag[2 * i + 1]
        }

        fft(evenReal, evenImag)
        fft(oddReal, oddImag)

        for (k in 0 until n / 2) {
            val angle = -2 * Math.PI * k / n
            val cos = cos(angle)
            val sin = sin(angle)
            val tre = cos * oddReal[k] - sin * oddImag[k]
            val tim = sin * oddReal[k] + cos * oddImag[k]

            real[k] = evenReal[k] + tre
            imag[k] = evenImag[k] + tim
            real[k + n / 2] = evenReal[k] - tre
            imag[k + n / 2] = evenImag[k] - tim
        }
    }

    private fun plotDataOnChart(chart: LineChart, data: List<Double>) {
        val totalSeconds = audioWindow.size
        val fftBinsPerSecond = data.size / totalSeconds.toFloat()

        val entries = data.mapIndexed { index, value ->
            val second = index / fftBinsPerSecond
            Entry(second, value.toFloat())
        }

        val dataSet = LineDataSet(entries, "Frequency Spectrum").apply {
            color = Color.BLUE
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
        }

        chart.data = LineData(dataSet)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            labelCount = totalSeconds
            setDrawGridLines(false)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${value.toInt()}s"
                }
            }
        }

        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.invalidate()
    }

}