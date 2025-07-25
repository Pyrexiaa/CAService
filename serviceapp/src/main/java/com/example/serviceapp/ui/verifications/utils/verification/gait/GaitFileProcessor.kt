package com.example.serviceapp.ui.verifications.utils.verification.gait

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object GaitFileProcessor {
    fun mergeTxtFiles(inputFiles: List<File>, outputFile: File) {
        val mergedLines = mutableListOf<List<String>>()

        for (file in inputFiles) {
            val lines = file.readLines()

            for (line in lines) {
                val parts = line.split(",")
                if (parts.size != 5) continue

                val timestamp = parts[0]
                val sensorType = parts[1]
                val x = parts[2]
                val y = parts[3]
                val z = parts[4]

                if (sensorType != "LSM6DSL Acceleration Sensor") continue
                mergedLines.add(listOf(timestamp, x, y, z))
            }
        }

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS")
        val baseTime = mergedLines.firstOrNull()?.get(0)?.let {
            LocalDateTime.parse(it, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } ?: return

        outputFile.bufferedWriter().use { writer ->
            writer.write("time,x,y,z\n")
            for ((timestampStr, x, y, z) in mergedLines) {
                val timeMillis = try {
                    LocalDateTime.parse(timestampStr, formatter)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                } catch (e: Exception) {
                    continue
                }
                val elapsed = (timeMillis - baseTime) / 1000.0
                writer.write("$elapsed,$x,$y,$z\n")
            }
        }
    }
}