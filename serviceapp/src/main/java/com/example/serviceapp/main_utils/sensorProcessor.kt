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
import java.io.File
import androidx.core.content.edit
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException

class SensorProcessor(private val context: Context) {

    // Use the same SharedPreferences file name as SmartServiceRecording
    private val recordingPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("RecordingPrefs", Context.MODE_PRIVATE)
    }

    // Key to read the sensor index from RecordingPrefs set by SmartServiceRecording
    private val SMART_SERVICE_SENSOR_INDEX_KEY = "sensor_index"
    // The total number of files in the circular buffer (0 to 59 inclusive)
    private val MAX_CIRCULAR_FILES = 60 // Max index 59, so (MAX_CIRCULAR_FILES - 1)

    // This property will now read the 'sensor_index' from RecordingPrefs
    private var smartServiceSensorIndex: Int
        get() {
            // Log the value we are getting
            val index = recordingPrefs.getInt(SMART_SERVICE_SENSOR_INDEX_KEY, 0)
            Log.d("SensorProcessor", "Getting SmartService sensor index from RecordingPrefs: $index")
            return index
        }
        // This class should not modify the index set by SmartServiceRecording
        set(value) {
            Log.w("SensorProcessor", "Attempted to set smartServiceSensorIndex from SensorProcessor. This index is managed by SmartServiceRecording.")
        }

    // A local variable to keep track of the last index *we* processed for display
    // This prevents re-processing the same file if SmartServiceRecording hasn't written a new one yet.
    private var lastDisplayedSensorIndex: Int = -1

    fun updateSensor(
        accelChart: LineChart,
        gyroChart: LineChart,
        magnetChart: LineChart
    ) {
        // Read the latest index from SmartServiceRecording
        val recorderIndex = smartServiceSensorIndex

        // The recorderIndex points to the *next* file to be written.
        // So, the latest *completed* file is (recorderIndex - 1 + MAX_CIRCULAR_FILES) % MAX_CIRCULAR_FILES.
        val latestRecorderFileIndex = (recorderIndex - 1 + MAX_CIRCULAR_FILES) % MAX_CIRCULAR_FILES

        // Log the expected file to read
        Log.d("SensorProcessor", "SmartService's latest index (next to write): $recorderIndex. Attempting to read file at index: $latestRecorderFileIndex")


        // Check if this file is the same as the one we last processed.
        // If it is, no new data has been written by the recorder, so just return.
        if (latestRecorderFileIndex == lastDisplayedSensorIndex) {
            Log.d("SensorProcessor", "No new sensor file to process. Current: $latestRecorderFileIndex, Last Displayed: $lastDisplayedSensorIndex")
            return
        }

        val sensorDir = File(context.filesDir, "sensor").apply { mkdirs() }
        val targetFile = File(sensorDir, "sensor_${latestRecorderFileIndex}.txt")

        if (!targetFile.exists()) {
            Log.w("SensorProcessor", "Sensor file ${targetFile.name} does not exist yet. Waiting for recorder.")
            return // File not yet written or deleted
        }
        if (targetFile.length() == 0L) {
            Log.w("SensorProcessor", "Sensor file ${targetFile.name} is empty. Waiting for data.")
            return // File exists but is empty
        }

        val accelX = mutableListOf<Entry>()
        val accelY = mutableListOf<Entry>()
        val accelZ = mutableListOf<Entry>()

        val gyroX = mutableListOf<Entry>()
        val gyroY = mutableListOf<Entry>()
        val gyroZ = mutableListOf<Entry>()

        val magX = mutableListOf<Entry>()
        val magY = mutableListOf<Entry>()
        val magZ = mutableListOf<Entry>()

        try {
            BufferedReader(FileReader(targetFile)).use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split(",")
                    if (parts.size < 5) {
                        Log.w("SensorProcessor", "Skipping malformed line in ${targetFile.name}: $line")
                        return@forEachLine // Skip to the next line
                    }

                    // Assuming the timestamp is parts[0]
                    val sensorType = parts[1].trim()
                    val x = parts[2].toFloatOrNull()
                    val y = parts[3].toFloatOrNull()
                    val z = parts[4].toFloatOrNull()

                    // Ensure all values are valid floats
                    if (x == null || y == null || z == null) {
                        Log.w("SensorProcessor", "Skipping line with invalid numeric data in ${targetFile.name}: $line")
                        return@forEachLine
                    }

                    // For plotting, we can use a simple counter for xValue within this single file's data.
                    // Or, if you want relative time across multiple files (more complex, requires parsing timestamps),
                    // you'd need a different x-axis strategy. For a single file representing a 1-second segment,
                    // a simple incrementing x-value for each sensor reading is typical.
                    val xValue = accelX.size.toFloat() // Use count as x-axis value (sample index within the file)

                    when {
                        "Acceleration" in sensorType -> {
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

            // Only update lastDisplayedSensorIndex if processing was successful
            lastDisplayedSensorIndex = latestRecorderFileIndex
            Log.d("SensorProcessor", "Successfully processed and plotted sensor file: ${targetFile.name}. Last displayed index updated to: $lastDisplayedSensorIndex")

            // Plot the data
            plotSensor(accelChart, accelX, accelY, accelZ, "Accelerometer")
            plotSensor(gyroChart, gyroX, gyroY, gyroZ, "Gyroscope")
            plotSensor(magnetChart, magX, magY, magZ, "Magnetic Field")

        } catch (e: IOException) {
            Log.e("SensorProcessor", "Error reading sensor file ${targetFile.name}: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("SensorProcessor", "Unexpected error processing sensor file ${targetFile.name}: ${e.message}", e)
        }
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
