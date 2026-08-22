package com.childpaint

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.childpaint.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val colorSwatches = mutableListOf<Pair<Int, View>>()
    private val toolButtons = mutableListOf<Pair<Tool, ImageView>>()
    private lateinit var fillToggle: ImageView
    private lateinit var undoButton: ImageView
    private lateinit var redoButton: ImageView
    private lateinit var saveButton: ImageView
    private lateinit var kidModeButton: ImageView

    private val kidMode by lazy { KidModeState(this) }
    private val autosaveExecutor = Executors.newSingleThreadExecutor()
    private val autosave = Runnable { persistPicture() }
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshKidChrome()
            ticker.postDelayed(this, 1000L)
        }
    }

    private var backBlocker: OnBackPressedCallback? = null

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) savePicture() else toast(getString(R.string.storage_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets()

        buildColorRow()
        buildToolRow()
        buildActionRow()

        binding.paintView.onTextRequested = { x, y -> promptForText(x, y) }
        binding.paintView.onHistoryChanged = {
            refreshHistoryButtons()
            scheduleAutosave()
        }

        binding.sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.paintView.strokeWidth = progressToWidth(progress)
                updateSizePreview()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        binding.lockCorner.setOnLongClickListener { attemptUnlock(); true }
        binding.lockCorner.setOnClickListener { toast(getString(R.string.pin_hold_hint)) }
        binding.expiredUnlock.setOnClickListener { attemptUnlock() }

        // In kid mode Back must do nothing at all: the only way out is the guarded
        // unlock, and a child mashing Back should not even get a dialog to poke at.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = Unit
        }.also { backBlocker = it })

        restoreLastPicture()
        binding.paintView.strokeWidth = progressToWidth(binding.sizeBar.progress)
        selectColor(PALETTE[0])
        selectTool(Tool.BRUSH)
        updateSizePreview()
        refreshHistoryButtons()
    }

    override fun onStart() {
        super.onStart()
        // Kid mode is re-entered from persisted state, so a force-stop does not escape it.
        applyKidMode(started = false)
        ticker.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(autosave)
        persistPicture()
    }

    override fun onDestroy() {
        super.onDestroy()
        autosaveExecutor.shutdown()
    }

    /**
     * Autosaving on onStop alone is not enough: in kid mode Home and Back are
     * blocked, so killing the app is the one exit a child can actually reach — and
     * a kill never runs onStop. Saving shortly after each finished stroke means the
     * worst case loses a second of drawing instead of the whole picture.
     */
    private fun scheduleAutosave() {
        ticker.removeCallbacks(autosave)
        ticker.postDelayed(autosave, AUTOSAVE_DELAY_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System UI creeps back after dialogs and transient swipes; re-assert it.
        if (hasFocus && kidMode.active) applyImmersiveMode()
    }

    // ---- kid mode -------------------------------------------------------------

    /**
     * Brings the whole UI in line with [KidModeState]. Called on every start, not
     * just when the parent flips the switch, so a relaunch lands back in kid mode.
     */
    private fun applyKidMode(started: Boolean) {
        val active = kidMode.active
        backBlocker?.isEnabled = active

        // No saving in kid mode: one canvas, and nothing lands in the phone gallery.
        saveButton.visibility = if (active) View.GONE else View.VISIBLE
        kidModeButton.visibility = if (active) View.GONE else View.VISIBLE
        binding.lockCorner.visibility = if (active) View.VISIBLE else View.GONE

        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyImmersiveMode()
            try {
                // Real pinning needs the user to confirm screen pinning, or a device
                // owner to allowlist us. Best-effort: the rest of kid mode still holds.
                startLockTask()
            } catch (e: Exception) {
                // Ignore; immersive mode and the blocked Back button remain in force.
            }
            if (started) toast(getString(R.string.kid_mode_started))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearImmersiveMode()
            try {
                stopLockTask()
            } catch (e: Exception) {
                // Ignore when lock task was never entered.
            }
        }
        refreshKidChrome()
    }

    private fun attemptUnlock() {
        showUnlockDialog(kidMode) {
            kidMode.stop()
            applyKidMode(started = false)
            toast(getString(R.string.kid_mode_ended))
        }
    }

    /** Repaints the time-left bar and shows the blocking overlay once time runs out. */
    private fun refreshKidChrome() {
        if (!kidMode.active) {
            binding.timerHud.visibility = View.GONE
            binding.expiredOverlay.visibility = View.GONE
            return
        }

        val expired = kidMode.expired
        binding.expiredOverlay.visibility = if (expired) View.VISIBLE else View.GONE

        if (!kidMode.hasTimeLimit || expired) {
            binding.timerHud.visibility = View.GONE
            return
        }

        val remaining = kidMode.remainingSeconds
        val total = kidMode.totalSeconds.toLong().coerceAtLeast(1L)
        val accent = when {
            remaining <= 60 -> 0xFFE53935.toInt()
            remaining <= 300 -> 0xFFFF8F00.toInt()
            else -> 0xFF2E7D32.toInt()
        }

        binding.timerHud.visibility = View.VISIBLE
        binding.timerBar.apply {
            // Scaled to a 1000-step max so the bar still creeps visibly on a long
            // session; a per-second max would jump in whole percent steps.
            progress = ((remaining.coerceAtMost(total) * 1000L) / total).toInt()
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x33000000)
        }
        binding.lastMinuteLabel.apply {
            visibility = if (remaining <= 60) View.VISIBLE else View.GONE
            setTextColor(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(0xF2FFFFFF.toInt())
                setStroke(dp(2), accent)
            }
        }
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun clearImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /**
     * Android reclaims backgrounded apps freely, and a child will not understand why
     * their drawing vanished. The canvas is stashed in internal storage on every stop
     * and put back on launch, independently of the explicit save-to-gallery button.
     */
    private fun persistPicture() {
        // Copy on the UI thread (cheap), encode off it (not cheap).
        val bitmap = binding.paintView.exportBitmap() ?: return
        val target = File(filesDir, AUTOSAVE_NAME)
        if (autosaveExecutor.isShutdown) return
        autosaveExecutor.execute {
            try {
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                // A failed autosave must never take the app down mid-drawing.
            }
        }
    }

    private fun restoreLastPicture() {
        val file = File(filesDir, AUTOSAVE_NAME)
        if (!file.exists()) return
        val saved = BitmapFactory.decodeFile(file.absolutePath) ?: return
        binding.paintView.restorePicture(saved)
    }

    /**
     * Draws under the system bars and pads the content back out by the insets.
     *
     * Without this, hiding the bars in kid mode leaves the strip they occupied
     * painted black by the system, which reads as a broken screen next to the
     * paper. Edge-to-edge means that strip is simply part of the app, and when the
     * bars come back in parent mode the insets push the toolbar clear of them.
     */
    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
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

    /** Square cells size themselves; [SquareView] derives the height from the width. */
    private fun weightedCell() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
            topMargin = dp(3)
            bottomMargin = dp(3)
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
            val swatch = SquareView(this).apply {
                layoutParams = weightedCell()
                setOnClickListener { selectColor(color) }
            }
            colorSwatches += color to swatch
            row.addView(swatch)
        }
    }

    private fun buildToolRow() {
        var row = gridRow()
        TOOLS.forEachIndexed { index, (tool, icon) ->
            if (index % TOOLS_PER_ROW == 0) {
                row = gridRow()
                binding.toolGrid.addView(row)
            }
            val button = chromeButton(icon).apply {
                layoutParams = cellParams(52)
                setOnClickListener { selectTool(tool) }
            }
            toolButtons += tool to button
            row.addView(button)
        }
    }

    private fun buildActionRow() {
        fillToggle = chromeButton(R.drawable.ic_shape_outline).apply {
            setOnClickListener {
                binding.paintView.fillShapes = !binding.paintView.fillShapes
                setImageResource(
                    if (binding.paintView.fillShapes) R.drawable.ic_shape_filled
                    else R.drawable.ic_shape_outline
                )
                setSelectedLook(binding.paintView.fillShapes)
            }
        }
        undoButton = chromeButton(R.drawable.ic_undo).apply { setOnClickListener { binding.paintView.undo() } }
        redoButton = chromeButton(R.drawable.ic_redo).apply { setOnClickListener { binding.paintView.redo() } }
        // A single tap here used to wipe the picture. Undo brings it back, but a
        // child does not know that, so wiping is a deliberate hold — the same
        // press-and-hold the lock corner already uses.
        val clear = chromeButton(R.drawable.ic_clear).apply {
            setOnClickListener { toast(getString(R.string.clear_hold_hint)) }
            setOnLongClickListener {
                binding.paintView.clearPaper()
                toast(getString(R.string.cleared))
                true
            }
        }
        saveButton = chromeButton(R.drawable.ic_save).apply { setOnClickListener { onSaveClicked() } }
        kidModeButton = chromeButton(R.drawable.ic_lock).apply {
            setOnClickListener { showKidModeSetup(kidMode) { applyKidMode(started = true) } }
        }

        for (b in listOf(fillToggle, undoButton, redoButton, clear, saveButton, kidModeButton)) {
            b.layoutParams = cellParams(52)
            binding.actionRow.addView(b)
        }
        fillToggle.setSelectedLook(false)
    }

    /** One button style for every tool and action, so nothing looks out of place. */
    private fun chromeButton(iconRes: Int): ImageView = ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(ICON_TINT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(13)
        setPadding(inset, inset, inset, inset)
        isClickable = true
        isFocusable = true
        setSelectedLook(false)
    }

    private fun View.setSelectedLook(selected: Boolean) {
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.text_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                binding.paintView.addText(input.text.toString(), x, y)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        // The keyboard comes up with the dialog. Asking for the field to be tapped
        // as well is one step too many, and in kid mode the immersive flags mean the
        // soft input mode alone is not always enough — hence the explicit request.
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
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
        const val AUTOSAVE_DELAY_MS = 1200L

        /** Every toolbar glyph is tinted this, so no icon reads as a stray emoji. */
        const val ICON_TINT = 0xFF4A3B2E.toInt()

        const val COLORS_PER_ROW = 8
        const val TOOLS_PER_ROW = 5

        val PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFF7A7A7A.toInt(), 0xFFFFFFFF.toInt(), 0xFF8B5A2B.toInt(),
            0xFFE53935.toInt(), 0xFFFF7043.toInt(), 0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(),
            0xFF7CB342.toInt(), 0xFF2E7D32.toInt(), 0xFF00BCD4.toInt(), 0xFF1E88E5.toInt(),
            0xFF1A237E.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFFFFC1CC.toInt()
        )

        val TOOLS = listOf(
            Tool.BRUSH to R.drawable.ic_brush,
            Tool.FILL to R.drawable.ic_bucket,
            Tool.ERASER to R.drawable.ic_eraser,
            Tool.TEXT to R.drawable.ic_text,
            Tool.LINE to R.drawable.ic_line,
            Tool.RECT to R.drawable.ic_rect,
            Tool.OVAL to R.drawable.ic_oval,
            Tool.TRIANGLE to R.drawable.ic_triangle,
            Tool.STAR to R.drawable.ic_star,
            Tool.HEART to R.drawable.ic_heart
        )
    }
}
