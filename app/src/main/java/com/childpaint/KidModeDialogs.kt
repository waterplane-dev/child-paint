package com.childpaint

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.random.Random

/**
 * Preset session lengths offered to the parent; 0 means no limit.
 *
 * Labels are string resources rather than literals so the dialog translates with
 * the rest of the app — this list is a top-level val, so it has no Context to
 * resolve them with and the lookup happens where the dialog is built.
 */
private val DURATION_CHOICES = listOf(
    5 to R.string.duration_5_min,
    15 to R.string.duration_15_min,
    30 to R.string.duration_30_min,
    45 to R.string.duration_45_min,
    60 to R.string.duration_1_hour,
    120 to R.string.duration_2_hours,
    0 to R.string.duration_no_limit,
    CUSTOM_MINUTES to R.string.duration_custom
)

/** Sentinel for the duration entry that reveals a free-text minutes field. */
private const val CUSTOM_MINUTES = -1

/**
 * Parent-facing setup: how long the child gets, and which barriers guard the way
 * back out. Shown before kid mode starts, never once it is running.
 */
fun Activity.showKidModeSetup(state: KidModeState, onStart: () -> Unit) {
    val pad = dpOf(20)
    var selectedMinutes = 15

    val durationLabel = TextView(this).apply {
        text = getString(R.string.kid_setup_duration)
        setPadding(0, 0, 0, dpOf(8))
    }

    val durationGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val durationButtons = mutableListOf<Pair<Int, TextView>>()

    val customField = EditText(this).apply {
        hint = getString(R.string.kid_setup_custom_hint)
        inputType = InputType.TYPE_CLASS_NUMBER
        visibility = View.GONE
    }

    var customSelected = false

    fun markDuration(minutes: Int) {
        customSelected = minutes == CUSTOM_MINUTES
        if (!customSelected) selectedMinutes = minutes
        customField.visibility = if (customSelected) View.VISIBLE else View.GONE
        for ((value, view) in durationButtons) {
            view.setBackgroundColor(if (value == minutes) 0xFFFFE0B2.toInt() else 0x00000000)
            view.setTypeface(null, if (value == minutes) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // Two per row keeps the dialog compact without a chip dependency.
    DURATION_CHOICES.chunked(2).forEach { pair ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((minutes, labelRes) in pair) {
            val button = TextView(this).apply {
                text = getString(labelRes)
                gravity = Gravity.CENTER
                setPadding(dpOf(8), dpOf(12), dpOf(8), dpOf(12))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { markDuration(minutes) }
            }
            durationButtons += minutes to button
            row.addView(button)
        }
        durationGroup.addView(row)
    }

    val pinField = EditText(this).apply {
        hint = getString(R.string.kid_setup_pin_hint)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setText(state.pin.orEmpty())
    }

    val requirePin = CheckBox(this).apply {
        text = getString(R.string.kid_setup_require_pin)
        isChecked = state.requirePin
        setOnCheckedChangeListener { _, checked -> pinField.visibility = if (checked) View.VISIBLE else View.GONE }
    }
    pinField.visibility = if (requirePin.isChecked) View.VISIBLE else View.GONE

    val requireMath = CheckBox(this).apply {
        text = getString(R.string.kid_setup_require_math)
        isChecked = state.requireMath
    }

    // How the end of the session announces itself. A grown-up in the next room
    // cannot see the overlay, and a child who cannot read will not tell them.
    val alertVibrate = CheckBox(this).apply {
        text = getString(R.string.kid_setup_alert_vibrate)
        isChecked = state.alertVibrate
    }
    val alertSound = CheckBox(this).apply {
        text = getString(R.string.kid_setup_alert_sound)
        isChecked = state.alertSound
    }

    // The extras dialog has no button of its own; without this line the only way
    // to find it is to try holding a lock that already does something on a tap.
    val extrasHint = TextView(this).apply {
        text = getString(R.string.kid_setup_extras_hint)
        alpha = 0.7f
        setPadding(0, dpOf(12), 0, 0)
    }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, dpOf(12), pad, 0)
        addView(durationLabel)
        addView(durationGroup)
        addView(customField)
        addView(requirePin)
        addView(pinField)
        addView(requireMath)
        addView(alertVibrate)
        addView(alertSound)
        addView(extrasHint)
    }
    markDuration(selectedMinutes)

    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.kid_setup_title)
        .setMessage(R.string.kid_setup_explainer)
        .setView(content)
        .setPositiveButton(R.string.kid_setup_start, null)
        .setNegativeButton(R.string.cancel, null)
        .create()

    dialog.show()
    // Overriding the click after show() keeps the dialog open on a bad PIN.
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (customSelected) {
            val custom = customField.text.toString().toIntOrNull()
            if (custom == null || custom !in 1..1440) {
                customField.error = getString(R.string.kid_setup_custom_error)
                return@setOnClickListener
            }
            selectedMinutes = custom
        }
        val pin = pinField.text.toString()
        if (requirePin.isChecked && pin.length != 4) {
            pinField.error = getString(R.string.kid_setup_pin_error)
            return@setOnClickListener
        }
        state.start(
            limitMinutes = selectedMinutes,
            pin = if (requirePin.isChecked) pin else state.pin,
            requirePin = requirePin.isChecked,
            requireMath = requireMath.isChecked,
            alertVibrate = alertVibrate.isChecked,
            alertSound = alertSound.isChecked
        )
        dialog.dismiss()
        onStart()
    }
}

