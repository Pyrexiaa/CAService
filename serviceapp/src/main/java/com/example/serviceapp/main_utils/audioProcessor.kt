package com.example.serviceapp.main_utils

import android.content.Context
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

class AudioProcessor(
    private val context: Context,
    private val chart: LineChart
) {
    private val audioWindow = LinkedList<DoubleArray>()

    private var currentFileIndex = 1
    private val maxSeconds = 60

    fun updateAudioSequence() {
        val currentFileIndex = getMaxAudioIndex()
        if (currentFileIndex < 1) {
            Log.w("AudioUpdate", "No audio files found.")
            return
        }

        val audioFileName = "audio_${currentFileIndex}.pcm"
        val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        val audioFile = File(audioDir, audioFileName)

        Log.d("AudioUpdate", "Looking for file at: ${audioFile.absolutePath}")

        if (!audioFile.exists()) {
            Log.w("AudioUpdate", "Audio file not found: $audioFileName")
            return
        }

        val bytes = audioFile.readBytes()
        if (bytes.isEmpty()) {
            Log.w("AudioUpdate", "Audio file is empty: $audioFileName")
            return
        }

        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().let { buf ->
                val shortArray = ShortArray(buf.limit())
                buf.get(shortArray)
                shortArray
            }

        val magnitude = try {
            computeFFT(shorts)
        } catch (e: Exception) {
            Log.e("AudioUpdate", "FFT computation failed: ${e.message}")
            return
        }

        if (magnitude.any { it.isNaN() || it.isInfinite() }) {
            Log.e("AudioUpdate", "Invalid FFT output")
            return
        }

        if (audioWindow.size >= maxSeconds) {
            audioWindow.removeFirst()
        }
        audioWindow.addLast(magnitude)

        val fullMagnitude = audioWindow.flatMap { it.asList() }
        val maxPoints = 5000
        val sampledMagnitude = if (fullMagnitude.size > maxPoints) {
            val step = fullMagnitude.size / maxPoints
            fullMagnitude.filterIndexed { index, _ -> index % step == 0 }
        } else {
            fullMagnitude
        }

        plotDataOnChart(chart, sampledMagnitude)

    }

    private fun getMaxAudioIndex(): Int {
        val regex = Regex("audio_(\\d+)\\.pcm")
        val audioDir = File(context.filesDir, "audio")
        return audioDir.listFiles()
            ?.mapNotNull { file ->
                regex.find(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }?.maxOrNull() ?: 0
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