// blur_checker/android/src/main/kotlin/com/example/blur_checker/BlurCheckerPlugin.kt
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
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.*

// Keep LaplacianResult data class
data class LaplacianResult(val stdDev: Double, val edgeCount: Int)

object BlurCheckerUtils {
  // --- Constants ---
  private const val PROCESSING_SCALE_FACTOR = 0.15

  // ** TUNING AREA 1: Solid Color Check **
  private const val SOLID_RGB_STD_DEV_THRESHOLD = 15.0
  private const val SOLID_SAMPLE_RATIO = 0.2
  private const val SOLID_MIN_SAMPLES = 200

  // Laplacian Kernel
  private const val LAPLACIAN_KERNEL_SIZE = 3
  private val LAPLACIAN_KERNEL = arrayOf(
    intArrayOf(0, -1, 0),
    intArrayOf(-1, 4, -1),
    intArrayOf(0, -1, 0)
  )

  // --- Public Score Computation ---
  fun computeLensDirtyScore(originalBitmap: Bitmap): Double {
    var scaledBitmap: Bitmap? = null
    var blurredBitmap: Bitmap? = null // Bitmap for after pre-filtering
    try {
      scaledBitmap = scaleBitmap(originalBitmap, PROCESSING_SCALE_FACTOR)
      val width = scaledBitmap.width; val height = scaledBitmap.height
      val numPixels = width * height
      if (width < 3 || height < 3 || numPixels == 0) return 0.0 // Need 3x3 for Laplacian/Blur
      var pixels = IntArray(numPixels)
      scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

      // STEP 1: Solid Color Check (on original scaled pixels)
      if (isMostlySolidRgb(pixels, numPixels, SOLID_RGB_STD_DEV_THRESHOLD, SOLID_SAMPLE_RATIO)) {
        return 0.0
      }

      // --- Not Solid ---

      // STEP 2: Pre-filter the image to reduce noise
      // Create a mutable copy to apply blur to
      blurredBitmap = scaledBitmap.copy(Bitmap.Config.ARGB_8888, true)
      // Pass width and height, as numPixels isn't needed inside anymore
      applyBoxBlur(blurredBitmap, width, height) // Apply simple 3x3 Box Blur in-place

      // Get pixels FROM THE BLURRED BITMAP
      pixels = IntArray(numPixels) // Re-use variable
      blurredBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

      // STEP 3: Calculate Metrics on Pre-filtered Image
      val luminance = DoubleArray(numPixels) { i -> val p = pixels[i]; (0.2126*Color.red(p) + 0.7152*Color.green(p) + 0.0722*Color.blue(p))/255.0 }
      val lapResult = computeLaplacianResult(luminance, width, height) // Use Laplacian again
      val contrastStd = computeGlobalContrastStdDev(luminance)

      // STEP 4: Normalize Scores
      // ** TUNING AREA 2: Normalization Factors **
      val focusScore = (lapResult.stdDev / 30.0).coerceIn(0.0, 1.0) // Moderate normalization
      val contrastScore = (contrastStd * 255.0 / 40.0).coerceIn(0.0, 1.0) // Moderate normalization

      // STEP 5: Logic Tree
      // ** TUNING AREA 3: Thresholds **
      val lowContrastThreshold = 0.10
      val sharpnessThreshold = 0.40 // Threshold for focusScore

      // Check 1: Low Contrast (Likely Solid that failed Step 1, now even lower contrast)
      if (contrastScore < lowContrastThreshold) {
        return 0.2
      }
      // Check 2: Sharp Image (Focus score still high after slight pre-blur)
      else if (focusScore > sharpnessThreshold) {
        return 0.1
      }
      // Check 3: Else (Likely Blurry)
      else {
        // Score based purely on lack of focus (post-filtering)
        val finalScore = (1.0 - focusScore)
        return finalScore.coerceIn(0.0, 1.0)
      }

    } catch (e: Exception) {
      Log.e("LensCheck", "Error during score computation: ${e.message}", e)
      return 0.5
    } finally {
      // Recycle both bitmaps if they were created
      scaledBitmap?.recycle()
      blurredBitmap?.recycle()
    }
  }

  // --- Helper Functions ---

