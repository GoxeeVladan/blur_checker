// blur_checker/android/src/main/kotlin/com/example/blur_checker/BlurCheckerPlugin.kt
package com.example.blur_checker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.AsyncTask
import android.util.Log
import androidx.annotation.NonNull
import com.example.blur_checker.BlurCheckerUtils
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

data class LaplacianResult(val stdDev: Double, val edgeCount: Int)

object BlurCheckerUtils {
  private const val PROCESSING_SCALE_FACTOR = 0.2
  private const val MOSTLY_SOLID_THRESHOLD = 5
  private const val MOSTLY_SOLID_SAMPLE_RATIO = 0.3
  private const val LAPLACIAN_KERNEL_SIZE = 3
  private val LAPLACIAN_KERNEL = arrayOf(
    intArrayOf(0, -1, 0),
    intArrayOf(-1, 4, -1),
    intArrayOf(0, -1, 0)
  )
  private const val SOBEL_KERNEL_SIZE = 3
  private val SOBEL_X_KERNEL = arrayOf(
    intArrayOf(-1, 0, 1),
    intArrayOf(-2, 0, 2),
    intArrayOf(-1, 0, 1)
  )
  private val SOBEL_Y_KERNEL = arrayOf(
    intArrayOf(1, 2, 1),
    intArrayOf(0, 0, 0),
    intArrayOf(-1, -2, -1)
  )
  private const val DARK_CHANNEL_PATCH_SIZE = 7

  fun computeLensDirtyScore(originalBitmap: Bitmap): Double {
    val scaledBitmap = scaleBitmap(originalBitmap, PROCESSING_SCALE_FACTOR)

    if (isMostlySolid(scaledBitmap, MOSTLY_SOLID_THRESHOLD, MOSTLY_SOLID_SAMPLE_RATIO)) {
      scaledBitmap.recycle()
      Log.d("LensCheck", "Image is mostly solid — returning 0.0")
      return 0.0
    }

    val lapResult = computeLaplacianResult(scaledBitmap)
    val laplacianStd = lapResult.stdDev
    val tenengrad = computeTenengradScore(scaledBitmap)
    val contrastStd = computeGlobalContrastStdDev(scaledBitmap)
    val brightness = computeAverageBrightness(scaledBitmap)
    val darkChannelAvg = computeDarkChannelAverage(scaledBitmap)

    scaledBitmap.recycle()

    // Normalized feature values with more conservative clamping
    val laplacianScaled = (laplacianStd / 30.0).coerceIn(0.0, 1.2)
    val tenengradScaled = (tenengrad / 50.0).coerceIn(0.0, 0.9)
    val contrastScaled = (contrastStd / 50.0).coerceIn(0.0, 0.9)
    val darkChannelScaled = (darkChannelAvg / 60.0).coerceIn(0.0, 1.3)

    // Adjust useTenengrad logic - consider contrast more strongly
    val useTenengrad = (contrastStd < 18.0) || (brightness > 160) || (darkChannelAvg > 30)
    val blendFactor = if (useTenengrad) 0.7 else 0.3

    val edgeFocusScore = 1.0 - ((blendFactor * tenengradScaled) + ((1 - blendFactor) * laplacianScaled))

    var dirtyScore = (0.4 * darkChannelScaled) +
            (0.35 * (1.0 - contrastScaled)) +
            (0.25 * edgeFocusScore)

    // Reduce impact of dark channel if contrast is very low
    if (contrastStd < 8.0) {
      dirtyScore -= 0.3 * darkChannelScaled.coerceAtMost(0.6)
      dirtyScore = dirtyScore.coerceAtLeast(0.0)
    }

    // Adjust based on edge strength
    val edgeWeightFactor = when {
      edgeFocusScore > 0.8 -> 0.5
      edgeFocusScore < 0.3 -> 0.85
      else -> 1.0
    }

    val adjustedScore = dirtyScore * edgeWeightFactor

    val hazeTrigger = (darkChannelScaled > 0.6 && contrastStd < 16) || (darkChannelScaled > 1.0)
    return if (hazeTrigger && adjustedScore < 0.65) 0.70 else adjustedScore
  }

