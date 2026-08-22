package com.childpaint

import android.content.Context

/**
 * The unattended-child session, persisted rather than held in memory.
 *
 * Persistence is the whole point: if the session lived only in the activity, a
 * child who managed to kill the app would come back to the full parent UI with a
 * fresh clock. Storing the flag and an absolute deadline means force-stopping the
 * app, or rebooting the phone, drops straight back into kid mode with the same
 * time already spent.
 */
class KidModeState(context: Context) {

    private val prefs = context.getSharedPreferences("kid_mode", Context.MODE_PRIVATE)

    val active: Boolean get() = prefs.getBoolean(KEY_ACTIVE, false)

    /** Wall-clock epoch millis when the session ends; 0 means no time limit. */
    private val deadlineAt: Long get() = prefs.getLong(KEY_DEADLINE, 0L)

    val hasTimeLimit: Boolean get() = deadlineAt > 0L

    val pin: String? get() = prefs.getString(KEY_PIN, null)
    val requirePin: Boolean get() = prefs.getBoolean(KEY_REQUIRE_PIN, true)
    val requireMath: Boolean get() = prefs.getBoolean(KEY_REQUIRE_MATH, true)
    val totalSeconds: Int get() = prefs.getInt(KEY_TOTAL_SECONDS, 0)

    /**
     * Seconds until unlock attempts are accepted again, 0 when not blocked.
     *
     * Stored as a wall-clock instant for the same reason the session deadline is:
     * a child who works out that killing the app resets the counter has broken the
     * limit, so the lockout has to outlive the process.
     */
    val unlockBlockedSeconds: Long
        get() {
            val until = prefs.getLong(KEY_BLOCKED_UNTIL, 0L)
            if (until <= 0L) return 0L
            val left = (until - System.currentTimeMillis() + 999L) / 1000L
            return if (left < 0) 0 else left
        }

    val remainingSeconds: Long
        get() {
            if (!hasTimeLimit) return Long.MAX_VALUE
            val left = (deadlineAt - System.currentTimeMillis()) / 1000L
            return if (left < 0) 0 else left
        }

    val expired: Boolean get() = active && hasTimeLimit && remainingSeconds <= 0L

    /**
     * Records a wrong unlock answer. Returns true when that tripped the lockout.
     *
     * Only the final step calls this — see [KidModeDialogs]. A child mashing the
     * maths question should not be able to lock a parent out of their own phone.
     */
    fun registerFailedUnlock(): Boolean {
        val failures = prefs.getInt(KEY_FAILED, 0) + 1
        return if (failures >= MAX_UNLOCK_ATTEMPTS) {
            prefs.edit()
                .putInt(KEY_FAILED, 0)
                .putLong(KEY_BLOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MS)
                .apply()
            true
        } else {
            prefs.edit().putInt(KEY_FAILED, failures).apply()
            false
        }
    }

    fun clearUnlockFailures() {
        prefs.edit().putInt(KEY_FAILED, 0).putLong(KEY_BLOCKED_UNTIL, 0L).apply()
    }

    fun start(limitMinutes: Int, pin: String?, requirePin: Boolean, requireMath: Boolean) {
        // A deadline rather than a countdown, so time keeps running while the app is
        // closed. Wall clock, not elapsedRealtime, because that resets on reboot.
        val deadline =
            if (limitMinutes > 0) System.currentTimeMillis() + limitMinutes * 60_000L else 0L
        prefs.edit()
            .putInt(KEY_FAILED, 0)
            .putLong(KEY_BLOCKED_UNTIL, 0L)
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_DEADLINE, deadline)
            .putInt(KEY_TOTAL_SECONDS, limitMinutes * 60)
            .putString(KEY_PIN, pin)
            .putBoolean(KEY_REQUIRE_PIN, requirePin)
            .putBoolean(KEY_REQUIRE_MATH, requireMath)
            .apply()
    }

    /** Ends the session but keeps the PIN, so the parent does not retype it next time. */
    fun stop() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_DEADLINE, 0L)
            .putInt(KEY_TOTAL_SECONDS, 0)
            .putInt(KEY_FAILED, 0)
            .putLong(KEY_BLOCKED_UNTIL, 0L)
            .apply()
    }

    companion object {
        /** Wrong answers allowed before the lockout, and how long it lasts. */
        const val MAX_UNLOCK_ATTEMPTS = 3
        const val LOCKOUT_MS = 5 * 60_000L

        const val KEY_ACTIVE = "active"
        const val KEY_DEADLINE = "deadline_at"
        const val KEY_TOTAL_SECONDS = "total_seconds"
        const val KEY_PIN = "pin"
        const val KEY_REQUIRE_PIN = "require_pin"
        const val KEY_REQUIRE_MATH = "require_math"
        const val KEY_FAILED = "failed_unlocks"
        const val KEY_BLOCKED_UNTIL = "unlock_blocked_until"
    }
}
