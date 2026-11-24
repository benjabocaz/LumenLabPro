package com.miestudio.lumenlab

import android.content.ContentValues
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var depthEngine: DepthEngine
    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var cardsContainer: LinearLayout
    private lateinit var sliderBox: LinearLayout
    private lateinit var sliderTitle: TextView
    private lateinit var sliderValue: TextView
    private lateinit var paramSeek: SeekBar
    private lateinit var exportBtn: TextView

    private val tabButtons = mutableListOf<TextView>()
    private var activeTab = 0 // 0=Filtros, 1=Tono, 2=Colores, 3=Detalles

    private var originalFull: Bitmap? = null
    private var previewBase: Bitmap? = null
    private var renderedPreview: Bitmap? = null
    private var rawDepth: DepthEngine.DepthMap? = null
    private var previewDepth: FloatArray? = null
    private var imageStats: ProPhotoEngine.ImageStats? = null

    private var activePresetId = "original"
    private var currentParams = ProPhotoEngine.ParamId.values().associateWith { 0 }.toMutableMap()
    private var selectedParam: ProPhotoEngine.ParamId? = null
    private var renderJob: Job? = null

    private val colorBg = Color.parseColor("#000000")
    private val colorCard = Color.parseColor("#1E1E20")
    private val colorCardSelected = Color.parseColor("#332B1A")
    private val colorAmber = Color.parseColor("#FFB300")
    private val colorText = Color.parseColor("#F2F2F2")
    private val colorSub = Color.parseColor("#9A9AA0")

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) loadPhoto(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        depthEngine = DepthEngine(this)
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg
        setContentView(buildUi())
        refreshCards()
    }

    override fun onDestroy() {
        super.onDestroy()
        depthEngine.close()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun roundedBg(fill: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // Barra superior
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val openBtn = makePill("ABRIR", colorCard, colorText).apply {
            setOnClickListener {
                pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        topBar.addView(openBtn)

        statusText = TextView(this).apply {
            text = "LumenLab Pro"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(statusText)

        val compareBtn = makePill("ORIGINAL", colorCard, colorText).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
            ).apply { marginEnd = dp(6) }
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { previewBase?.let { imageView.setImageBitmap(it) }; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { renderedPreview?.let { imageView.setImageBitmap(it) }; true }
                    else -> false
                }
            }
        }
        topBar.addView(compareBtn)

        exportBtn = makePill("GUARDAR", colorAmber, Color.BLACK).apply {
            setOnClickListener { exportFullRes() }
        }
        topBar.addView(exportBtn)
        root.addView(topBar)

        // Visor de foto
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(imageView)

        // Slider de precisión (se muestra al tocar cualquier parámetro)
        sliderBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            visibility = View.GONE
        }
        val sliderHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sliderTitle = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorText)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sliderValue = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAmber)
        }
        sliderHeader.addView(sliderTitle)
        sliderHeader.addView(sliderValue)
        sliderBox.addView(sliderHeader)

        paramSeek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = selectedParam ?: return
                    val realVal = p.minVal + progress
                    sliderValue.text = formatVal(realVal)
                    if (fromUser) {
                        currentParams[p] = realVal
                        refreshCards(keepSlider = true)
                        scheduleRender(immediate = false)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { scheduleRender(immediate = true) }
            })
        }
        sliderBox.addView(paramSeek)
        root.addView(sliderBox)

        // Carrusel horizontal estilo Xiaomi (tarjetas oscuras redondeadas)
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(cardsContainer)
        root.addView(scroll)

        // Barra inferior de pestañas estilo Xiaomi (Filtros / Tono / Colores / Detalles)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        val tabNames = listOf("Filtros", "Tono", "Colores", "Detalles")
        tabNames.forEachIndexed { idx, title ->
            val btn = TextView(this).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                ).apply { marginEnd = dp(6) }
                setOnClickListener {
                    activeTab = idx
                    if (idx == 0) {
                        selectedParam = null
                        sliderBox.visibility = View.GONE
                    }
                    updateTabStyles()
                    refreshCards()
                }
            }
            tabButtons.add(btn)
            tabBar.addView(btn)
        }
        root.addView(tabBar)
        updateTabStyles()

        return root
    }

    private fun makePill(label: String, bg: Int, fg: Int): TextView = TextView(this).apply {
        text = label
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = roundedBg(bg, 18)
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
    }

    private fun updateTabStyles() {
        tabButtons.forEachIndexed { idx, btn ->
            val active = (idx == activeTab)
            btn.background = if (active) roundedBg(colorAmber, 18) else null
            btn.setTextColor(if (active) Color.BLACK else colorSub)
        }
    }

    private fun formatVal(v: Int): String = when {
        v > 0 -> "+$v"
        else -> "$v"
    }

    private fun refreshCards(keepSlider: Boolean = false) {
        cardsContainer.removeAllViews()
        if (activeTab == 0) {
            if (!keepSlider) sliderBox.visibility = View.GONE
            for (preset in ProPhotoEngine.presets) {
                val isSelected = (preset.id == activePresetId)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = roundedBg(
                        if (isSelected) colorCardSelected else colorCard,
                        16,
                        if (isSelected) colorAmber else Color.TRANSPARENT
                    )
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(dp(106), dp(82)).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        activePresetId = preset.id
                        val stats = imageStats ?: ProPhotoEngine.ImageStats(0.5f, 0.1f, 0.05f)
                        currentParams = ProPhotoEngine.computeAdaptiveParams(preset.id, stats)
                        statusText.text = preset.name
                        refreshCards()
                        scheduleRender(immediate = true)
                    }
                }
                card.addView(TextView(this).apply {
                    text = if (preset.id == "original") "○" else "✦"
                    textSize = 18f
                    setTextColor(if (isSelected) colorAmber else colorSub)
                })
                card.addView(TextView(this).apply {
                    text = preset.name
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) colorAmber else colorText)
                })
                cardsContainer.addView(card)
            }
        } else {
            val cat = activeTab - 1 // 0=Tono, 1=Colores, 2=Detalles
            val list = ProPhotoEngine.ParamId.values().filter { it.category == cat }
            for (param in list) {
                val v = currentParams[param] ?: 0
                val isSelected = (param == selectedParam)
                val isModified = (v != 0)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = roundedBg(
                        if (isSelected) colorCardSelected else colorCard,
                        16,
                        if (isSelected) colorAmber else (if (isModified) Color.parseColor("#554422") else Color.TRANSPARENT)
                    )
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(82)).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        selectedParam = param
                        sliderTitle.text = param.label
                        sliderValue.text = formatVal(v)
                        paramSeek.max = param.maxVal - param.minVal
                        paramSeek.progress = v - param.minVal
                        sliderBox.visibility = View.VISIBLE
                        refreshCards(keepSlider = true)
                    }
                }
                card.addView(TextView(this).apply {
                    text = formatVal(v)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isModified || isSelected) colorAmber else colorSub)
                })
                card.addView(TextView(this).apply {
                    text = param.label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(colorText)
                    setPadding(0, dp(4), 0, 0)
                })
                cardsContainer.addView(card)
            }
        }
    }

    private fun loadPhoto(uri: Uri) {
        statusText.text = "Analizando luz y mapa 3D..."
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { decodeOrientedBitmap(uri, 2600) }
            if (loaded == null) {
                statusText.text = "Error al abrir"
                return@launch
            }
            originalFull = loaded
            val prev = downscaleIfNeeded(loaded, 1080)
            previewBase = prev
            imageView.setImageBitmap(prev)

            val (stats, dm, refined) = withContext(Dispatchers.Default) {
                val st = ProPhotoEngine.analyzeImage(prev)
                val raw = depthEngine.estimateDepth(prev)
                val px = IntArray(prev.width * prev.height)
                prev.getPixels(px, 0, prev.width, 0, 0, prev.width, prev.height)
                val ref = depthEngine.refineForLighting(raw, px, prev.width, prev.height, radius = 10)
                Triple(st, raw, ref)
            }
            imageStats = stats
            rawDepth = dm
            previewDepth = refined
            activePresetId = "original"
            currentParams = ProPhotoEngine.ParamId.values().associateWith { 0 }.toMutableMap()
            statusText.text = "Listo • Elige un filtro"
            refreshCards()
            scheduleRender(immediate = true)
        }
    }

    private fun scheduleRender(immediate: Boolean) {
        val base = previewBase ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            if (!immediate) delay(45)
            val result = withContext(Dispatchers.Default) {
                ProPhotoEngine.render(base, previewDepth, currentParams, activePresetId)
            }
            renderedPreview = result
            imageView.setImageBitmap(result)
        }
    }

    private fun exportFullRes() {
        val full = originalFull ?: return
        val dm = rawDepth ?: return
        statusText.text = "Guardando en máxima calidad..."
        exportBtn.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                val px = IntArray(full.width * full.height)
                full.getPixels(px, 0, full.width, 0, 0, full.width, full.height)
                val refFull = depthEngine.refineForLighting(dm, px, full.width, full.height, radius = 14)
                val finalBmp = ProPhotoEngine.render(full, refFull, currentParams, activePresetId)
                saveToGallery(finalBmp)
            }
            exportBtn.isEnabled = true
            statusText.text = if (ok) "Guardada en Galería" else "Error al guardar"
            Toast.makeText(this@MainActivity, if (ok) "Foto guardada en Pictures/LumenLab" else "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToGallery(bitmap: Bitmap): Boolean {
        val name = "LumenLab_${activePresetId}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LumenLab")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 97, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) { false }
    }

    private fun decodeOrientedBitmap(uri: Uri, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val maxDim = max(opts.outWidth, opts.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / sample > maxSide * 2) sample *= 2
        val raw = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        val rotated = if (!m.isIdentity) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true) else raw
        return downscaleIfNeeded(rotated, maxSide)
    }

    private fun downscaleIfNeeded(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
    }
}
