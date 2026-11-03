package com.msp1974.vacompanion.device

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import kotlin.math.abs

enum class MotionDetectionMode {
    PIXEL_DIFF,
    FACE_DETECTION
}

data class MotionResult(
    val hasMotion: Boolean,
    val boundingBoxes: List<RectF>,
    val motionIntensity: Float, // 0.0 to 1.0
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
)

class MotionDetectionEngine(
    private val detectionWidth: Int = 160,
    private val detectionHeight: Int = 120
) {
    companion object {
        const val MOTION_INTERVAL_TIMEOUT = 5000
        init {
            suppressMLKitSpam()
        }

        private fun suppressMLKitSpam() {
            try {
                // Reinforce log suppression before ML Kit is used in this class
                android.system.Os.setenv("TFLITE_XNNPACK_DELEGATE_NO_LOGGING", "1", true)
                android.system.Os.setenv("XNNPACK_LOG_LEVEL", "0", true)
                android.system.Os.setenv("TFLITE_LOG_LEVEL", "0", true)
                android.system.Os.setenv("TF_CPP_MIN_LOG_LEVEL", "3", true)

                // Native ML Kit tags often check these environment variables as a fallback for system properties
                android.system.Os.setenv("log.tag.FaceDetectorV2Jni", "ERROR", true)
                android.system.Os.setenv("log.tag.ThickFaceDetector", "ERROR", true)
                android.system.Os.setenv("log.tag.Vision", "ERROR", true)
            } catch (_: Exception) {}
        }
    }

    private var backgroundModel: IntArray? = null
    private var alpha = 0.05f // Learning rate for background model
    private var motionThreshold = 25 // Luma difference threshold
    private var minBlobSize = 64 // Minimum pixels in a block to consider as motion

    var detectorMode = MotionDetectionMode.PIXEL_DIFF

    private val _motionFlow = MutableSharedFlow<MotionResult>(replay = 0)
    val motionFlow = _motionFlow.asSharedFlow()

    fun stepRange(value: Int, min: Int, max: Int, steps: Int = 100, invert: Boolean = true): Int {
        return stepRange(value, min.toFloat(), max.toFloat(), steps, invert).toInt()
    }

    fun stepRange(value: Int, min: Float, max: Float, steps: Int = 100, invert: Boolean = true): Float {
        val step = (max-min) / steps
        return if (invert) {
            max - (step * value)
        } else {
            min + (step * value)
        }
    }

    fun setSensitivity(sensitivity: Int) {
        Timber.i("Motion sensitivity updated to $sensitivity")
        // Higher sensitivity = lower threshold
        // Map 0-100 to threshold 50-5
        motionThreshold = stepRange(sensitivity, 5, 50)


        // Also adjust min blob size based on sensitivity
        // 0 -> 64 pixels, 100 -> 4 pixels
        minBlobSize = stepRange(sensitivity, 4, 64)


        // Adjust background learning rate
        // Higher sensitivity = slower learning (don't absorb slow movement too fast)
        // 0 -> 0.2, 100 -> 0.1
        alpha = stepRange(sensitivity, 0.1f, 0.2f)

    }

    fun reset() {
        backgroundModel = null
    }

    fun close() {}

    /** The camera's frames hold a picture of the transposed shape stretched into the frame (see
     *  [com.msp1974.vacompanion.device.camera.CameraOrientation.isFrameSqueezed]). */
    @Volatile var squeezedFrames = false

    // Odysseus fork: face detection used Google ML Kit, which reports usage to Google. ML Kit is
    // not linked into this fork, so FACE_DETECTION mode reports no faces; PIXEL_DIFF works.
    @Suppress("UNUSED_PARAMETER")
    suspend fun processImageProxy(imageProxy: ImageProxy) {
        if (detectorMode == MotionDetectionMode.FACE_DETECTION) {
            Timber.w("Face detection is not available in this build")
        }
    }

    @Suppress("unused")
    /**
     * The frame's brightness as a greyscale bitmap with its shape put right, for a camera whose
     * frames are squeezed. A face detector does poorly on faces stretched that far.
     */
    private fun unsqueezedLuma(imageProxy: ImageProxy): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val luma = buffer.get(row * rowStride + col).toInt() and 0xFF
                pixels[row * width + col] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        val frame = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        // The picture is height:width, so it is as tall as the frame and (height / width) as wide.
        val trueWidth = (height.toLong() * height / width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, trueWidth, height, true).also { if (it !== frame) frame.recycle() }
    }

    suspend fun processFrame(luma: IntArray, frameWidth: Int, frameHeight: Int, rotation: Int = 0) {
        // 1. Resize/Downsample for performance if needed
        // For simplicity, we assume the input is already low resolution or we process it as is
        // but given the requirements, we'll implement a fast downsampling if it's too big
        
        val workLuma: IntArray
        val workW: Int
        val workH: Int
        
        if (frameWidth > detectionWidth || frameHeight > detectionHeight) {
            workW = detectionWidth
            workH = detectionHeight
            workLuma = downsample(luma, frameWidth, frameHeight, workW, workH)
        } else {
            workW = frameWidth
            workH = frameHeight
            workLuma = luma
        }

        // 2. Initialize or Update Background Model
        if (backgroundModel == null || backgroundModel!!.size != workLuma.size) {
            backgroundModel = workLuma.copyOf()
            return
        }

        val bg = backgroundModel!!
        val diffMap = BooleanArray(workLuma.size)
        var motionCount = 0

        for (i in workLuma.indices) {
            val currentVal = workLuma[i]
            val bgVal = bg[i]
            val diff = abs(currentVal - bgVal)

            if (diff > motionThreshold) {
                diffMap[i] = true
                motionCount++
            }

            // Update background slowly
            bg[i] = ((1f - alpha) * bgVal + alpha * currentVal).toInt()
        }

        // 3. Group detected pixels into bounding boxes (Blob Detection)
        val boxes = if (motionCount > 0) {
            findBoundingBoxes(diffMap, workW, workH)
        } else {
            emptyList()
        }

        val intensity = motionCount.toFloat() / workLuma.size
        val hasMotion = boxes.isNotEmpty()

        _motionFlow.emit(MotionResult(hasMotion, boxes, intensity, workW, workH, rotation))
    }

    private fun downsample(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val srcX = (x * xRatio).toInt()
                val srcY = (y * yRatio).toInt()
                dst[y * dstW + x] = src[srcY * srcW + srcX]
            }
        }
        return dst
    }

    private fun findBoundingBoxes(diffMap: BooleanArray, w: Int, h: Int): List<RectF> {
        val visited = BooleanArray(diffMap.size)
        val boxes = mutableListOf<RectF>()
        
        // Use a grid-based approach for speed on low-power hardware
        val gridSize = 8
        val gridW = w / gridSize
        val gridH = h / gridSize
        
        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val idx = (gy * gridSize) * w + (gx * gridSize)
                if (diffMap[idx] && !visited[idx]) {
                    // Start a "blob" search
                    val (rect, count) = floodFillRect(diffMap, visited, gx * gridSize, gy * gridSize, w, h)
                    if (count >= minBlobSize) {
                         // Convert to normalized coordinates 0..1
                         boxes.add(RectF(
                             rect.left / w,
                             rect.top / h,
                             rect.right / w,
                             rect.bottom / h
                         ))
                    }
                }
            }
        }
        
        // Merge overlapping or very close boxes to simplify
        return mergeBoxes(boxes)
    }

    private fun floodFillRect(diffMap: BooleanArray, visited: BooleanArray, startX: Int, startY: Int, w: Int, h: Int): Pair<RectF, Int> {
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY
        
        val stack = mutableListOf<Pair<Int, Int>>()
        stack.add(startX to startY)
        visited[startY * w + startX] = true
        
        var count = 0
        val maxPixels = 500 // Limit search size for performance

        while (stack.isNotEmpty() && count < maxPixels) {
            val (cx, cy) = stack.removeAt(stack.size - 1)
            count++
            
            if (cx < minX) minX = cx
            if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy
            if (cy > maxY) maxY = cy
            
            // Check neighbors in a sparse way for performance
            val step = 4
            val neighbors = listOf(cx to cy - step, cx to cy + step, cx - step to cy, cx + step to cy)
            
            for ((nx, ny) in neighbors) {
                if (nx in 0 until w && ny in 0 until h) {
                    val nIdx = ny * w + nx
                    if (diffMap[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        stack.add(nx to ny)
                    }
                }
            }
        }
        
        return RectF(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat()) to count
    }

    private fun mergeBoxes(boxes: List<RectF>): List<RectF> {
        if (boxes.size < 2) return boxes
        
        val result = mutableListOf<RectF>()
        val skip = BooleanArray(boxes.size)
        
        for (i in boxes.indices) {
            if (skip[i]) continue
            val current = RectF(boxes[i])
            
            for (j in i + 1 until boxes.size) {
                if (skip[j]) continue
                
                // If boxes are close or overlap, merge
                val buffer = 0.05f
                val expanded = RectF(current.left - buffer, current.top - buffer, current.right + buffer, current.bottom + buffer)
                
                if (expanded.intersect(boxes[j])) {
                    current.union(boxes[j])
                    skip[j] = true
                }
            }
            result.add(current)
        }
        return result
    }
}
