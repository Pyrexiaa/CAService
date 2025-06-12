package com.example.serviceapp.main_utils

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.File

class SensorProcessor(private val context: Context) {

    fun updateSensor(
        accelChart: LineChart,
        gyroChart: LineChart,
        magnetChart: LineChart
    ) {
        val sensorDir = File(context.filesDir, "sensor").apply { mkdirs() }
        val sensorFiles = sensorDir
            .listFiles { _, name -> name.matches(Regex("sensor_\\d+\\.txt")) }
            ?.sortedBy { it.nameWithoutExtension.substringAfter("sensor_").toInt() }
            ?: return

        val accelX = mutableListOf<Entry>()
        val accelY = mutableListOf<Entry>()
        val accelZ = mutableListOf<Entry>()

        val gyroX = mutableListOf<Entry>()
        val gyroY = mutableListOf<Entry>()
        val gyroZ = mutableListOf<Entry>()

        val magX = mutableListOf<Entry>()
        val magY = mutableListOf<Entry>()
        val magZ = mutableListOf<Entry>()

        sensorFiles.forEachIndexed { secondIndex, file ->
            val xValue = secondIndex.toFloat() + 1

            file.readLines().forEach { line ->
                val parts = line.split(",")
                if (parts.size < 5) return@forEach

                val sensorType = parts[1].trim()
                val x = parts[2].toFloatOrNull() ?: return@forEach
                val y = parts[3].toFloatOrNull() ?: return@forEach
                val z = parts[4].toFloatOrNull() ?: return@forEach

                when {
                    "Accelerometer" in sensorType -> {
                        accelX.add(Entry(xValue, x))
                        accelY.add(Entry(xValue, y))
                        accelZ.add(Entry(xValue, z))
                    }

                    "Gyroscope" in sensorType -> {
                        gyroX.add(Entry(xValue, x))
                        gyroY.add(Entry(xValue, y))
                        gyroZ.add(Entry(xValue, z))
                    }

                    "Magnetic field" in sensorType -> {
                        magX.add(Entry(xValue, x))
                        magY.add(Entry(xValue, y))
                        magZ.add(Entry(xValue, z))
                    }
                }
            }
        }

        plotSensor(accelChart, accelX, accelY, accelZ, "Accelerometer")
        plotSensor(gyroChart, gyroX, gyroY, gyroZ, "Gyroscope")
        plotSensor(magnetChart, magX, magY, magZ, "Magnetic Field")
    }

    private fun plotSensor(
        chart: LineChart,
        xData: List<Entry>,
        yData: List<Entry>,
        zData: List<Entry>,
        label: String
    ) {
        val data = LineData(
            LineDataSet(xData, "$label X").apply {
                color = Color.RED; setDrawCircles(false); lineWidth = 1.5f; setDrawValues(false)
            },
            LineDataSet(yData, "$label Y").apply {
                color = Color.GREEN; setDrawCircles(false); lineWidth = 1.5f; setDrawValues(false)
            },
            LineDataSet(zData, "$label Z").apply {
                color = Color.BLUE; setDrawCircles(false); lineWidth = 1.5f; setDrawValues(false)
            }
        )

        chart.data = data
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.granularity = 1f
        chart.invalidate()
    }
}
