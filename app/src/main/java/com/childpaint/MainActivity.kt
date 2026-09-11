package com.childpaint

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
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
    private lateinit var moreButton: ImageView
    private lateinit var undoButton: ImageView
    private lateinit var redoButton: ImageView
    private lateinit var saveButton: ImageView
    private lateinit var photoButton: ImageView
    private lateinit var kidModeButton: ImageView

    private val kidMode by lazy { KidModeState(this) }
    private val settings by lazy { ParentSettings(this) }
    /** Autosave encoding and photo decoding both go here: off the UI thread, in order. */
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val autosave = Runnable { persistPicture() }
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshKidChrome()
            ticker.postDelayed(this, 1000L)
        }
    }

    private var backBlocker: OnBackPressedCallback? = null

    /** Held so the alarm can be silenced on unlock rather than running its course. */
    private var expiryAlarm: Ringtone? = null

    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) usePhotoAsPaper(uri) }

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

        // Touching the paper puts the tools away again — the tools are open only
        // for as long as it takes to pick one.
        binding.paintView.onCanvasTouched = { if (settings.hideToolbar) showToolbar(false) }
        binding.toolbarHandle.setOnClickListener { showToolbar(true) }

        binding.paintView.onTextRequested = { existing -> promptForText(existing) }
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

        binding.expiredUnlock.setOnClickListener { attemptUnlock() }

        // In kid mode Back must do nothing at all: the only way out is the guarded
        // unlock, and a child mashing Back should not even get a dialog to poke at.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = Unit
        }.also { backBlocker = it })

        restoreLastPicture()
        applyToolbarMode()
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
        stopExpiryAlert()
        persistPicture()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
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
        // Same reasoning for the photo picker, plus one more: it would have to open
        // another app's UI over a session that is meant to have no way out.
        photoButton.visibility = if (active || !settings.photoPaper) View.GONE else View.VISIBLE

        // The lock lives in the toolbar in both modes. It used to be a translucent
        // badge floating over the paper, which vanished the moment a child painted
        // something dark under it — exactly when a grown-up needs to find it.
        // Against the toolbar it is always legible, and a hold rather than a tap
        // keeps a child from opening the dialog by accident.
        if (active) {
            kidModeButton.setOnClickListener { toast(getString(R.string.unlock_hold_hint)) }
            kidModeButton.setOnLongClickListener { attemptUnlock(); true }
        } else {
            kidModeButton.setOnClickListener { showKidModeSetup(kidMode) { applyKidMode(started = true) } }
            kidModeButton.setOnLongClickListener { showParentExtras(settings) { applyExtras() }; true }
        }

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
            stopExpiryAlert()
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
        if (expired && !kidMode.expiryAlerted) {
            kidMode.markExpiryAlerted()
            soundExpiryAlert()
        }

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

    /**
     * Buzzes and/or sounds the alarm once, when the session runs out.
     *
     * Fired from the one-second tick, guarded by a persisted flag: the tick would
     * otherwise re-fire it every second for as long as the overlay is up, and a
     * relaunch after time is up would set it off again.
     */
    private fun soundExpiryAlert() {
        if (kidMode.alertVibrate) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(VIBRATE_PATTERN, -1)
                }
            }
        }

        if (!kidMode.alertSound) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            expiryAlarm = RingtoneManager.getRingtone(this, uri)?.apply {
                // The alarm stream, so it is audible from the next room even with the
                // ringer down — which is the whole point of the option.
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            // Stopped on a timer as well as on unlock: an alarm nobody silences is
            // a punishment for the child sitting next to it.
            ticker.postDelayed({ stopExpiryAlert() }, ALARM_MS)
        } catch (e: Exception) {
            // A missing or unplayable alarm sound must not take kid mode down.
        }
    }

    private fun stopExpiryAlert() {
        try {
            expiryAlarm?.takeIf { it.isPlaying }?.stop()
        } catch (e: Exception) {
            // Nothing useful to do if the ringtone is already gone.
        }
        expiryAlarm = null
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
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
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

    // ---- grown-up extras ------------------------------------------------------

    /**
     * Moves the toolbar between the column and the overlay, and shows whichever of
     * the toolbar and its handle belongs on screen.
     *
     * Reparenting rather than a second copy of the toolbar: it is built in code,
     * and two of them would mean two sets of buttons to keep in step.
     */
    private fun applyToolbarMode() {
        val hidden = settings.hideToolbar
        val toolbar = binding.toolbar
        val target: ViewGroup = if (hidden) binding.toolbarOverlay else binding.paperColumn
        val parent = toolbar.parent as? ViewGroup
        if (parent !== target) {
            parent?.removeView(toolbar)
            toolbar.layoutParams = if (hidden) {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            target.addView(toolbar)
        }
        binding.toolbarHandle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(17).toFloat()
            setColor(0xF2FFFFFF.toInt())
            setStroke(dp(2), 0xFFD8C4B2.toInt())
        }
        binding.toolbarHandle.imageTintList = ColorStateList.valueOf(ICON_TINT)
        showToolbar(!hidden)
    }

    /** In hide mode the handle and the toolbar swap places; otherwise it is a no-op. */
    private fun showToolbar(visible: Boolean) {
        if (!settings.hideToolbar) {
            binding.toolbar.visibility = View.VISIBLE
            binding.toolbarHandle.visibility = View.GONE
            return
        }
        binding.toolbar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.toolbarHandle.visibility = if (visible) View.GONE else View.VISIBLE
    }

    /** Re-reads [ParentSettings] and puts the toolbar back in line with it. */
    private fun applyExtras() {
        val current = binding.paintView.tool
        buildToolRow()
        // A dress-up tool that is no longer on screen must not stay selected, or the
        // next tap on the paper draws a moustache from a button nobody can see.
        val stillThere = toolButtons.any { it.first == current }
        selectTool(if (stillThere) current else Tool.BRUSH)
        photoButton.visibility = if (kidMode.active || !settings.photoPaper) View.GONE else View.VISIBLE
        applyToolbarMode()
    }

    /**
     * Confirms first: the photo becomes the new paper, and whatever is on the paper
     * now is gone. Undo cannot bring it back either — a new paper starts a new
     * history — so this is the one destructive step that gets a dialog rather than
     * the press-and-hold the clear button uses.
     */
    private fun onPhotoClicked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.photo_title)
            .setMessage(R.string.photo_confirm)
            .setPositiveButton(R.string.photo_pick) { _, _ ->
                pickPhoto.launch(
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build()
                )
            }
            // The way back out. Holding the clear button paints over a photo, but
            // leaves it underneath for undo to walk back onto — so without this
            // there is no way to be rid of a photo short of clearing and never
            // undoing, and nothing on screen says even that.
            .setNeutralButton(R.string.photo_blank) { _, _ ->
                binding.paintView.useBlankPaper()
                refreshHistoryButtons()
                scheduleAutosave()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun usePhotoAsPaper(uri: Uri) {
        val width = binding.paintView.width
        val height = binding.paintView.height
        if (width <= 0 || height <= 0) {
            toast(getString(R.string.photo_failed))
            return
        }
        val paperColor = binding.paintView.paperColor
        // Decoding a phone camera photo is tens of milliseconds at best and a
        // visible stutter at worst, so it happens on the same worker the autosave
        // uses and comes back to the canvas on the main thread.
        ioExecutor.execute {
            val paper = PhotoPaper.load(this, uri, width, height, paperColor)
            runOnUiThread {
                if (paper == null) {
                    toast(getString(R.string.photo_failed))
                    return@runOnUiThread
                }
                binding.paintView.setPaperPicture(paper)
                refreshHistoryButtons()
                scheduleAutosave()
            }
        }
    }

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

    /** Rebuilt, not appended to, because the grown-up extras change the drawer. */
    private fun buildToolRow() {
        binding.toolGrid.removeAllViews()
        toolButtons.clear()
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
        // The "..." button rides along as the next cell rather than sitting off to
        // one side, where it would read as an action instead of as more tools.
        if (TOOLS.size % TOOLS_PER_ROW == 0) {
            row = gridRow()
            binding.toolGrid.addView(row)
        }
        moreButton = chromeButton(R.drawable.ic_more).apply {
            layoutParams = cellParams(52)
            setOnClickListener { showMoreTools() }
        }
        row.addView(moreButton)
        // Pad the last row out to a full one. The cells are weight-laid-out, so a
        // row holding two buttons stretched them to half the screen each and the
        // dress-up shapes ended up twice the size of every other tool.
        val remainder = (TOOLS.size + 1) % TOOLS_PER_ROW
        if (remainder != 0) {
            repeat(TOOLS_PER_ROW - remainder) {
                row.addView(View(this).apply { layoutParams = cellParams(52) })
            }
        }
    }

    /** Everything that did not earn a permanent place on the toolbar. */
    private fun moreTools(): List<Pair<Tool, Int>> =
        if (settings.funShapes) MORE_TOOLS + FUN_TOOLS else MORE_TOOLS

    private fun showMoreTools() {
        val tools = moreTools()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        var row = gridRow()
        lateinit var dialog: AlertDialog
        tools.forEachIndexed { index, (tool, icon) ->
            if (index % MORE_PER_ROW == 0) {
                row = gridRow()
                grid.addView(row)
            }
            row.addView(
                chromeButton(icon).apply {
                    layoutParams = cellParams(60)
                    setSelectedLook(binding.paintView.tool == tool)
                    setOnClickListener {
                        selectTool(tool)
                        dialog.dismiss()
                    }
                }
            )
        }
        // Same reason as the toolbar: weighted cells in a short row grow to fill it.
        val remainder = tools.size % MORE_PER_ROW
        if (remainder != 0) {
            repeat(MORE_PER_ROW - remainder) {
                row.addView(View(this).apply { layoutParams = cellParams(60) })
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.more_shapes_title)
            .setView(grid)
            .create()
        dialog.show()
    }

    private fun buildActionRow() {
        // Three states rather than two, cycled by tapping: filled, filled with a
        // rim, outline. It starts on filled because that is what a child means by
        // "draw a hat".
        fillToggle = chromeButton(R.drawable.ic_shape_filled).apply {
            setOnClickListener {
                binding.paintView.shapeStyle = when (binding.paintView.shapeStyle) {
                    ShapeStyle.FILLED -> ShapeStyle.FILLED_OUTLINE
                    ShapeStyle.FILLED_OUTLINE -> ShapeStyle.OUTLINE
                    ShapeStyle.OUTLINE -> ShapeStyle.FILLED
                }
                refreshShapeStyleButton()
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
        // Parent-only, and only when the extra is switched on: picking a photo opens
        // the system picker, which is the child's whole gallery.
        photoButton = chromeButton(R.drawable.ic_photo).apply { setOnClickListener { onPhotoClicked() } }
        // Listeners are assigned in applyKidMode: this button means "hand over the
        // phone" in parent mode and "give it back" in kid mode.
        kidModeButton = chromeButton(R.drawable.ic_lock)

        for (b in listOf(fillToggle, undoButton, redoButton, clear, saveButton, photoButton, kidModeButton)) {
            b.layoutParams = cellParams(52)
            binding.actionRow.addView(b)
        }
        refreshShapeStyleButton()
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

    private fun refreshShapeStyleButton() {
        val style = binding.paintView.shapeStyle
        fillToggle.setImageResource(
            when (style) {
                ShapeStyle.FILLED -> R.drawable.ic_shape_filled
                ShapeStyle.FILLED_OUTLINE -> R.drawable.ic_shape_filled_outline
                ShapeStyle.OUTLINE -> R.drawable.ic_shape_outline
            }
        )
        // Outline is the plainest of the three, so it reads as the unset state.
        fillToggle.setSelectedLook(style != ShapeStyle.OUTLINE)
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
        // The drawer button wears whatever was picked out of it, so the toolbar
        // never hides which tool is selected.
        val fromDrawer = moreTools().firstOrNull { it.first == tool }
        moreButton.setImageResource(fromDrawer?.second ?: R.drawable.ic_more)
        moreButton.setSelectedLook(fromDrawer != null)
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

    private fun promptForText(existing: String) {
        val input = EditText(this).apply {
            // Multi-line on purpose: Enter has to insert a line break rather than
            // dismiss the dialog, because a typed break is the only way to say
            // where a line ends that the box width cannot express.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 6
            setText(existing)
            setSelection(existing.length)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.text_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                binding.paintView.addText(input.text.toString())
            }
            .setNegativeButton(R.string.cancel) { _, _ -> binding.paintView.cancelText() }
            .setOnCancelListener { binding.paintView.cancelText() }
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
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
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
            GALLERY_FOLDER
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
        /** Album the save button writes into, matching the app's public name. */
        const val GALLERY_FOLDER = "Little Painter"

        const val AUTOSAVE_NAME = "canvas.png"
        const val AUTOSAVE_DELAY_MS = 1200L

        /** Long enough to fetch a grown-up, short enough not to be a punishment. */
        const val ALARM_MS = 8000L
        val VIBRATE_PATTERN = longArrayOf(0, 400, 250, 400, 250, 700)

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

        /**
         * The toolbar itself: what a child reaches for without thinking. Everything
         * else is one tap away behind the "..." button rather than on screen.
         */
        val TOOLS = listOf(
            Tool.BRUSH to R.drawable.ic_brush,
            Tool.FILL to R.drawable.ic_bucket,
            Tool.ERASER to R.drawable.ic_eraser,
            Tool.TEXT to R.drawable.ic_text,
            Tool.SELECT to R.drawable.ic_select,
            Tool.LINE to R.drawable.ic_line,
            Tool.RECT to R.drawable.ic_rect,
            Tool.OVAL to R.drawable.ic_oval,
            Tool.TRIANGLE to R.drawable.ic_triangle
        )

        /** The drawer behind "...". Always available, extras or not. */
        val MORE_TOOLS = listOf(
            Tool.STAR to R.drawable.ic_star,
            Tool.HEART to R.drawable.ic_heart,
            Tool.DIAMOND to R.drawable.ic_diamond,
            Tool.PENTAGON to R.drawable.ic_pentagon,
            Tool.HEXAGON to R.drawable.ic_hexagon,
            Tool.ARROW to R.drawable.ic_arrow,
            Tool.CLOUD to R.drawable.ic_cloud,
            Tool.MOON to R.drawable.ic_moon,
            Tool.SUN to R.drawable.ic_sun,
            Tool.LIGHTNING to R.drawable.ic_lightning,
            Tool.FLOWER to R.drawable.ic_flower,
            Tool.BUBBLE to R.drawable.ic_bubble
        )

        const val MORE_PER_ROW = 4

        /** Added to the drawer only when a grown-up switches the extras on. */
        val FUN_TOOLS = listOf(
            Tool.GLASSES to R.drawable.ic_glasses,
            Tool.MUSTACHE to R.drawable.ic_mustache,
            Tool.UMBRELLA to R.drawable.ic_umbrella,
            Tool.CROWN to R.drawable.ic_crown
        )
    }
}
