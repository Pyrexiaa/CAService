package com.example.serviceapp.main_utils

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class ImageProcessor(
    private val context: Context,
    private val imageView: ImageView,
    private val coroutineScope: CoroutineScope
) {
    private var currentImageIndex = 1
    private var imageJob: Job? = null
    private val imageDir = File(context.filesDir, "image").apply { mkdirs() }

    fun startImageLoop() {
        imageJob?.cancel()

        imageJob = coroutineScope.launch {
            val maxImageIndex = getMaxImageIndex()
            if (maxImageIndex < 1) return@launch

            currentImageIndex = maxImageIndex

            while (isActive && currentImageIndex <= maxImageIndex) {
                val imageFile = File(imageDir, "image_${currentImageIndex}.jpg")
                if (imageFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                    imageView.setImageBitmap(bitmap)
                }

                currentImageIndex++
                delay(500)
            }

            // After max index, keep showing the last available image
            while (isActive) {
                val finalImage = File(imageDir, "image_${maxImageIndex}.jpg")
                if (finalImage.exists()) {
                    val bitmap = BitmapFactory.decodeFile(finalImage.absolutePath)
                    imageView.setImageBitmap(bitmap)
                }
                delay(500)
            }

        }
    }

    private fun getMaxImageIndex(): Int {
        val regex = Regex("image_(\\d+)\\.jpg")
        return imageDir.listFiles()
            ?.mapNotNull { file ->
                regex.find(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }?.maxOrNull() ?: 0
    }

    fun stopImageLoop() {
        imageJob?.cancel()
        imageJob = null
    }
}
