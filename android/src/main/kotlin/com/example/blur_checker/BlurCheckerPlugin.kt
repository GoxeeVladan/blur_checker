package com.example.blur_checker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.AsyncTask
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

data class LaplacianResult(val stdDev: Double, val edgeCount: Int)

class BlurCheckerPlugin : FlutterPlugin, MethodCallHandler {
  private lateinit var channel: MethodChannel
  private val processingScaleFactor = 0.2f // Experiment with this value
  private val nearSolidColorScaleFactor = 0.05f
  private val nearSolidColorTolerance = 10
  private val nearSolidColorSampleSize = 10

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "blur_checker_native")
    channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getLensDirtyScore" -> {
        val path = call.argument<String>("path")
        if (path == null) {
          result.error("ARGUMENT_ERROR", "Path argument is required", null)
          return
        }
        ProcessImageTask(path, false, result, this).execute()
      }
      "isLensDirty" -> {
        val path = call.argument<String>("path")
        if (path == null) {
          result.error("ARGUMENT_ERROR", "Path argument is required", null)
          return
        }
        ProcessImageTask(path, true, result, this).execute()
      }
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private fun computeLensDirtyScore(originalBitmap: Bitmap): Double {
    val scaledBitmap = Bitmap.createScaledBitmap(
      originalBitmap,
      (originalBitmap.width * processingScaleFactor).toInt().coerceAtLeast(1),
      (originalBitmap.height * processingScaleFactor).toInt().coerceAtLeast(1),
      true
    )

    // Early exit for solid or mostly solid images (UI, backgrounds, clean screens)
    if (isMostlySolid(scaledBitmap)) {
      scaledBitmap.recycle()
      Log.d("LensCheck", "Image is mostly solid — returning 0.0")
      return 0.0
    }

    val lapResult = computeLaplacianResult(scaledBitmap)
    val laplacianStd = lapResult.stdDev
    val edgeCount = lapResult.edgeCount
    val tenengrad = computeTenengradScore(scaledBitmap)
    val contrastStd = computeGlobalContrastStdDev(scaledBitmap)
    val brightness = computeAverageBrightness(scaledBitmap)
    val darkChannel = computeDarkChannelAverage(scaledBitmap)

    scaledBitmap.recycle()

    // Normalize feature values
    val laplacianScaled = (laplacianStd / 30.0).coerceIn(0.0, 1.5)
    val tenengradScaled = (tenengrad / 50.0).coerceIn(0.0, 1.0)
    val contrastScaled = (contrastStd / 50.0).coerceIn(0.0, 1.0)
    val darkChannelScaled = (darkChannel / 60.0).coerceIn(0.0, 2.0)

    // Switch to Tenengrad more heavily if contrast is low or image is bright
    val useTenengrad = (contrastStd < 15.0) || (brightness > 170) || (darkChannel > 35)
    val blendFactor = if (useTenengrad) 0.7 else 0.3

    val edgeFocusScore = 1.0 - ((blendFactor * tenengradScaled) + ((1 - blendFactor) * laplacianScaled))

    val dirtyScore = (0.4 * darkChannelScaled) +
            (0.35 * (1.0 - contrastScaled)) +
            (0.25 * edgeFocusScore)

    // Adjust based on edge strength
    val edgeWeightFactor = when {
      edgeFocusScore > 0.8 -> 0.5
      edgeFocusScore < 0.3 -> 0.85
      else -> 1.0
    }

    val adjustedScore = dirtyScore * edgeWeightFactor

    val hazeTrigger = (darkChannelScaled > 0.7 && contrastStd < 15) || (darkChannelScaled > 1.0)
    return if (hazeTrigger && adjustedScore < 0.6) 0.75 else adjustedScore
  }

  private fun computeLaplacianResult(bitmap: Bitmap): LaplacianResult {
    val w = bitmap.width
    val h = bitmap.height
    val gray = Array(h) { IntArray(w) }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = bitmap.getPixel(x, y)
        gray[y][x] = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
      }
    }

    val kernel = arrayOf(
      intArrayOf(0, -1, 0),
      intArrayOf(-1, 4, -1),
      intArrayOf(0, -1, 0)
    )

    var sum = 0.0
    var sumSq = 0.0
    var count = 0
    var strongEdgeCount = 0

    for (y in 1 until h - 1) {
      for (x in 1 until w - 1) {
        var lap = 0
        for (ky in -1..1) {
          for (kx in -1..1) {
            lap += gray[y + ky][x + kx] * kernel[ky + 1][kx + 1]
          }
        }
        val v = lap.toDouble()
        sum += v
        sumSq += v * v
        if (kotlin.math.abs(v) > 15) strongEdgeCount++
        count++
      }
    }
    val variance = if (count > 0) (sumSq / count) - (sum / count).pow(2) else 0.0
    return LaplacianResult(sqrt(variance.coerceAtLeast(0.0)), strongEdgeCount)
  }

  private fun computeGlobalContrastStdDev(bitmap: Bitmap): Double {
    val w = bitmap.width
    val h = bitmap.height
    val values = DoubleArray(w * h)
    var idx = 0
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = bitmap.getPixel(x, y)
        values[idx++] = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
      }
    }
    if (values.isEmpty()) return 0.0
    val mean = values.average()
    val variance = values.sumOf { (it - mean).pow(2) } / values.size
    return sqrt(variance)
  }