/**
 * The grown-up panel: the optional extras, off by default.
 *
 * It sits behind a long press on the lock in parent mode, and is unreachable in
 * kid mode because there the same long press is the unlock. That is the whole
 * gate — no maths, no PIN — because the phone is already in a grown-up's hands
 * when this is reachable at all. What it guards against is a child wandering into
 * the photo picker, not a determined attacker.
 */
fun Activity.showParentExtras(settings: ParentSettings, onChanged: () -> Unit) {
    val photo = CheckBox(this).apply {
        text = getString(R.string.extras_photo)
        isChecked = settings.photoPaper
    }
    val shapes = CheckBox(this).apply {
        text = getString(R.string.extras_fun_shapes)
        isChecked = settings.funShapes
    }
    val hideToolbar = CheckBox(this).apply {
        text = getString(R.string.extras_hide_toolbar)
        isChecked = settings.hideToolbar
    }
    // Which build is actually on the phone. Every APK looked identical from the
    // inside, so a build that never finished installing was indistinguishable
    // from one that did — and the answer was "the change is missing" either way.
    val version = TextView(this).apply {
        text = getString(R.string.extras_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        textSize = 12f
        alpha = 0.6f
        setPadding(0, dpOf(12), 0, 0)
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpOf(20), dpOf(12), dpOf(20), 0)
        addView(photo)
        addView(shapes)
        addView(hideToolbar)
        addView(version)
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.extras_title)
        .setMessage(R.string.extras_explainer)
        .setView(content)
        .setPositiveButton(R.string.ok) { _, _ ->
            settings.photoPaper = photo.isChecked
            settings.funShapes = shapes.isChecked
            settings.hideToolbar = hideToolbar.isChecked
            onChanged()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/**
 * The way out of kid mode. Barriers are asked one at a time: a sum a young child
 * cannot do in their head, then the parent's PIN. Both are optional individually,
 * but with neither enabled this degrades to a plain confirmation. Repeated wrong
 * answers at the last barrier trip the lockout in [KidModeState].
 */
fun Activity.showUnlockDialog(state: KidModeState, onUnlocked: () -> Unit) {
    val blocked = state.unlockBlockedSeconds
    if (blocked > 0) {
        showLockedOut(blocked)
        return
    }
    val steps = buildList {
        if (state.requireMath) add(STEP_MATH)
        if (state.requirePin && !state.pin.isNullOrEmpty()) add(STEP_PIN)
        if (isEmpty()) add(STEP_CONFIRM)
    }
    runUnlockStep(state, steps, 0, onUnlocked)
}

private const val STEP_MATH = "math"
private const val STEP_PIN = "pin"
private const val STEP_CONFIRM = "confirm"

private fun Activity.runUnlockStep(
    state: KidModeState,
    steps: List<String>,
    index: Int,
    onUnlocked: () -> Unit
) {
    if (index >= steps.size) {
        onUnlocked()
        return
    }

    val step = steps[index]
    if (step == STEP_CONFIRM) {
        AlertDialog.Builder(this)
            .setTitle(R.string.unlock_title)
            .setMessage(R.string.unlock_confirm)
            .setPositiveButton(R.string.ok) { _, _ -> onUnlocked() }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return
    }

    // Two two-digit numbers chosen so no column carries: 32 + 24, never 38 + 27.
    // An adult reads the answer off the page; a pre-schooler cannot add tens at
    // all. Multiplication tables were the thing the parent had to stop and think
    // about, and thinking is what this question must not require of them.
    val a = Random.nextInt(1, 5) * 10 + Random.nextInt(1, 5)
    val b = Random.nextInt(1, 5) * 10 + Random.nextInt(1, 5)

    val prompt = if (step == STEP_MATH) getString(R.string.unlock_math, a, b) else getString(R.string.unlock_pin)
    // Only the last barrier is rate limited. If a wrong sum counted, a child
    // hammering the maths question could lock the parent out of their own phone.
    val countsTowardLockout = index == steps.lastIndex
    val field = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or
            (if (step == STEP_PIN) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
        setPadding(dpOf(20), dpOf(16), dpOf(20), dpOf(16))
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.unlock_title)
        .setMessage(prompt)
        .setView(field)
        .setPositiveButton(R.string.ok, null)
        .setNegativeButton(R.string.cancel, null)
        .create()

    // Keyboard up with the dialog, same as the text tool: the parent is answering
    // this one-handed while a child waits.
    field.requestFocus()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
    field.post {
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val entered = field.text.toString().trim()
        val correct = if (step == STEP_MATH) entered.toIntOrNull() == a + b else entered == state.pin
        if (!correct) {
            field.text.clear()
            if (countsTowardLockout && state.registerFailedUnlock()) {
                dialog.dismiss()
                showLockedOut(state.unlockBlockedSeconds)
            } else {
                field.error = getString(R.string.unlock_wrong)
            }
            return@setOnClickListener
        }
        dialog.dismiss()
        if (index == steps.lastIndex) state.clearUnlockFailures()
        runUnlockStep(state, steps, index + 1, onUnlocked)
    }
}

/** Tells whoever is holding the phone how long the lockout has left to run. */
private fun Activity.showLockedOut(seconds: Long) {
    val message = if (seconds >= 60) {
        val minutes = ((seconds + 59) / 60).toInt()
        resources.getQuantityString(R.plurals.unlock_blocked_minutes, minutes, minutes)
    } else {
        val secs = seconds.toInt()
        resources.getQuantityString(R.plurals.unlock_blocked_seconds, secs, secs)
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.unlock_blocked_title)
        .setMessage(message)
        .setPositiveButton(R.string.ok, null)
        .show()
}

private fun Context.dpOf(value: Int): Int = (value * resources.displayMetrics.density).toInt()
