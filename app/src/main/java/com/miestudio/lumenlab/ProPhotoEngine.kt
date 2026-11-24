package com.miestudio.lumenlab

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object ProPhotoEngine {

    enum class ParamId(val label: String, val category: Int, val minVal: Int, val maxVal: Int) {
        // 0 = TONO
        LUZ_3D("Luz 3D", 0, -100, 100),
        EXPOSICION("Exposición", 0, -100, 100),
        CONTRASTE("Contraste", 0, -100, 100),
        REFLEJOS("Reflejos", 0, -100, 100),
        SOMBRAS("Sombras", 0, -100, 100),
        RANGO_CLARO("Rango claro", 0, -100, 100),
        RANGO_OSCURO("Rango oscuro", 0, -100, 100),

        // 1 = COLORES
        CALIDEZ("Calidez", 1, -100, 100),
        TINTA("Tinta", 1, -100, 100),
        SATURACION("Saturación", 1, -100, 100),
        VIBRACION("Vibración", 1, -100, 100),
        DESVANECER("Desvanecer", 1, 0, 100),

        // 2 = DETALLES
        CLARIDAD("Claridad", 2, -100, 100),
        NITIDEZ("Nitidez", 2, 0, 100),
        DISPERSION("Dispersión", 2, 0, 100),
        GRANO("Grano", 2, 0, 100),
        VINETA("Viñeta", 2, 0, 100)
    }

    data class FilterPreset(val id: String, val name: String)

    val presets = listOf(
        FilterPreset("original", "Original"),
        FilterPreset("flash90", "Direct Flash 90s"),
        FilterPreset("club", "Club Disposable"),
        FilterPreset("studio", "Studio Key 3D"),
        FilterPreset("leica_m9", "Leica M9 CCD"),
        FilterPreset("leica_m3", "Leica M3 Mono"),
        FilterPreset("portra", "Portra 400"),
        FilterPreset("cinestill", "CineStill 800T"),
        FilterPreset("vintage35", "Vintage 35mm"),
        FilterPreset("teal_orange", "Teal & Orange"),
        FilterPreset("bleach", "Bleach Bypass"),
        FilterPreset("golden3d", "Golden Hour 3D"),
        FilterPreset("pro_clean", "Pro Clean Auto")
    )

    data class ImageStats(
        val avgLuma: Float,
        val darkRatio: Float,
        val brightRatio: Float
    )

    fun analyzeImage(bitmap: Bitmap): ImageStats {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        var darks = 0
        var brights = 0
        val step = max(1, px.size / 15000)
        var count = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val l = (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
            sum += l
            if (l < 0.20f) darks++
            if (l > 0.85f) brights++
            count++
            i += step
        }
        return ImageStats(
            avgLuma = (sum / max(1, count)).toFloat(),
            darkRatio = darks.toFloat() / max(1, count),
            brightRatio = brights.toFloat() / max(1, count)
        )
    }

    /**
     * Calcula los parámetros óptimos adaptados al histograma de la foto actual
     * para que el resultado sea auténtico y nunca queme la piel.
     */
    fun computeAdaptiveParams(presetId: String, stats: ImageStats): MutableMap<ParamId, Int> {
        val p = ParamId.values().associateWith { 0 }.toMutableMap()
        if (presetId == "original") return p

        // Compensación inteligente de exposición según la luz real de la foto
        val autoExp = ((0.48f - stats.avgLuma) * 55f).toInt().coerceIn(-25, 35)
        val protectHigh = if (stats.brightRatio > 0.12f) -24 else -8

        when (presetId) {
            "flash90" -> {
                p[ParamId.LUZ_3D] = 78
                p[ParamId.EXPOSICION] = (autoExp + 10).coerceIn(-15, 38)
                p[ParamId.CONTRASTE] = 28
                p[ParamId.REFLEJOS] = (protectHigh + 14).coerceIn(-30, 22)
                p[ParamId.SOMBRAS] = -36
                p[ParamId.RANGO_CLARO] = 18
                p[ParamId.RANGO_OSCURO] = -26
                p[ParamId.CALIDEZ] = -10
                p[ParamId.TINTA] = 4
                p[ParamId.SATURACION] = -6
                p[ParamId.VIBRACION] = 14
                p[ParamId.CLARIDAD] = 22
                p[ParamId.NITIDEZ] = 26
                p[ParamId.DISPERSION] = 14
                p[ParamId.GRANO] = 18
                p[ParamId.VINETA] = 32
            }
            "club" -> {
                p[ParamId.LUZ_3D] = 88
                p[ParamId.EXPOSICION] = (autoExp + 6).coerceIn(-15, 35)
                p[ParamId.CONTRASTE] = 22
                p[ParamId.REFLEJOS] = 12
                p[ParamId.SOMBRAS] = -42
                p[ParamId.RANGO_OSCURO] = -18
                p[ParamId.CALIDEZ] = 14
                p[ParamId.TINTA] = -14
                p[ParamId.SATURACION] = 8
                p[ParamId.DESVANECER] = 18
                p[ParamId.CLARIDAD] = 14
                p[ParamId.NITIDEZ] = 16
                p[ParamId.DISPERSION] = 34
                p[ParamId.GRANO] = 36
                p[ParamId.VINETA] = 44
            }
            "studio" -> {
                p[ParamId.LUZ_3D] = 52
                p[ParamId.EXPOSICION] = (autoExp + 8).coerceIn(-12, 28)
                p[ParamId.CONTRASTE] = 14
                p[ParamId.REFLEJOS] = protectHigh
                p[ParamId.SOMBRAS] = 16
                p[ParamId.RANGO_CLARO] = 10
                p[ParamId.CALIDEZ] = 6
                p[ParamId.VIBRACION] = 16
                p[ParamId.CLARIDAD] = 12
                p[ParamId.NITIDEZ] = 20
                p[ParamId.VINETA] = 18
            }
            "leica_m9" -> {
                p[ParamId.LUZ_3D] = 18
                p[ParamId.EXPOSICION] = (autoExp - 4).coerceIn(-20, 20)
                p[ParamId.CONTRASTE] = 30
                p[ParamId.REFLEJOS] = protectHigh - 6
                p[ParamId.SOMBRAS] = -14
                p[ParamId.RANGO_OSCURO] = -18
                p[ParamId.CALIDEZ] = 8
                p[ParamId.TINTA] = 6
                p[ParamId.SATURACION] = 12
                p[ParamId.VIBRACION] = 22
                p[ParamId.CLARIDAD] = 28
                p[ParamId.NITIDEZ] = 24
                p[ParamId.GRANO] = 14
                p[ParamId.VINETA] = 24
            }
            "leica_m3" -> {
                p[ParamId.LUZ_3D] = 26
                p[ParamId.EXPOSICION] = autoExp
                p[ParamId.CONTRASTE] = 38
                p[ParamId.REFLEJOS] = -10
                p[ParamId.SOMBRAS] = -18
                p[ParamId.RANGO_CLARO] = 16
                p[ParamId.RANGO_OSCURO] = -28
                p[ParamId.SATURACION] = -100
                p[ParamId.CLARIDAD] = 32
                p[ParamId.NITIDEZ] = 24
                p[ParamId.GRANO] = 28
                p[ParamId.VINETA] = 28
            }
            "portra" -> {
                p[ParamId.LUZ_3D] = 14
                p[ParamId.EXPOSICION] = (autoExp + 10).coerceIn(-10, 30)
                p[ParamId.CONTRASTE] = -6
                p[ParamId.REFLEJOS] = -26
                p[ParamId.SOMBRAS] = 24
                p[ParamId.CALIDEZ] = 18
                p[ParamId.TINTA] = 8
                p[ParamId.SATURACION] = -8
                p[ParamId.VIBRACION] = 16
                p[ParamId.DESVANECER] = 14
                p[ParamId.DISPERSION] = 18
                p[ParamId.GRANO] = 22
            }
            "cinestill" -> {
                p[ParamId.LUZ_3D] = 32
                p[ParamId.EXPOSICION] = autoExp
                p[ParamId.CONTRASTE] = 20
                p[ParamId.REFLEJOS] = 10
                p[ParamId.SOMBRAS] = -16
                p[ParamId.CALIDEZ] = -24
                p[ParamId.TINTA] = -8
                p[ParamId.VIBRACION] = 20
                p[ParamId.DESVANECER] = 10
                p[ParamId.DISPERSION] = 68
                p[ParamId.GRANO] = 26
                p[ParamId.VINETA] = 26
            }
            "vintage35" -> {
                p[ParamId.EXPOSICION] = (autoExp + 4).coerceIn(-15, 25)
                p[ParamId.CONTRASTE] = 8
                p[ParamId.REFLEJOS] = -18
                p[ParamId.SOMBRAS] = 14
                p[ParamId.CALIDEZ] = 22
                p[ParamId.TINTA] = 6
                p[ParamId.SATURACION] = -16
                p[ParamId.DESVANECER] = 28
                p[ParamId.DISPERSION] = 22
                p[ParamId.GRANO] = 38
                p[ParamId.VINETA] = 30
            }
            "teal_orange" -> {
                p[ParamId.LUZ_3D] = 34
                p[ParamId.EXPOSICION] = autoExp
                p[ParamId.CONTRASTE] = 24
                p[ParamId.REFLEJOS] = -12
                p[ParamId.SOMBRAS] = -14
                p[ParamId.CALIDEZ] = 16
                p[ParamId.TINTA] = -12
                p[ParamId.SATURACION] = 10
                p[ParamId.VIBRACION] = 26
                p[ParamId.CLARIDAD] = 18
                p[ParamId.VINETA] = 22
            }
            "bleach" -> {
                p[ParamId.LUZ_3D] = 28
                p[ParamId.EXPOSICION] = (autoExp - 6).coerceIn(-25, 20)
                p[ParamId.CONTRASTE] = 48
                p[ParamId.REFLEJOS] = 14
                p[ParamId.SOMBRAS] = -28
                p[ParamId.RANGO_OSCURO] = -24
                p[ParamId.CALIDEZ] = -8
                p[ParamId.SATURACION] = -46
                p[ParamId.CLARIDAD] = 42
                p[ParamId.NITIDEZ] = 30
                p[ParamId.GRANO] = 24
                p[ParamId.VINETA] = 34
            }
            "golden3d" -> {
                p[ParamId.LUZ_3D] = 44
                p[ParamId.EXPOSICION] = (autoExp + 8).coerceIn(-12, 28)
                p[ParamId.CONTRASTE] = 14
                p[ParamId.REFLEJOS] = -14
                p[ParamId.SOMBRAS] = 18
                p[ParamId.CALIDEZ] = 34
                p[ParamId.TINTA] = 10
                p[ParamId.VIBRACION] = 24
                p[ParamId.DISPERSION] = 38
                p[ParamId.GRANO] = 12
                p[ParamId.VINETA] = 16
            }
            "pro_clean" -> {
                p[ParamId.LUZ_3D] = 16
                p[ParamId.EXPOSICION] = autoExp
                p[ParamId.CONTRASTE] = 12
                p[ParamId.REFLEJOS] = protectHigh
                p[ParamId.SOMBRAS] = if (stats.darkRatio > 0.25f) 24 else 10
                p[ParamId.VIBRACION] = 14
                p[ParamId.CLARIDAD] = 10
                p[ParamId.NITIDEZ] = 18
            }
        }
        return p
    }

    suspend fun render(
        source: Bitmap,
        depthMap: FloatArray?,
        params: Map<ParamId, Int>,
        presetId: String
    ): Bitmap = coroutineScope {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)

        val luz3d = (params[ParamId.LUZ_3D] ?: 0) / 100f
        val expEv = (params[ParamId.EXPOSICION] ?: 0) / 55f
        val contrast = (params[ParamId.CONTRASTE] ?: 0) / 100f
        val highlights = (params[ParamId.REFLEJOS] ?: 0) / 100f
        val shadows = (params[ParamId.SOMBRAS] ?: 0) / 100f
        val whites = (params[ParamId.RANGO_CLARO] ?: 0) / 100f
        val blacks = (params[ParamId.RANGO_OSCURO] ?: 0) / 100f

        val warmth = (params[ParamId.CALIDEZ] ?: 0) / 100f
        val tint = (params[ParamId.TINTA] ?: 0) / 100f
        val sat = (params[ParamId.SATURACION] ?: 0) / 100f
        val vib = (params[ParamId.VIBRACION] ?: 0) / 100f
        val fade = (params[ParamId.DESVANECER] ?: 0) / 100f

        val clarity = (params[ParamId.CLARIDAD] ?: 0) / 100f
        val sharpen = (params[ParamId.NITIDEZ] ?: 0) / 100f
        val halation = (params[ParamId.DISPERSION] ?: 0) / 100f
        val grain = (params[ParamId.GRANO] ?: 0) / 100f
        val vignette = (params[ParamId.VINETA] ?: 0) / 100f

        // Pre-cálculo de mapas espaciales solo si se usan (Claridad / Dispersión)
        val lumaBlur = if (abs(clarity) > 0.02f) computeLumaBlur(src, w, h, max(3, min(w, h) / 65)) else null
        val haloBlur = if (halation > 0.02f) computeHighlightBlur(src, w, h, max(4, min(w, h) / 55)) else null

        val out = IntArray(w * h)
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val rowsPerChunk = (h + cores - 1) / cores

        val expMult = 2f.pow(expEv)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val invMaxR2 = 1f / (cx * cx + cy * cy)
        val isTealOrange = (presetId == "teal_orange")
        val isClub = (presetId == "club")

        (0 until cores).map { chunk ->
            val yStart = chunk * rowsPerChunk
            val yEnd = min(h, yStart + rowsPerChunk)
            async(Dispatchers.Default) {
                for (y in yStart until yEnd) {
                    val row = y * w
                    val dy = y - cy
                    for (x in 0 until w) {
                        val idx = row + x
                        val c = src[idx]
                        var r = ((c shr 16) and 0xFF) / 255f
                        var g = ((c shr 8) and 0xFF) / 255f
                        var b = (c and 0xFF) / 255f

                        // 1. LUZ 3D / FLASH FÍSICO (Ley del Cuadrado Inverso sobre mapa 3D)
                        if (depthMap != null && abs(luz3d) > 0.01f) {
                            val d = depthMap[idx] // 1 = cerca, 0 = fondo
                            val falloff = (d - 0.48f) * 2.0f // [-0.96, +1.04]
                            val lightGain = 2f.pow(falloff * luz3d * 0.85f)
                            r *= lightGain
                            g *= lightGain
                            b *= lightGain

                            // Brillo especular sutil de flash en el primer plano
                            if (luz3d > 0.35f && d > 0.58f) {
                                val l = 0.299f * r + 0.587f * g + 0.114f * b
                                if (l > 0.55f) {
                                    val spec = (l - 0.55f) * (d - 0.58f) * luz3d * 0.42f
                                    r += spec; g += spec; b += spec
                                }
                            }
                        }

                        // 2. EXPOSICIÓN + PROTECCIÓN FILMIC DE ALTAS LUCES
                        r = softClip(r * expMult)
                        g = softClip(g * expMult)
                        b = softClip(b * expMult)

                        // 3. REFLEJOS, SOMBRAS, RANGO CLARO Y RANGO OSCURO
                        val luma0 = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                        val hiMask = smoothstep(0.45f, 0.98f, luma0)
                        val shMask = 1f - smoothstep(0.02f, 0.55f, luma0)
                        val toneDelta = (highlights * 0.32f * hiMask) +
                                (shadows * 0.34f * shMask) +
                                (whites * 0.22f * hiMask * hiMask) +
                                (blacks * 0.22f * shMask * shMask)
                        r = (r + toneDelta).coerceIn(0f, 1f)
                        g = (g + toneDelta).coerceIn(0f, 1f)
                        b = (b + toneDelta).coerceIn(0f, 1f)

                        // 4. CONTRASTE (Curva en S fílmica)
                        if (abs(contrast) > 0.01f) {
                            val k = 1f + contrast * 0.95f
                            r = ((r - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                            g = ((g - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                            b = ((b - 0.5f) * k + 0.5f).coerceIn(0f, 1f)
                        }

                        // 5. CLARIDAD (Micro-contraste local)
                        if (lumaBlur != null) {
                            val lCur = 0.299f * r + 0.587f * g + 0.114f * b
                            val detail = (lCur - lumaBlur[idx]) * clarity * 0.75f
                            r = (r + detail).coerceIn(0f, 1f)
                            g = (g + detail).coerceIn(0f, 1f)
                            b = (b + detail).coerceIn(0f, 1f)
                        }

                        // 6. NITIDEZ (Unsharp mask 3x3 rápido)
                        if (sharpen > 0.02f && x > 0 && x < w - 1 && y > 0 && y < h - 1) {
                            val n1 = src[idx - 1]; val n2 = src[idx + 1]
                            val n3 = src[idx - w]; val n4 = src[idx + w]
                            val avgL = (lumaOf(n1) + lumaOf(n2) + lumaOf(n3) + lumaOf(n4)) * 0.25f
                            val lCur = lumaOf(c)
                            val edge = (lCur - avgL) * sharpen * 0.95f
                            r = (r + edge).coerceIn(0f, 1f)
                            g = (g + edge).coerceIn(0f, 1f)
                            b = (b + edge).coerceIn(0f, 1f)
                        }

                        // 7. CALIDEZ, TINTA Y SPLIT-TONING (Teal & Orange / Club)
                        if (abs(warmth) > 0.01f || abs(tint) > 0.01f) {
                            r = (r + warmth * 0.11f + tint * 0.05f).coerceIn(0f, 1f)
                            g = (g - tint * 0.06f).coerceIn(0f, 1f)
                            b = (b - warmth * 0.11f + tint * 0.04f).coerceIn(0f, 1f)
                        }
                        if (isTealOrange) {
                            val l = 0.299f * r + 0.587f * g + 0.114f * b
                            val sh = 1f - smoothstep(0.15f, 0.58f, l)
                            val hi = smoothstep(0.45f, 0.90f, l)
                            r = (r - sh * 0.07f + hi * 0.07f).coerceIn(0f, 1f)
                            g = (g + sh * 0.02f + hi * 0.02f).coerceIn(0f, 1f)
                            b = (b + sh * 0.09f - hi * 0.06f).coerceIn(0f, 1f)
                        } else if (isClub) {
                            val l = 0.299f * r + 0.587f * g + 0.114f * b
                            val sh = 1f - smoothstep(0.05f, 0.42f, l)
                            g = (g + sh * 0.045f).coerceIn(0f, 1f)
                            b = (b + sh * 0.035f).coerceIn(0f, 1f)
                        }

                        // 8. SATURACIÓN Y VIBRACIÓN (con protección de piel)
                        if (abs(sat) > 0.01f || abs(vib) > 0.01f) {
                            val l = 0.299f * r + 0.587f * g + 0.114f * b
                            val maxC = max(r, max(g, b))
                            val minC = min(r, min(g, b))
                            val curSat = maxC - minC
                            val skinProtect = if (r > g && g > b && (r - b) > 0.06f) 0.45f else 1.0f
                            val satGain = (1f + sat) * (1f + vib * (1f - curSat) * skinProtect * 0.85f)
                            r = (l + (r - l) * satGain).coerceIn(0f, 1f)
                            g = (l + (g - l) * satGain).coerceIn(0f, 1f)
                            b = (l + (b - l) * satGain).coerceIn(0f, 1f)
                        }

                        // 9. DISPERSIÓN / HALATION (Halo cálido real sobre luces altas)
                        if (haloBlur != null) {
                            val hVal = haloBlur[idx] * halation
                            r = (r + hVal * 0.85f).coerceIn(0f, 1f)
                            g = (g + hVal * 0.32f).coerceIn(0f, 1f)
                            b = (b + hVal * 0.14f).coerceIn(0f, 1f)
                        }

                        // 10. DESVANECER (Negros mate de película)
                        if (fade > 0.01f) {
                            val lift = fade * 0.14f
                            r = lift + r * (1f - lift * 0.7f)
                            g = lift + g * (1f - lift * 0.7f)
                            b = lift + b * (1f - lift * 0.7f)
                        }

                        // 11. VIÑETA ÓPTICA
                        if (vignette > 0.01f) {
                            val dx = x - cx
                            val r2 = (dx * dx + dy * dy) * invMaxR2
                            val vig = (1f - vignette * 0.65f * r2 * r2).coerceIn(0.28f, 1f)
                            r *= vig; g *= vig; b *= vig
                        }

                        // 12. GRANO 35MM ORGÁNICO (más visible en medios tonos)
                        if (grain > 0.01f) {
                            val l = 0.299f * r + 0.587f * g + 0.114f * b
                            val midWeight = 1f - abs(l - 0.5f) * 1.4f
                            val hash = ((idx * 374761393) xor (y * 668265263))
                            val rnd = (((hash xor (hash ushr 13)) * 1274126177) and 0xFF) / 255f - 0.5f
                            val gAmt = rnd * grain * 0.16f * max(0.2f, midWeight)
                            r = (r + gAmt).coerceIn(0f, 1f)
                            g = (g + gAmt).coerceIn(0f, 1f)
                            b = (b + gAmt).coerceIn(0f, 1f)
                        }

                        out[idx] = (0xFF shl 24) or
                                ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
                                ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
                                (b * 255f).toInt().coerceIn(0, 255)
                    }
                }
            }
        }.awaitAll()

        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    private fun softClip(v: Float): Float =
        if (v <= 0.85f) v.coerceAtLeast(0f) else (0.85f + 0.15f * (1f - 1f / (1f + (v - 0.85f) * 3.5f))).coerceAtMost(1f)

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / max(1e-5f, e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lumaOf(c: Int): Float =
        (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f

    private fun computeLumaBlur(src: IntArray, w: Int, h: Int, radius: Int): FloatArray {
        val luma = FloatArray(src.size) { lumaOf(src[it]) }
        return fastBoxBlur(luma, w, h, radius)
    }

    private fun computeHighlightBlur(src: IntArray, w: Int, h: Int, radius: Int): FloatArray {
        val hi = FloatArray(src.size) { i ->
            val l = lumaOf(src[i])
            if (l > 0.76f) (l - 0.76f) / 0.24f else 0f
        }
        return fastBoxBlur(hi, w, h, radius)
    }

    private fun fastBoxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f; var cnt = 0
            for (x in 0..min(radius, w - 1)) { sum += src[row + x]; cnt++ }
            for (x in 0 until w) {
                tmp[row + x] = sum / cnt
                if (x + radius + 1 < w) { sum += src[row + x + radius + 1]; cnt++ }
                if (x - radius >= 0) { sum -= src[row + x - radius]; cnt-- }
            }
        }
        for (x in 0 until w) {
            var sum = 0f; var cnt = 0
            for (y in 0..min(radius, h - 1)) { sum += tmp[y * w + x]; cnt++ }
            for (y in 0 until h) {
                dst[y * w + x] = sum / cnt
                if (y + radius + 1 < h) { sum += tmp[(y + radius + 1) * w + x]; cnt++ }
                if (y - radius >= 0) { sum -= tmp[(y - radius) * w + x]; cnt-- }
            }
        }
        return dst
    }
}
