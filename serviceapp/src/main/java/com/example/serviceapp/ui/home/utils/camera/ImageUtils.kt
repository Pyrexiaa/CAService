package com.example.serviceapp.ui.home.utils.camera

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    fun rotateBitmap90AntiClockwise(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply {
            postRotate(-90f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun getRotationDegrees(context: Context): Int {
        val rotation = (context as Activity).windowManager.defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    fun cropFace(original: Bitmap, box: android.graphics.Rect): Bitmap {
        val safeBox = android.graphics.Rect(
            box.left.coerceAtLeast(0),
            box.top.coerceAtLeast(0),
            box.right.coerceAtMost(original.width),
            box.bottom.coerceAtMost(original.height)
        )
        return Bitmap.createBitmap(
            original,
            safeBox.left,
            safeBox.top,
            safeBox.width(),
            safeBox.height()
        )
    }

    fun saveBitmapForDebugging(context: Context, bitmap: Bitmap, orientation: String) {
        try {
            val debugDir = File(context.filesDir, "debug_faces")
            if (!debugDir.exists()) debugDir.mkdirs()
            val debugFile = File(debugDir, "face_debug_${orientation}_${System.currentTimeMillis()}.png")
            FileOutputStream(debugFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            Log.d("ImageUtils", "Saved debug bitmap: ${debugFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("ImageUtils", "Failed to save debug bitmap: ${e.message}", e)
        }
    }
}