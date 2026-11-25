package io.github.staperfeed.facebridge

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.io.File

/**
 * Documentation screenshots without the headset optics: renders the window's view tree straight into a
 * bitmap (2x, transparent rounded corners kept) and saves a PNG to the app's external files directory:
 *
 *   adb shell am start -n io.github.staperfeed.facebridge/.AdbControl --ez screenshots true
 *   adb shell am start -n io.github.staperfeed.facebridge/.AdbControl --ez calibrate true --ez screenshots true
 *   adb pull /sdcard/Android/data/io.github.staperfeed.facebridge/files/screenshots
 *
 * Reachable only through the DUMP-protected .AdbControl alias. Elevation shadows are not part of a
 * software render, so they are absent from the images.
 */
object Screenshots {
    const val EXTRA = "screenshots"
    private const val TAG = "VrcftBridge"
    private const val SCALE = 2f

    fun capture(activity: Activity, name: String): File? {
        val root = activity.window.decorView
        if (root.width == 0 || root.height == 0) return null
        return try {
            val bitmap = Bitmap.createBitmap((root.width * SCALE).toInt(), (root.height * SCALE).toInt(), Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { scale(SCALE, SCALE) }.also { root.draw(it) }
            val dir = File(activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            val file = File(dir, "$name.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Log.i(TAG, "screenshot saved: $file")
            file
        } catch (e: Exception) {
            Log.w(TAG, "screenshot $name failed: $e")
            null
        }
    }
}