  private fun scaleBitmap(bitmap: Bitmap, factor: Double): Bitmap {
    return Bitmap.createScaledBitmap(
      bitmap,
      (bitmap.width * factor).toInt().coerceAtLeast(1),
      (bitmap.height * factor).toInt().coerceAtLeast(1),
      true
    )
  }

  private fun isMostlySolid(bitmap: Bitmap, threshold: Int, sampleRatio: Double): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val numPixels = width * height
    if (numPixels <= 1) return true

    val numSamples = (numPixels * sampleRatio).toInt().coerceAtLeast(50)
    val pixels = IntArray(numSamples)
    val random = java.util.Random()

    val baseColor = bitmap.getPixel(random.nextInt(width), random.nextInt(height))
    val r0 = Color.red(baseColor)
    val g0 = Color.green(baseColor)
    val b0 = Color.blue(baseColor)

    var totalDiff = 0L
    for (i in 0 until numSamples) {
      val x = random.nextInt(width)
      val y = random.nextInt(height)
      val p = bitmap.getPixel(x, y)
      totalDiff += kotlin.math.abs(Color.red(p) - r0) +
              kotlin.math.abs(Color.green(p) - g0) +
              kotlin.math.abs(Color.blue(p) - b0)
    }

    val avgDiff = totalDiff.toDouble() / numSamples
    return avgDiff < threshold
  }

  private fun computeLaplacianResult(bitmap: Bitmap): LaplacianResult {
    val width = bitmap.width
    val height = bitmap.height
    if (width < LAPLACIAN_KERNEL_SIZE || height < LAPLACIAN_KERNEL_SIZE) {
      return LaplacianResult(0.0, 0)
    }

    val gray = Array(height) { IntArray(width) }
    for (y in 0 until height) {
      for (x in 0 until width) {
        gray[y][x] = (Color.luminance(bitmap.getPixel(x, y)) * 255).toInt()
      }
    }

    var sum = 0.0
    var sumSq = 0.0
    var count = 0
    var strongEdgeCount = 0
    val edgeThreshold = 20

    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        var laplacianValue = 0
        for (ky in -1..1) {
          for (kx in -1..1) {
            laplacianValue += gray[y + ky][x + kx] * LAPLACIAN_KERNEL[ky + 1][kx + 1]
          }
        }
        val v = laplacianValue.toDouble()
        sum += v
        sumSq += v * v
        if (kotlin.math.abs(v) > edgeThreshold) strongEdgeCount++
        count++
      }
    }

    val variance = if (count > 0) (sumSq / count) - (sum / count).pow(2) else 0.0
    return LaplacianResult(sqrt(variance.coerceAtLeast(0.0)), strongEdgeCount)
  }

  private fun computeGlobalContrastStdDev(bitmap: Bitmap): Double {
    val width = bitmap.width
    val height = bitmap.height
    val luminanceValues = DoubleArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        luminanceValues[y * width + x] = Color.luminance(bitmap.getPixel(x, y)).toDouble()
      }
    }
    if (luminanceValues.isEmpty()) return 0.0
    val mean = luminanceValues.average()
    val variance = luminanceValues.sumOf { (it - mean).pow(2) } / luminanceValues.size
    return sqrt(variance)
  }

  private fun computeDarkChannelAverage(bitmap: Bitmap): Double {
    val width = bitmap.width
    val height = bitmap.height
    if (width < DARK_CHANNEL_PATCH_SIZE || height < DARK_CHANNEL_PATCH_SIZE) {
      var minVal = 255
      for (y in 0 until height) {
        for (x in 0 until width) {
          val pixel = bitmap.getPixel(x, y)
          minVal = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel), minVal)
        }
      }
      return minVal.toDouble()
    }

    val pad = DARK_CHANNEL_PATCH_SIZE / 2
    var sum = 0
    var count = 0

    for (y in 0 until height) {
      for (x in 0 until width) {
        var localMin = 255
        for (dy in -pad..pad) {
          for (dx in -pad..pad) {
            val ny = y + dy
            val nx = x + dx
            if (ny in 0 until height && nx in 0 until width) {
              val pixel = bitmap.getPixel(nx, ny)
              localMin = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel), localMin)
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
    val width = bitmap.width
    val height = bitmap.height
    var totalLuminance = 0.0
    for (y in 0 until height) {
      for (x in 0 until width) {
        totalLuminance += Color.luminance(bitmap.getPixel(x, y))
      }
    }
    return if (width * height > 0) (totalLuminance / (width * height)) * 255 else 0.0
  }

  private fun computeTenengradScore(bitmap: Bitmap): Double {
    val width = bitmap.width
    val height = bitmap.height
    if (width < SOBEL_KERNEL_SIZE || height < SOBEL_KERNEL_SIZE) {
      return 0.0
    }

    val gray = Array(height) { IntArray(width) }
    for (y in 0 until height) {
      for (x in 0 until width) {
        gray[y][x] = (Color.luminance(bitmap.getPixel(x, y)) * 255).toInt()
      }
    }

    var sumMagnitude = 0.0
    var count = 0

    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        var gx = 0
        var gy = 0
        for (ky in -1..1) {
          for (kx in -1..1) {
            val pixelValue = gray[y + ky][x + kx]
            gx += pixelValue * SOBEL_X_KERNEL[ky + 1][kx + 1]
            gy += pixelValue * SOBEL_Y_KERNEL[ky + 1][kx + 1]
          }
        }
        sumMagnitude += sqrt((gx * gx + gy * gy).toDouble())
        count++
      }
    }
    return if (count > 0) sumMagnitude / count else 0.0
  }
}