//  private fun isNearSolidColor(bitmap: Bitmap, tolerance: Int = 15, sampleSize: Int = 20): Boolean {
//    val w = bitmap.width
//    val h = bitmap.height
//    if (w * h <= 1) return true
//
//    val random = java.util.Random()
//    var totalRed = 0
//    var totalGreen = 0
//    var totalBlue = 0
//
//    for (i in 0 until minOf(sampleSize, w * h)) {
//      val x = random.nextInt(w)
//      val y = random.nextInt(h)
//      val pixel = bitmap.getPixel(x, y)
//      totalRed += Color.red(pixel)
//      totalGreen += Color.green(pixel)
//      totalBlue += Color.blue(pixel)
//    }
//
//    val sampleCount = minOf(sampleSize, w * h)
//    if (sampleCount == 0) return true
//
//    val avgRed = totalRed / sampleCount
//    val avgGreen = totalGreen / sampleCount
//    val avgBlue = totalBlue / sampleCount
//
//    val checkSampleSize = minOf(200, w * h) // Increased check sample
//    for (i in 0 until checkSampleSize) {
//      val x = random.nextInt(w)
//      val y = random.nextInt(h)
//      val pixel = bitmap.getPixel(x, y)
//      if (kotlin.math.abs(Color.red(pixel) - avgRed) > tolerance ||
//        kotlin.math.abs(Color.green(pixel) - avgGreen) > tolerance ||
//        kotlin.math.abs(Color.blue(pixel) - avgBlue) > tolerance) {
//        return false
//      }
//    }
//    return true
//  }

  private fun isMostlySolid(bitmap: Bitmap, threshold: Int = 10): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val baseColor = pixels[0]
    val r0 = (baseColor shr 16) and 0xFF
    val g0 = (baseColor shr 8) and 0xFF
    val b0 = baseColor and 0xFF

    var totalDiff = 0L
    for (i in 1 until pixels.size) {
      val p = pixels[i]
      val r = (p shr 16) and 0xFF
      val g = (p shr 8) and 0xFF
      val b = p and 0xFF
      totalDiff += Math.abs(r - r0) + Math.abs(g - g0) + Math.abs(b - b0)
    }

    val avgDiff = totalDiff / pixels.size
    return avgDiff < threshold
  }

  private fun isBlockSolid(bitmap: Bitmap, startX: Int, startY: Int, endX: Int, endY: Int, tolerance: Int, sampleSize: Int): Boolean {
    val random = java.util.Random()
    var totalRed = 0
    var totalGreen = 0
    var totalBlue = 0

    val pixelCount = (endX - startX) * (endY - startY)
    val samples = minOf(sampleSize, pixelCount)
    if (samples <= 1) return true

    for (i in 0 until samples) {
      val x = startX + random.nextInt(endX - startX)
      val y = startY + random.nextInt(endY - startY)
      val pixel = bitmap.getPixel(x, y)
      totalRed += Color.red(pixel)
      totalGreen += Color.green(pixel)
      totalBlue += Color.blue(pixel)
    }

    val avgRed = totalRed / samples
    val avgGreen = totalGreen / samples
    val avgBlue = totalBlue / samples

    for (i in 0 until samples) {
      val x = startX + random.nextInt(endX - startX)
      val y = startY + random.nextInt(endY - startY)
      val pixel = bitmap.getPixel(x, y)
      if (kotlin.math.abs(Color.red(pixel) - avgRed) > tolerance ||
        kotlin.math.abs(Color.green(pixel) - avgGreen) > tolerance ||
        kotlin.math.abs(Color.blue(pixel) - avgBlue) > tolerance) {
        return false
      }
    }
    return true
  }


  private fun computeDarkChannelAverage(bitmap: Bitmap): Double {
    val w = bitmap.width
    val h = bitmap.height
    val darkChannel = Array(h) { IntArray(w) }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = bitmap.getPixel(x, y)
        darkChannel[y][x] = minOf(Color.red(p), Color.green(p), Color.blue(p))
      }
    }

    val patchSize = 7
    val pad = patchSize / 2
    var sum = 0L
    var count = 0

    for (y in 0 until h) {
      for (x in 0 until w) {
        var localMin = 255
        for (dy in -pad..pad) {
          for (dx in -pad..pad) {
            val ny = y + dy
            val nx = x + dx
            if (ny in 0 until h && nx in 0 until w) {
              localMin = minOf(localMin, darkChannel[ny][nx])
            }
          }
        }
        sum += localMin
        count++
      }
    }
    return if (count > 0) sum.toDouble() / count else 0.0
  }

  private fun computeAverageBrightness(bitmap: Bitmap): Double {
    val w = bitmap.width
    val h = bitmap.height
    var total = 0.0
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = bitmap.getPixel(x, y)
        total += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
      }
    }
    return if (w * h > 0) total / (w * h) else 0.0
  }

  private fun computeTenengradScore(bitmap: Bitmap): Double {
    val w = bitmap.width
    val h = bitmap.height
    val gray = Array(h) { IntArray(w) }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = bitmap.getPixel(x, y)
        gray[y][x] = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
      }
    }

    val sobelX = arrayOf(
      intArrayOf(-1, 0, 1),
      intArrayOf(-2, 0, 2),
      intArrayOf(-1, 0, 1)
    )
    val sobelY = arrayOf(
      intArrayOf(1, 2, 1),
      intArrayOf(0, 0, 0),
      intArrayOf(-1, -2, -1)
    )

    var sum = 0.0
    var count = 0

    for (y in 1 until h - 1) {
      for (x in 1 until w - 1) {
        var gx = 0
        var gy = 0
        for (ky in -1..1) {
          for (kx in -1..1) {
            val px = gray[y + ky][x + kx]
            gx += px * sobelX[ky + 1][kx + 1]
            gy += px * sobelY[ky + 1][kx + 1]
          }
        }
        val magnitude = sqrt((gx * gx + gy * gy).toDouble())
        sum += magnitude
        count++
      }
    }
    return if (count > 0) sum / count else 0.0
  }

  private inner class ProcessImageTask(
    private val path: String,
    private val isLensDirtyCheck: Boolean,
    private val result: MethodChannel.Result,
    private val plugin: BlurCheckerPlugin
  ) : AsyncTask<Void, Void, Any>() {

    override fun doInBackground(vararg params: Void?): Any {
      val bitmap = BitmapFactory.decodeFile(File(path).absolutePath)
      return if (bitmap != null) {
        val scaledBitmap = Bitmap.createScaledBitmap(
          bitmap,
          (bitmap.width * processingScaleFactor).toInt().coerceAtLeast(1),
          (bitmap.height * processingScaleFactor).toInt().coerceAtLeast(1),
          true
        )
        val score = plugin.computeLensDirtyScore(scaledBitmap)
        scaledBitmap.recycle()
        if (isLensDirtyCheck) score >= 0.7 else score
      } else {
        "BITMAP_NULL"
      }
    }

    override fun onPostExecute(returnValue: Any) {
      when (returnValue) {
        is Double -> result.success(returnValue)
        is Boolean -> result.success(returnValue)
        is String -> result.error(returnValue, "Failed to decode image", null)
      }
    }
  }
}