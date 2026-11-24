package com.miestudio.lumenlab

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DepthEngine(private val context: Context) {

    data class DepthMap(
        val width: Int,
        val height: Int,
        val values: FloatArray // 0.0 = fondo lejano, 1.0 = primer plano cercano
    ) {
        fun sampleBilinear(nx: Float, ny: Float): Float {
            val x = nx.coerceIn(0f, 1f) * (width - 1)
            val y = ny.coerceIn(0f, 1f) * (height - 1)
            val x0 = x.toInt().coerceIn(0, width - 1)
            val y0 = y.toInt().coerceIn(0, height - 1)
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val dx = x - x0
            val dy = y - y0

            val v00 = values[y0 * width + x0]
            val v10 = values[y0 * width + x1]
            val v01 = values[y1 * width + x0]
            val v11 = values[y1 * width + x1]

            return (v00 + (v10 - v00) * dx) * (1f - dy) + (v01 + (v11 - v01) * dx) * dy
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private val inputSize = 518

    private fun ensureSession(): OrtSession {
        session?.let { return it }
        val modelFile = File(context.cacheDir, "depth_v2_large_lumen.onnx")
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            context.assets.open("depth_model.onnx").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(4, Runtime.getRuntime().availableProcessors() / 2))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(modelFile.absolutePath, opts).also { session = it }
    }

    fun estimateDepth(source: Bitmap): DepthMap {
        val activeSession = ensureSession()
        val resized = Bitmap.createScaledBitmap(source, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (resized != source) resized.recycle()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val planeSize = inputSize * inputSize
        val inputData = FloatArray(3 * planeSize)

        for (i in 0 until planeSize) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            inputData[i] = (r - mean[0]) / std[0]
            inputData[planeSize + i] = (g - mean[1]) / std[1]
            inputData[2 * planeSize + i] = (b - mean[2]) / std[2]
        }

        val inputName = activeSession.inputNames.first()
        val shape = longArrayOf(1L, 3L, inputSize.toLong(), inputSize.toLong())

        OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape).use { tensor ->
            activeSession.run(mapOf(inputName to tensor)).use { result ->
                val outTensor = result[0] as OnnxTensor
                val fb = outTensor.floatBuffer
                val raw = FloatArray(fb.remaining())
                fb.get(raw)

                var minVal = Float.POSITIVE_INFINITY
                var maxVal = Float.NEGATIVE_INFINITY
                for (v in raw) {
                    if (v.isFinite()) {
                        if (v < minVal) minVal = v
                        if (v > maxVal) maxVal = v
                    }
                }
                val range = max(1e-5f, maxVal - minVal)
                val normalized = FloatArray(raw.size) { idx ->
                    val v = raw[idx]
                    if (v.isFinite()) ((v - minVal) / range).coerceIn(0f, 1f) else 0f
                }

                val outSide = sqrt(normalized.size.toDouble()).toInt().coerceAtLeast(1)
                return DepthMap(outSide, normalized.size / outSide, normalized)
            }
        }
    }

    fun refineForLighting(rawDepth: DepthMap, rgbPixels: IntArray, w: Int, h: Int, radius: Int = 10): FloatArray {
        val n = w * h
        val guide = FloatArray(n)
        val p = FloatArray(n)

        for (y in 0 until h) {
            val ny = y.toFloat() / max(1, h - 1)
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val c = rgbPixels[idx]
                guide[idx] = (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
                p[idx] = rawDepth.sampleBilinear(x.toFloat() / max(1, w - 1), ny)
            }
        }

        val meanI = boxFilter(guide, w, h, radius)
        val meanP = boxFilter(p, w, h, radius)
        val corrI = boxFilter(FloatArray(n) { guide[it] * guide[it] }, w, h, radius)
        val corrIP = boxFilter(FloatArray(n) { guide[it] * p[it] }, w, h, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = max(0f, corrI[i] - meanI[i] * meanI[i])
            val ai = (corrIP[i] - meanI[i] * meanP[i]) / (varI + 0.004f)
            a[i] = ai
            b[i] = meanP[i] - ai * meanI[i]
        }

        val meanA = boxFilter(a, w, h, radius)
        val meanB = boxFilter(b, w, h, radius)
        return FloatArray(n) { i -> (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f) }
    }

    private fun boxFilter(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val temp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var count = 0
            for (x in 0..min(radius, w - 1)) { sum += src[row + x]; count++ }
            for (x in 0 until w) {
                temp[row + x] = sum / count
                if (x + radius + 1 < w) { sum += src[row + x + radius + 1]; count++ }
                if (x - radius >= 0) { sum -= src[row + x - radius]; count-- }
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (y in 0..min(radius, h - 1)) { sum += temp[y * w + x]; count++ }
            for (y in 0 until h) {
                dst[y * w + x] = sum / count
                if (y + radius + 1 < h) { sum += temp[(y + radius + 1) * w + x]; count++ }
                if (y - radius >= 0) { sum -= temp[(y - radius) * w + x]; count-- }
            }
        }
        return dst
    }

    fun close() {
        session?.close()
        session = null
    }
}
