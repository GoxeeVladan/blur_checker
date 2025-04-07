package com.example.blur_checker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "blur_checker_native")
    channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getLensDirtyScore" -> {
        val path = call.argument<String>("path")
        val bitmap = BitmapFactory.decodeFile(File(path ?: "").absolutePath)

        if (bitmap != null) {
          val score = computeLensDirtyScore(bitmap)
          result.success(score)
        } else {
          result.error("BITMAP_NULL", "Failed to decode image", null)
        }
      }
      "isLensDirty" -> {
        val path = call.argument<String>("path")
        val bitmap = BitmapFactory.decodeFile(File(path ?: "").absolutePath)

        if (bitmap != null) {
          val score = computeLensDirtyScore(bitmap)
          result.success(score >= 0.7)
        } else {
          result.error("BITMAP_NULL", "Failed to decode image", null)
        }
      }
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  private fun computeLensDirtyScore(bitmap: Bitmap): Double {
    val lapResult = computeLaplacianResult(bitmap)
    val laplacianStd = lapResult.stdDev
    val edgeCount = lapResult.edgeCount
    val tenengrad = computeTenengradScore(bitmap)
    val contrastStd = computeGlobalContrastStdDev(bitmap)
    val brightness = computeAverageBrightness(bitmap)
    val darkChannelVal = computeDarkChannelAverage(bitmap)
    val laplacianScaled = (laplacianStd / 30.0).coerceIn(0.0, 1.5)
    val tenengradScaled = (tenengrad / 50.0).coerceIn(0.0, 1.0)
    val contrastScaled = (contrastStd / 50.0).coerceIn(0.0, 1.0)
    val darkChannelScaled = (darkChannelVal / 60.0).coerceIn(0.0, 2.0)

    val useTenengrad = (contrastStd < 15.0) || (brightness > 170) || (darkChannelVal > 35)
    val blendFactor = if (useTenengrad) 0.7 else 0.3 // Weighted preference

    val edgeFocusScore = (1.0 - ((blendFactor * tenengradScaled) + ((1 - blendFactor) * laplacianScaled)))

    val dirtyScore = (0.4 * darkChannelScaled) +
            (0.35 * (1.0 - contrastScaled)) +
            (0.25 * edgeFocusScore)

    val edgeWeightFactor = when {
      edgeFocusScore > 0.8 -> 0.5
      edgeFocusScore < 0.3 -> 0.85
      else -> 1.0
    }

    val adjustedScore = dirtyScore * edgeWeightFactor

    // Updated haze fallback logic
    val hazeTrigger = (darkChannelScaled > 0.7 && contrastStd < 15) || (darkChannelScaled > 1.0)
    val finalScore = if (hazeTrigger && adjustedScore < 0.6) 0.75 else adjustedScore

    return finalScore
  }

  private fun computeLaplacianResult(bitmap: Bitmap): LaplacianResult {
    val scaleFactor = 0.4f
    val w = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

    val gray = Array(h) { IntArray(w) }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = scaled.getPixel(x, y)
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
    val scaleFactor = 0.4f
    val w = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

    val values = DoubleArray(w * h)
    var idx = 0
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = scaled.getPixel(x, y)
        values[idx++] = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
      }
    }
    val mean = values.average()
    val variance = values.sumOf { (it - mean).pow(2) } / values.size
    return sqrt(variance)
  }

  private fun computeDarkChannelAverage(bitmap: Bitmap): Double {
    val scaleFactor = 0.4f
    val w = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

    val darkChannel = Array(h) { IntArray(w) }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = scaled.getPixel(x, y)
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
    return sum.toDouble() / count
  }

  private fun computeAverageBrightness(bitmap: Bitmap): Double {
    val scaleFactor = 0.4f
    val w = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

    var total = 0.0
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = scaled.getPixel(x, y)
        total += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
      }
    }
    return total / (w * h)
  }

  private fun computeTenengradScore(bitmap: Bitmap): Double {
    val scaleFactor = 0.4f
    val w = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)

    val gray = Array(h) { IntArray(w) }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = scaled.getPixel(x, y)
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

    return sum / count // average gradient magnitude
  }

}
