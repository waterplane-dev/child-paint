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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.childpaint.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
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
    private val pictures by lazy { PictureStore(this) }
    private val picturePrefs by lazy { getSharedPreferences(PICTURE_PREFS, MODE_PRIVATE) }

    /**
     * The saved picture the paper came from, so saving again updates it instead of
     * piling up copies. Dropped whenever the paper becomes a new page — clear, plain
     * paper, a photo — so a new drawing never overwrites the one it replaced.
     * Persisted: the canvas itself survives a restart, and so must this.
     */
    private var currentPictureId: String?
        get() = picturePrefs.getString(KEY_CURRENT_PICTURE, null)
        set(value) { picturePrefs.edit().putString(KEY_CURRENT_PICTURE, value).apply() }
    /**
     * False until the saved picture is back on the paper. Restoring reads on the
     * worker now, and an autosave before it lands would write the blank paper over
     * the files it is still reading from.
     */
    private var restored = false

    /** When the oldest change not yet autosaved was made; 0 when everything is saved. */
    private var unsavedSince = 0L
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

        setUpTyping()
        binding.paintView.onHistoryChanged = {
            refreshHistoryButtons()
            scheduleAutosave()
        }

        binding.sizeBar.onProgressChanged = { progress, _ ->
            binding.paintView.strokeWidth = progressToWidth(progress)
        }

        binding.expiredUnlock.squishOnPress()
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

    /**
     * Autosaving on onStop alone is not enough: in kid mode Home and Back are
     * blocked, so killing the app is the one exit a child can actually reach — and
     * a kill never runs onStop. Saving shortly after each finished stroke means the
     * worst case loses a second of drawing instead of the whole picture.
     *
     * The debounce has a ceiling. Each stroke pushes the save back, so a child who
     * never pauses for [AUTOSAVE_DELAY_MS] — the usual way a small child draws —
     * would otherwise not be saved at all until they stopped.
     */
    private fun scheduleAutosave() {
        val now = SystemClock.uptimeMillis()
        if (unsavedSince == 0L) unsavedSince = now
        val ceiling = unsavedSince + AUTOSAVE_MAX_WAIT_MS - now
        ticker.removeCallbacks(autosave)
        ticker.postDelayed(autosave, AUTOSAVE_DELAY_MS.coerceAtMost(ceiling).coerceAtLeast(0L))
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
        if (!restored) return
        // Copy on the UI thread (cheap); encode both the PNG and the op list off it
        // (not cheap — the JSON grows with every point of every stroke).
        val bitmap = binding.paintView.exportBitmap() ?: return
        val session = binding.paintView.saveSession()
        unsavedSince = 0L
        IO.execute {
            try {
                File(filesDir, AUTOSAVE_NAME).writePngAtomically(bitmap)
                if (session != null) persistSession(session)
            } catch (e: Exception) {
                // A failed autosave must never take the app down mid-drawing.
                Log.w(TAG, "autosave failed", e)
            }
        }
    }

    /**
     * The op list goes next to the flat picture, so the things in it can still be
     * picked up after a restart. The photo under it is written once per photo,
     * under its own name, *before* the list that points at it; a kill between the
     * two leaves the old list pointing at the old photo, never a mismatched pair.
     */
    private fun persistSession(session: PaintCanvasView.SavedSession) {
        val paperName = session.paperId?.let { "$PAPER_PREFIX$it.png" }
        if (paperName != null && session.paper != null) {
            val paperFile = File(filesDir, paperName)
            if (!paperFile.exists()) paperFile.writePngAtomically(session.paper)
        }
        File(filesDir, SESSION_NAME).writeTextAtomically(OpCodec.encode(session.session))
        filesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(PAPER_PREFIX) && file.name != paperName) file.delete()
        }
    }

    /**
     * Reads the autosave on the worker, not in onCreate's frame: decoding the op
     * list costs as much as encoding it. Being on the same worker as the writes is
     * the other half — a relaunch reads only after the last activity's final save
     * has landed, never the file it was about to replace.
     */
    private fun restoreLastPicture() {
        IO.execute {
            val flatFile = File(filesDir, AUTOSAVE_NAME)
            val flat = if (flatFile.exists()) BitmapFactory.decodeFile(flatFile.absolutePath) else null
            val sessionFile = File(filesDir, SESSION_NAME)
            val session = if (sessionFile.exists()) {
                try { OpCodec.decode(sessionFile.readText()) } catch (e: Exception) { null }
            } else null
            val paper = session?.paperId?.let { id ->
                val file = File(filesDir, "$PAPER_PREFIX$id.png")
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (flat != null || session != null) binding.paintView.restoreSession(session, paper, flat)
                restored = true
                refreshHistoryButtons()
            }
        }
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
            keyboardHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            keepTypingInView()
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
        binding.toolbarHandle.background = plainButton(radiusDp = 20f)
        binding.toolbarHandle.imageTintList = ColorStateList.valueOf(ICON_TINT)
        binding.toolbarHandle.squishOnPress()
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
        MaterialAlertDialogBuilder(this)
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
                currentPictureId = null
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
        IO.execute {
            val paper = PhotoPaper.load(this, uri, width, height, paperColor)
            runOnUiThread {
                if (paper == null) {
                    toast(getString(R.string.photo_failed))
                    return@runOnUiThread
                }
                binding.paintView.setPaperPicture(paper)
                currentPictureId = null
                refreshHistoryButtons()
                scheduleAutosave()
            }
        }
    }

    // ---- toolbar construction -------------------------------------------------

    /** Lays children out in fixed rows of [perRow], each stretched by weight. */
    private fun gridRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // A pressed button squishes and a chosen swatch grows past its cell.
        clipChildren = false
        clipToPadding = false
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
                squishOnPress()
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
                layoutParams = cellParams(TOOL_CELL_DP)
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
            layoutParams = cellParams(TOOL_CELL_DP)
            setOnClickListener { showMoreTools() }
        }
        row.addView(moreButton)
        // Pad the last row out to a full one. The cells are weight-laid-out, so a
        // row holding two buttons stretched them to half the screen each and the
        // dress-up shapes ended up twice the size of every other tool.
        val remainder = (TOOLS.size + 1) % TOOLS_PER_ROW
        if (remainder != 0) {
            repeat(TOOLS_PER_ROW - remainder) {
                row.addView(View(this).apply { layoutParams = cellParams(TOOL_CELL_DP) })
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
            clipChildren = false
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        var row = gridRow()
        lateinit var dialog: BottomSheetDialog
        tools.forEachIndexed { index, (tool, icon) ->
            if (index % MORE_PER_ROW == 0) {
                row = gridRow()
                grid.addView(row)
            }
            row.addView(
                chromeButton(icon).apply {
                    layoutParams = cellParams(DRAWER_CELL_DP)
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
                row.addView(View(this).apply { layoutParams = cellParams(DRAWER_CELL_DP) })
            }
        }
        dialog = kidSheet(getString(R.string.more_shapes_title), grid)
        dialog.show()
    }

    /**
     * A sheet that slides up from the tools: a title with a close button, then
     * [content]. Opened fully, so a tall grid never arrives half-hidden behind a
     * peek that a child would have to drag. The close button is the one knob a
     * child can find; tapping the paper above the sheet closes it as well.
     */
    private fun kidSheet(title: String, content: View): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(12), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ICON_TINT)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chromeButton(R.drawable.ic_close).apply {
                contentDescription = getString(R.string.close)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(header)
            addView(content)
        }
        dialog.setContentView(column)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
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
                currentPictureId = null
                toast(getString(R.string.cleared))
                true
            }
        }
        saveButton = chromeButton(R.drawable.ic_save).apply { setOnClickListener { showPictures() } }
        // Parent-only, and only when the extra is switched on: picking a photo opens
        // the system picker, which is the child's whole gallery.
        photoButton = chromeButton(R.drawable.ic_photo).apply { setOnClickListener { onPhotoClicked() } }
        // Listeners are assigned in applyKidMode: this button means "hand over the
        // phone" in parent mode and "give it back" in kid mode.
        kidModeButton = chromeButton(R.drawable.ic_lock)

        for (b in listOf(fillToggle, undoButton, redoButton, clear, saveButton, photoButton, kidModeButton)) {
            b.layoutParams = cellParams(TOOL_CELL_DP)
            binding.actionRow.addView(b)
        }
        refreshShapeStyleButton()
    }

    /**
     * One button style for every tool and action, so nothing looks out of place:
     * a face on a lip (see KidChrome.kt) that squishes under the finger. The glyph
     * sits a lip's height above centre, so it is centred on the face, not the key.
     */
    private fun chromeButton(iconRes: Int): ImageView = ImageView(this).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = dp(13)
        setPadding(inset, inset, inset, inset + dp(4))
        isClickable = true
        isFocusable = true
        squishOnPress()
        setSelectedLook(false)
    }

    /** Accent face and a white glyph when chosen; white face and ink otherwise. */
    private fun View.setSelectedLook(selected: Boolean) {
        val was = getTag(R.id.selected_look) as? Boolean
        setTag(R.id.selected_look, selected)
        background = if (selected) accentButton() else plainButton()
        (this as? ImageView)?.imageTintList = ColorStateList.valueOf(if (selected) Color.WHITE else ICON_TINT)
        // Becoming the chosen one gets a little bounce, so the eye finds it.
        if (selected && was == false) {
            scaleX = 1.14f
            scaleY = 1.14f
            springBack()
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
        // The glyph is the state; the accent is reserved for the chosen tool, so
        // the toolbar never shows two orange buttons.
        fillToggle.setSelectedLook(false)
    }

    // ---- selection ------------------------------------------------------------

    private fun selectColor(color: Int) {
        binding.paintView.color = color
        val density = resources.displayMetrics.density
        for ((swatchColor, view) in colorSwatches) {
            val selected = swatchColor == color
            view.background = SwatchDrawable(swatchColor, selected, density)
            // The chosen blob stands proud of the row — bigger, and above its
            // neighbours so the overlap goes the right way.
            val rest = if (selected) 1.2f else 1f
            if (view.restScale != rest) view.settleAt(rest)
            view.translationZ = if (selected) density else 0f
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

    /** The size wedge is painted in the colour that will come out of the brush. */
    private fun updateSizePreview() {
        binding.sizeBar.color =
            if (binding.paintView.tool == Tool.ERASER) ERASER_PREVIEW else binding.paintView.color
    }

    private fun refreshHistoryButtons() {
        undoButton.alpha = if (binding.paintView.canUndo) 1f else 0.35f
        redoButton.alpha = if (binding.paintView.canRedo) 1f else 0.35f
    }

    // ---- text tool ------------------------------------------------------------

    /** How much of the window the keyboard covers from the bottom; 0 when it is down. */
    private var keyboardHeight = 0

    /** Set while the field is filled from the paper, so the echo is not typed back in. */
    private var fillingInput = false

    /**
     * Typing goes straight onto the paper. The drawn box is the input: an unseen
     * field holds the keyboard, and every letter it gets is handed to the box,
     * which fits the words to itself. A dialog here covered the very box the
     * words were for, and asked a question of a child who cannot read it.
     */
    private fun setUpTyping() {
        val input = binding.textInput
        // The paper must never be resized under the keyboard: ops are canvas
        // pixels, and a new size recreates the bitmap. From Android 11 the window
        // does not resize when drawing edge-to-edge, and the keyboard's height
        // still arrives as an inset, which [keepTypingInView] uses to lift the
        // paper out of its way instead. Before that, it simply covers what it covers.
        window.setSoftInputMode(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }
        )
        input.doAfterTextChanged { text ->
            if (!fillingInput) binding.paintView.typeText(text?.toString().orEmpty())
        }
        input.setOnEditorActionListener { _, _, _ ->
            binding.paintView.finishTyping()
            true
        }
        binding.paintView.onTextRequested = { existing ->
            fillingInput = true
            input.setText(existing)
            input.setSelection(existing.length)
            fillingInput = false
            input.requestFocus()
            // In kid mode the immersive flags mean focus alone does not always
            // bring the keyboard up; ask for it outright, once the focus has landed.
            input.post {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            keepTypingInView()
        }
        binding.paintView.onTextEditEnded = {
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            fillingInput = true
            input.setText("")
            fillingInput = false
            keepTypingInView()
        }
    }

    /**
     * Slides the paper up just far enough that the box being typed into clears the
     * keyboard, and back down when it is gone. A translation, not a resize — the
     * paper keeps its size, so nothing drawn on it moves relative to it.
     */
    private fun keepTypingInView() {
        val column = binding.paperColumn
        val box = binding.paintView.typingBounds()
        if (box == null || keyboardHeight == 0) {
            column.translationY = 0f
            return
        }
        val at = IntArray(2)
        binding.paintView.getLocationInWindow(at)
        val boxBottom = at[1] - column.translationY + box.bottom + dp(24)
        val keyboardTop = binding.root.height - keyboardHeight
        column.translationY = -(boxBottom - keyboardTop).coerceIn(0f, box.top.coerceAtLeast(0f))
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
        // A full-screen PNG takes hundreds of milliseconds to encode: on the worker,
        // or the paper freezes under the child's finger.
        IO.execute {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, name)
            } else {
                saveToLegacyPictures(bitmap, name)
            }
            runOnUiThread { toast(getString(if (ok) R.string.saved else R.string.save_failed)) }
        }
        storeCurrentPicture(bitmap, currentPictureId ?: newPictureId())
    }

    // ---- my pictures ----------------------------------------------------------

    /**
     * The save button's grid: "save this one" first, then every picture kept in the
     * app, newest first. Tapping one opens it with its things still separate, so
     * the hand can pick them up again; holding one offers to throw it away.
     */
    private fun showPictures() {
        lateinit var dialog: BottomSheetDialog
        val columns = 3
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val tiles = mutableListOf<View>()
        tiles += pictureTile(null, getString(R.string.pictures_save), selected = false).apply {
            setOnClickListener {
                dialog.dismiss()
                onSaveClicked()
            }
        }
        val current = currentPictureId
        for (entry in pictures.list()) {
            tiles += pictureTile(entry.thumb, null, selected = entry.id == current).apply {
                setOnClickListener {
                    dialog.dismiss()
                    openPicture(entry.id)
                }
                setOnLongClickListener {
                    confirmDeletePicture(entry.id) {
                        dialog.dismiss()
                        showPictures()
                    }
                    true
                }
            }
        }
        for (rowTiles in tiles.chunked(columns)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (tile in rowTiles) {
                tile.layoutParams = LinearLayout.LayoutParams(0, dp(170), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                row.addView(tile)
            }
            // Pad the last row so its tiles keep the same width as the rest.
            repeat(columns - rowTiles.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(170), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            grid.addView(row)
        }
        dialog = kidSheet(getString(R.string.pictures_title), android.widget.ScrollView(this).apply { addView(grid) })
        dialog.show()
    }

    /**
     * One grid cell: a thumbnail, or the save icon with [label] under it. The save
     * tile wears the accent — it is the thing to press — and the tile of the
     * picture the paper came from wears it too, as "this one is open".
     */
    private fun pictureTile(thumb: File?, label: String?, selected: Boolean): View {
        val primary = thumb == null
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            squishOnPress()
            setSelectedLook(selected || primary)
            setPadding(dp(6), dp(6), dp(6), dp(10))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (thumb != null) {
                BitmapFactory.decodeFile(thumb.absolutePath)?.let { setImageBitmap(it) }
            } else {
                setImageResource(R.drawable.ic_save)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding(dp(28), dp(28), dp(28), dp(8))
            }
        }
        tile.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (label != null) {
            tile.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (primary) Color.WHITE else ICON_TINT)
            })
        }
        return tile
    }

    /**
     * Writes the paper into the app's own pictures under [id]. The copy happens
     * here on the UI thread; encoding goes to the worker like autosave, and so does
     * the [skipIfBlank] check, which reads every pixel.
     */
    private fun storeCurrentPicture(flat: Bitmap, id: String, skipIfBlank: Boolean = false) {
        val session = binding.paintView.saveSession()
        val paper = binding.paintView.paperColor
        currentPictureId = id
        IO.execute {
            try {
                if (!skipIfBlank || !isBlank(flat, paper)) pictures.save(id, session, flat)
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.save_failed)) }
            }
        }
    }

    /**
     * Opens a kept picture in place of the paper. Whatever is on the paper now is
     * put away first — under its own picture if it came from one, as a new one if
     * not — so opening an old drawing never costs the current one. A blank page is
     * not worth a tile and is skipped.
     */
    private fun openPicture(id: String) {
        val flat = binding.paintView.exportBitmap()
        if (flat != null) storeCurrentPicture(flat, currentPictureId ?: newPictureId(), skipIfBlank = true)
        IO.execute {
            val loaded = try { pictures.load(id) } catch (e: Exception) { null }
            runOnUiThread {
                if (loaded == null) {
                    toast(getString(R.string.picture_open_failed))
                    return@runOnUiThread
                }
                binding.paintView.restoreSession(loaded.session, loaded.paper, loaded.flat)
                currentPictureId = id
                refreshHistoryButtons()
            }
        }
    }

    private fun confirmDeletePicture(id: String, then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.picture_delete_title)
            .setPositiveButton(R.string.picture_delete) { _, _ ->
                if (currentPictureId == id) currentPictureId = null
                // After any pending save of this same picture, or it comes back.
                IO.execute { pictures.delete(id) }
                then()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isBlank(bitmap: Bitmap, paper: Int): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.all { it == paper }
    }

    private fun newPictureId() = "p${System.currentTimeMillis()}"

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

    /** Set while a hint is up, so a fade-out cut short by a new hint does not hide it. */
    private var hintShowing = false

    private val hideHint = Runnable {
        hintShowing = false
        binding.hintPill.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(220)
            .withEndAction { if (!hintShowing) binding.hintPill.visibility = View.GONE }
            .start()
    }

    /**
     * A hint at the top of the paper, in the app's own voice rather than the
     * system toast's: a dark pill that drops in, waits, and fades. One at a time —
     * a new one replaces whatever is showing.
     */
    private fun toast(message: String) {
        val pill = binding.hintPill
        ticker.removeCallbacks(hideHint)
        pill.text = message
        if (!hintShowing) {
            pill.alpha = 0f
            pill.translationY = -dp(12).toFloat()
            pill.visibility = View.VISIBLE
        }
        hintShowing = true
        pill.animate().alpha(1f).translationY(0f).setDuration(200).withEndAction(null).start()
        ticker.postDelayed(hideHint, HINT_MS)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Album the save button writes into, matching the app's public name. */
        const val GALLERY_FOLDER = "Little Painter"

        const val AUTOSAVE_NAME = "canvas.png"
        const val PICTURE_PREFS = "pictures"
        const val KEY_CURRENT_PICTURE = "current"
        /** The op list behind the flat picture, so its things stay pickable after a restart. */
        const val SESSION_NAME = "canvas-ops.json"
        /** The photo under the ops, one file per photo: paper-<id>.png. */
        const val PAPER_PREFIX = "paper-"
        const val AUTOSAVE_DELAY_MS = 1200L
        /** However busy the child, no change waits longer than this to be saved. */
        const val AUTOSAVE_MAX_WAIT_MS = 10_000L
        const val TAG = "ChildPaint"

        /**
         * Every write and read of the app's files, one at a time, in order. One per
         * process, not per activity: a recreated activity must queue behind the
         * saves its predecessor left running, or it reads half-old files and two
         * threads race on the same temp file.
         */
        val IO: ExecutorService = Executors.newSingleThreadExecutor()

        /** Long enough to fetch a grown-up, short enough not to be a punishment. */
        const val ALARM_MS = 8000L
        val VIBRATE_PATTERN = longArrayOf(0, 400, 250, 400, 250, 700)

        /** Every toolbar glyph is tinted this, so no icon reads as a stray emoji. */
        const val ICON_TINT = 0xFF3F2E22.toInt()
        /** What the size wedge shows for the eraser: the pink of a pencil's rubber. */
        const val ERASER_PREVIEW = 0xFFE8C4C0.toInt()

        const val COLORS_PER_ROW = 8
        const val TOOLS_PER_ROW = 5
        /** Cell heights; each includes the 4 dp lip under the face. */
        const val TOOL_CELL_DP = 56
        const val DRAWER_CELL_DP = 68

        /** How long a hint stays up: read twice, not long enough to nag. */
        const val HINT_MS = 2200L

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
            Tool.SELECT to R.drawable.ic_hand,
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
