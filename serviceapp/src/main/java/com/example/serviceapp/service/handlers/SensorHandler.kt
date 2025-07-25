package com.example.serviceapp.service.handlers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorHandler (
    private val context: Context,
    private val onSensorActivated: (String) -> Unit
){
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activeSensors = mutableSetOf<String>()
    private var sensorScore = 0

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            handleSensorChange(event)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun handleSensorChange(event: SensorEvent) {
        val type = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "ACC"
            Sensor.TYPE_GYROSCOPE -> "GYRO"
            Sensor.TYPE_MAGNETIC_FIELD -> "MAG"
            else -> null
        }

        type?.let {
            if (activeSensors.add(it)) {
                onSensorActivated(it)
                sensorScore++
                Log.d("SensorHandler", "Sensor activated: $it, Score: $sensorScore")
            }
        }
    }

    private fun startListening(listener: SensorEventListener? = null) {
        val actualListener = listener ?: sensorListener

        listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(actualListener, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(sensorListener)
    }

    suspend fun logSensorData(writer: BufferedWriter, durationMillis: Long = 1000L) = withContext(Dispatchers.IO) {
        val logger = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.getDefault()).format(Date())
                val data = "${timestamp},${event.sensor.name},${event.values.joinToString(",")}"
                try {
                    writer.write("$data\n")
                    writer.flush() // Flush after writing to ensure data is not lost
                } catch (e: IOException) {
                    Log.e("SensorLogger", "Failed to write sensor data: ${e.message}")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            // Register temporary logger
            listOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_MAGNETIC_FIELD
            ).forEach { type ->
                sensorManager.getDefaultSensor(type)?.let {
                    sensorManager.registerListener(logger, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }

            delay(durationMillis)

        } finally {
            // Always unregister after logging to avoid listener overflow
            sensorManager.unregisterListener(logger)
        }
    }

}