  // Box blur implementation (simple 3x3 average) - applies IN PLACE
  // Takes width and height as arguments now
  private fun applyBoxBlur(bitmap: Bitmap, width: Int, height: Int) {
    // val width = bitmap.width // Get from arguments
    // val height = bitmap.height // Get from arguments
    if (width < 3 || height < 3) return // Cannot apply 3x3 kernel

    val numPixelsTotal = width * height // Calculate total pixels inside function if needed
    val pixels = IntArray(numPixelsTotal)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val blurredPixels = IntArray(pixels.size)

    // Apply blur to inner pixels
    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0
        for (ky in -1..1) {
          for (kx in -1..1) {
            val p = pixels[(y + ky) * width + (x + kx)]
            aSum += Color.alpha(p)
            rSum += Color.red(p)
            gSum += Color.green(p)
            bSum += Color.blue(p)
          }
        }
        // Average the sums (integer division)
        blurredPixels[y * width + x] = Color.argb(aSum / 9, rSum / 9, gSum / 9, bSum / 9)
      }
    }

    // Handle borders (simple copy - could be improved with edge handling)
    for (y in 0 until height) {
      if (width > 0) {
        blurredPixels[y * width + 0] = pixels[y * width + 0] // Left edge
        if (width > 1) {
          blurredPixels[y * width + (width - 1)] = pixels[y * width + (width - 1)] // Right edge
        }
      }
    }
    for (x in 1 until width - 1) {
      if (height > 0) {
        blurredPixels[0 * width + x] = pixels[0 * width + x] // Top edge
        if (height > 1) {
          blurredPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x] // Bottom edge
        }
      }
    }
    // Handle corners explicitly (simplest way to ensure they are copied)
    if (width > 0 && height > 0) {
      blurredPixels[0] = pixels[0] // Top-Left
      if (width > 1) blurredPixels[width - 1] = pixels[width - 1] // Top-Right
      if (height > 1) blurredPixels[(height - 1) * width] = pixels[(height - 1) * width] // Bottom-Left
      // *** CORRECTED LINE ***
      if (width > 1 && height > 1) blurredPixels[width * height - 1] = pixels[width * height - 1] // Bottom-Right
    }


    // Set the blurred pixels back onto the original bitmap
    bitmap.setPixels(blurredPixels, 0, width, 0, 0, width, height)
  }

  private fun scaleBitmap(bitmap: Bitmap, factor: Double): Bitmap {
    val newWidth = (bitmap.width * factor).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * factor).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
  }

  private fun isMostlySolidRgb(pixels: IntArray, numPixels: Int, stdDevThreshold: Double, sampleRatio: Double): Boolean {
    if (numPixels <= 1) return true
    val numSamples = (numPixels * sampleRatio).toInt().coerceAtLeast(SOLID_MIN_SAMPLES).coerceAtMost(numPixels)
    if (numSamples < 2) return true
    val samplesR = DoubleArray(numSamples); val samplesG = DoubleArray(numSamples); val samplesB = DoubleArray(numSamples)
    val random = java.util.Random()
    var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
    for (i in 0 until numSamples) {
      val index = random.nextInt(numPixels); val p = pixels[index]
      val r = Color.red(p).toDouble(); val g = Color.green(p).toDouble(); val b = Color.blue(p).toDouble()
      samplesR[i] = r; samplesG[i] = g; samplesB[i] = b
      sumR += r; sumG += g; sumB += b
    }
    val meanR = sumR / numSamples; val meanG = sumG / numSamples; val meanB = sumB / numSamples
    val varianceR = samplesR.sumOf { (it - meanR).pow(2) } / numSamples
    val varianceG = samplesG.sumOf { (it - meanG).pow(2) } / numSamples
    val varianceB = samplesB.sumOf { (it - meanB).pow(2) } / numSamples
    val stdDevR = sqrt(varianceR); val stdDevG = sqrt(varianceG); val stdDevB = sqrt(varianceB)
    return stdDevR < stdDevThreshold && stdDevG < stdDevThreshold && stdDevB < stdDevThreshold
  }

  private fun computeLaplacianResult(luminance: DoubleArray, width: Int, height: Int): LaplacianResult {
    val grayInt = IntArray(luminance.size) { (luminance[it] * 255).toInt() }
    var sum = 0.0; var sumSq = 0.0; var count = 0; var strongEdgeCount = 0
    val edgeThreshold = 20 // Keep this edge counting, might be useful later
    val kernelSize = LAPLACIAN_KERNEL_SIZE
    val kernelRadius = kernelSize / 2
    for (y in kernelRadius until height - kernelRadius) {
      for (x in kernelRadius until width - kernelRadius) {
        var laplacianValue = 0.0
        for (ky in -kernelRadius..kernelRadius) {
          for (kx in -kernelRadius..kernelRadius) {
            laplacianValue += grayInt[(y + ky) * width + (x + kx)] * LAPLACIAN_KERNEL[ky + kernelRadius][kx + kernelRadius]
          }
        }
        sum += laplacianValue; sumSq += laplacianValue * laplacianValue
        if (kotlin.math.abs(laplacianValue) > edgeThreshold) strongEdgeCount++ // Use kotlin.math.abs
        count++
      }
    }
    if (count == 0) return LaplacianResult(0.0, 0)
    val mean = sum / count
    val variance = (sumSq / count) - (mean * mean)
    return LaplacianResult(sqrt(variance.coerceAtLeast(0.0)), strongEdgeCount)
  }

  private fun computeGlobalContrastStdDev(luminance: DoubleArray): Double {
    if (luminance.isEmpty()) return 0.0
    val mean = luminance.average()
    val variance = luminance.sumOf { (it - mean).pow(2) } / luminance.size
    return sqrt(variance.coerceAtLeast(0.0))
  }
  // Removed computeAverageBrightness, computeDarkChannelAverage, convertToGrayscale, computeMeanGradientMagnitude

} // End of BlurCheckerUtils object


