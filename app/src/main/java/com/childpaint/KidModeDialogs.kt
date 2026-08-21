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

/** Preset session lengths offered to the parent; 0 means no limit. */
private val DURATION_CHOICES = listOf(
    5 to "5 min",
    15 to "15 min",
    30 to "30 min",
    45 to "45 min",
    60 to "1 hour",
    120 to "2 hours",
    0 to "No limit",
    CUSTOM_MINUTES to "Custom\u2026"
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
        for ((minutes, label) in pair) {
            val button = TextView(this).apply {
                text = label
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

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, dpOf(12), pad, 0)
        addView(durationLabel)
        addView(durationGroup)
        addView(customField)
        addView(requirePin)
        addView(pinField)
        addView(requireMath)
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
            requireMath = requireMath.isChecked
        )
        dialog.dismiss()
        onStart()
    }
}

/**
 * The way out of kid mode. Barriers are asked one at a time: a multiplication a
 * young child cannot do in their head, then the parent's PIN. Both are optional
 * individually, but with neither enabled this degrades to a plain confirmation.
 */
fun Activity.showUnlockDialog(state: KidModeState, onUnlocked: () -> Unit) {
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

    // Single-digit times table: instant for an adult, still well out of reach for
    // the age this app is aimed at. Two-digit factors only made the parent do
    // arithmetic one-handed while holding a child.
    val a = Random.nextInt(3, 10)
    val b = Random.nextInt(3, 10)

    val prompt = if (step == STEP_MATH) getString(R.string.unlock_math, a, b) else getString(R.string.unlock_pin)
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
        val correct = if (step == STEP_MATH) entered.toIntOrNull() == a * b else entered == state.pin
        if (!correct) {
            field.text.clear()
            field.error = getString(R.string.unlock_wrong)
            return@setOnClickListener
        }
        dialog.dismiss()
        runUnlockStep(state, steps, index + 1, onUnlocked)
    }
}

private fun Context.dpOf(value: Int): Int = (value * resources.displayMetrics.density).toInt()
