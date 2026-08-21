package com.childpaint

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.childpaint.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val colorSwatches = mutableListOf<Pair<Int, View>>()
    private val toolButtons = mutableListOf<Pair<Tool, TextView>>()
    private lateinit var fillToggle: TextView
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) savePicture() else toast(getString(R.string.storage_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildColorRow()
        buildToolRow()
        buildActionRow()

        binding.paintView.onTextRequested = { x, y -> promptForText(x, y) }
        binding.paintView.onHistoryChanged = { refreshHistoryButtons() }

        binding.sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.paintView.strokeWidth = progressToWidth(progress)
                updateSizePreview()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        restoreLastPicture()
        binding.paintView.strokeWidth = progressToWidth(binding.sizeBar.progress)
        selectColor(PALETTE[0])
        selectTool(Tool.BRUSH)
        updateSizePreview()
        refreshHistoryButtons()
    }

    override fun onStop() {
        super.onStop()
        persistPicture()
    }

    /**
     * Android reclaims backgrounded apps freely, and a child will not understand why
     * their drawing vanished. The canvas is stashed in internal storage on every stop
     * and put back on launch, independently of the explicit save-to-gallery button.
     */
    private fun persistPicture() {
        val bitmap = binding.paintView.exportBitmap() ?: return
        try {
            FileOutputStream(File(filesDir, AUTOSAVE_NAME)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            // A failed autosave must never take the app down mid-drawing.
        }
    }

    private fun restoreLastPicture() {
        val file = File(filesDir, AUTOSAVE_NAME)
        if (!file.exists()) return
        val saved = BitmapFactory.decodeFile(file.absolutePath) ?: return
        binding.paintView.restorePicture(saved)
    }

    /** Slider is non-linear so the thin end has usable resolution for detail work. */
    private fun progressToWidth(progress: Int): Float = 2f + (progress / 100f) * (progress / 100f) * 78f

    // ---- toolbar construction -------------------------------------------------

    /** Lays children out in fixed rows of [perRow], each stretched by weight. */
    private fun gridRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun cellParams(heightDp: Int) =
        LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
            topMargin = dp(3)
            bottomMargin = dp(3)
        }

    private fun buildColorRow() {
        var row = gridRow()
        PALETTE.forEachIndexed { index, color ->
            if (index % COLORS_PER_ROW == 0) {
                row = gridRow()
                binding.colorGrid.addView(row)
            }
            val swatch = View(this).apply {
                layoutParams = cellParams(38)
                setOnClickListener { selectColor(color) }
            }
            colorSwatches += color to swatch
            row.addView(swatch)
        }
    }

    private fun buildToolRow() {
        var row = gridRow()
        TOOLS.forEachIndexed { index, (tool, label) ->
            if (index % TOOLS_PER_ROW == 0) {
                row = gridRow()
                binding.toolGrid.addView(row)
            }
            val button = chromeButton(label).apply {
                layoutParams = cellParams(52)
                setOnClickListener { selectTool(tool) }
            }
            toolButtons += tool to button
            row.addView(button)
        }
    }

    private fun buildActionRow() {
        fillToggle = chromeButton(OUTLINE_ICON).apply {
            setOnClickListener {
                binding.paintView.fillShapes = !binding.paintView.fillShapes
                text = if (binding.paintView.fillShapes) FILLED_ICON else OUTLINE_ICON
                setSelectedLook(binding.paintView.fillShapes)
            }
        }
        undoButton = chromeButton("\u21B6").apply { setOnClickListener { binding.paintView.undo() } }
        redoButton = chromeButton("\u21B7").apply { setOnClickListener { binding.paintView.redo() } }
        val clear = chromeButton("\uD83D\uDDD1").apply { setOnClickListener { binding.paintView.clearPaper() } }
        val save = chromeButton("\uD83D\uDCBE").apply { setOnClickListener { onSaveClicked() } }

        for (b in listOf(fillToggle, undoButton, redoButton, clear, save)) {
            b.layoutParams = cellParams(52)
            binding.actionRow.addView(b)
        }
        fillToggle.setSelectedLook(false)
    }

    private fun chromeButton(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        isClickable = true
        isFocusable = true
        setSelectedLook(false)
    }

    private fun TextView.setSelectedLook(selected: Boolean) {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(if (selected) 0xFFFFD27A.toInt() else Color.WHITE)
            setStroke(dp(if (selected) 3 else 1), if (selected) 0xFFE08A00.toInt() else 0xFFD8C4B2.toInt())
        }
    }

    // ---- selection ------------------------------------------------------------

    private fun selectColor(color: Int) {
        binding.paintView.color = color
        for ((swatchColor, view) in colorSwatches) {
            val selected = swatchColor == color
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatchColor)
                setStroke(dp(if (selected) 4 else 1), if (selected) Color.BLACK else 0xFF8A7A6C.toInt())
            }
        }
        updateSizePreview()
    }

    private fun selectTool(tool: Tool) {
        binding.paintView.tool = tool
        for ((buttonTool, view) in toolButtons) view.setSelectedLook(buttonTool == tool)
        // Only shape tools care about fill-vs-outline; dim the toggle otherwise.
        val shapeSelected = tool.asShape() != null && tool != Tool.LINE
        fillToggle.alpha = if (shapeSelected) 1f else 0.4f
        updateSizePreview()
    }

    private fun updateSizePreview() {
        val width = binding.paintView.strokeWidth
        val dotColor = if (binding.paintView.tool == Tool.ERASER) Color.LTGRAY else binding.paintView.color
        binding.sizePreview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
            // The preview caps out at the widget size; it hints at scale, not exact px.
            val size = dp(6) + (width / 80f * dp(28)).toInt()
            setSize(size, size)
            setStroke(dp(1), 0xFF8A7A6C.toInt())
        }
    }

    private fun refreshHistoryButtons() {
        undoButton.alpha = if (binding.paintView.canUndo) 1f else 0.35f
        redoButton.alpha = if (binding.paintView.canRedo) 1f else 0.35f
    }

    // ---- text tool ------------------------------------------------------------

    private fun promptForText(x: Float, y: Float) {
        val input = EditText(this).apply {
            setSingleLine()
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                binding.paintView.addText(input.text.toString(), x, y)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- saving ---------------------------------------------------------------

    private fun onSaveClicked() {
        // Scoped storage means no permission is needed from Android 10 onwards.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePicture()
            return
        }
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            savePicture()
        } else {
            requestStorage.launch(permission)
        }
    }

    private fun savePicture() {
        val bitmap = binding.paintView.exportBitmap()
        if (bitmap == null) {
            toast(getString(R.string.save_failed))
            return
        }
        val name = "paint-${System.currentTimeMillis()}.png"
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(bitmap, name)
        } else {
            saveToLegacyPictures(bitmap, name)
        }
        toast(getString(if (ok) R.string.saved else R.string.save_failed))
    }

    private fun saveViaMediaStore(bitmap: Bitmap, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Paint")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun saveToLegacyPictures(bitmap: Bitmap, name: String): Boolean = try {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Paint"
        )
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        // Without this the picture does not show up in the gallery until a reboot.
        MediaStore.Images.Media.insertImage(contentResolver, file.absolutePath, name, null)
        true
    } catch (e: Exception) {
        false
    }

    // ---- helpers --------------------------------------------------------------

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTOSAVE_NAME = "canvas.png"

        const val COLORS_PER_ROW = 8
        const val TOOLS_PER_ROW = 5

        const val OUTLINE_ICON = "▭"
        const val FILLED_ICON = "▬"

        val PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFF7A7A7A.toInt(), 0xFFFFFFFF.toInt(), 0xFF8B5A2B.toInt(),
            0xFFE53935.toInt(), 0xFFFF7043.toInt(), 0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(),
            0xFF7CB342.toInt(), 0xFF2E7D32.toInt(), 0xFF00BCD4.toInt(), 0xFF1E88E5.toInt(),
            0xFF1A237E.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFFFFC1CC.toInt()
        )

        val TOOLS = listOf(
            Tool.BRUSH to "✏",
            Tool.FILL to "🪣",
            Tool.ERASER to "🧽",
            Tool.TEXT to "T",
            Tool.LINE to "╱",
            Tool.RECT to "□",
            Tool.OVAL to "○",
            Tool.TRIANGLE to "△",
            Tool.STAR to "☆",
            Tool.HEART to "♡"
        )
    }
}