// --- Flutter Plugin Class ---
class BlurCheckerPlugin : FlutterPlugin, MethodCallHandler {
  private lateinit var channel: MethodChannel
  // Use Default dispatcher for CPU-bound work like image processing
  private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "blur_checker_native")
    channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getLensDirtyScore", "isLensDirty" -> {
        val path = call.argument<String>("path")
        if (path == null) {
          result.error("ARGUMENT_ERROR", "Path argument is required", null)
          return
        }
        val isCheck = call.method == "isLensDirty"
        // Default threshold based on expected blurry score range (1.0 - focusScore)
        val threshold = call.argument<Double>("threshold") ?: 0.7

        coroutineScope.launch { // Launch on the scope's dispatcher (Default)
          try {
            val score = processImage(path) // processImage uses Dispatchers.IO internally
            // Switch back to main thread to return result to Flutter
            withContext(Dispatchers.Main) {
              when (score) {
                null -> result.error("PROCESSING_ERROR", "Failed to process image", null)
                else -> {
                  if (isCheck) { result.success(score >= threshold) }
                  else { result.success(score) }
                }
              }
            }
          } catch (e: Exception) {
            Log.e("BlurCheckerPlugin", "Error in Coroutine: ${e.message}", e)
            withContext(Dispatchers.Main) {
              result.error("COROUTINE_ERROR", "Exception during processing: ${e.message}", null)
            }
          }
        }
      }
      else -> result.notImplemented()
    }
  }

  // This suspend function runs the image processing off the main thread
  private suspend fun processImage(path: String): Double? = withContext(Dispatchers.IO) { // Use IO dispatcher for file access
    val file = File(path)
    if (!file.exists()) { Log.e("BlurCheckerPlugin", "File does not exist: $path"); return@withContext null }
    val absolutePath = file.absolutePath
    var bitmap: Bitmap? = null
    try {
      // Decode with sampling using target 640x640
      bitmap = decodeSampledBitmap(absolutePath, 640, 640)
      if (bitmap == null) { Log.e("BlurCheckerPlugin", "Failed to decode bitmap: $path"); return@withContext null }
      // Perform the core score computation (CPU-bound, but already off main thread)
      val score = BlurCheckerUtils.computeLensDirtyScore(bitmap)
      return@withContext score
    } catch (e: Exception) { Log.e("BlurCheckerPlugin", "Error processing image $path: ${e.message}", e); return@withContext null }
    finally { bitmap?.recycle() } // Ensure bitmap is recycled
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    // Cancel all coroutines started by this scope when the engine detaches
    coroutineScope.cancel()
  }

  // --- Bitmap Decoding Helpers ---
  private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    return try {
      // First decode with inJustDecodeBounds=true to check dimensions
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(path, options)

      // Calculate inSampleSize
      options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

      // Decode bitmap with inSampleSize set
      options.inJustDecodeBounds = false
      options.inPreferredConfig = Bitmap.Config.ARGB_8888 // Prefer ARGB_8888 for quality
      BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) { Log.e("BlurCheckerPlugin", "Decode Error: $path - ${e.message}"); null }
    catch (oome: OutOfMemoryError) { Log.e("BlurCheckerPlugin", "OOM Error decoding bitmap: $path"); null } // Catch OOM specifically
  }

  private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    // Raw height and width of image
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
      val halfHeight = height / 2; val halfWidth = width / 2
      // Calculate the largest inSampleSize value that is a power of 2 and keeps both
      // height and width larger than the requested height and width.
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2
      }
      // This additional loop helps RARE cases where the image is VERY large
      while ((width / inSampleSize) > reqWidth * 2 || (height / inSampleSize) > reqHeight * 2) {
        inSampleSize *= 2;
      }
    }
    return inSampleSize
  }
} // End of BlurCheckerPlugin class