class BlurCheckerPlugin : FlutterPlugin, MethodCallHandler {
  private lateinit var channel: MethodChannel

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
        ProcessImageTask(path, false, result).execute()
      }

      "isLensDirty" -> {
        val path = call.argument<String>("path")
        if (path == null) {
          result.error("ARGUMENT_ERROR", "Path argument is required", null)
          return
        }
        ProcessImageTask(path, true, result).execute()
      }

      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private inner class ProcessImageTask(
    private val path: String,
    private val isLensDirtyCheck: Boolean,
    private val result: MethodChannel.Result
  ) : AsyncTask<Void, Void, Any?>() {

    override fun doInBackground(vararg params: Void?): Any? {
      val path = File(path).absolutePath
      val bitmap = decodeSampledBitmap(path, 640, 640) // Example: Target 512x512
      if (bitmap == null) {
        return "BITMAP_NULL"
      }

      val score = BlurCheckerUtils.computeLensDirtyScore(bitmap)
      bitmap.recycle()
      return if (isLensDirtyCheck) score >= 0.7 else score
    }

    override fun onPostExecute(returnValue: Any?) {
      when (returnValue) {
        is Double -> result.success(returnValue)
        is Boolean -> result.success(returnValue)
        is String -> result.error(returnValue, "Failed to decode image", null)
        null -> result.error("NULL_RESULT", "Image processing failed", null)
      }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
      // First decode with inJustDecodeBounds=true to check dimensions
      val options = BitmapFactory.Options()
      options.inJustDecodeBounds = true
      BitmapFactory.decodeFile(path, options)

      // Calculate inSampleSize
      options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

      // Decode bitmap with inSampleSize set
      options.inJustDecodeBounds = false
      return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
      // Raw height and width of image
      val height = options.outHeight
      val width = options.outWidth
      var inSampleSize = 1

      if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2

        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // height and width larger than the requested height and width.
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
          inSampleSize *= 2
        }
      }

      return inSampleSize
    }
  }
}