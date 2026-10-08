package com.childpaint

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The session a child cannot end by killing the app. Every value is read back
 * through a fresh [KidModeState], because surviving the process is the point.
 */
@RunWith(RobolectricTestRunner::class)
class KidModeStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun fresh() = KidModeState(context)

    private fun start(minutes: Int) =
        fresh().start(minutes, pin = "1234", requirePin = true, requireMath = true, alertVibrate = true, alertSound = false)

    @Test
    fun aTimedSessionCountsDownFromItsLimit() {
        start(minutes = 10)
        val state = fresh()
        assertTrue(state.active)
        assertTrue(state.hasTimeLimit)
        assertEquals(600, state.totalSeconds)
        assertTrue(state.remainingSeconds in 598L..600L)
        assertFalse(state.expired)
    }

    @Test
    fun anUntimedSessionNeverExpires() {
        start(minutes = 0)
        val state = fresh()
        assertTrue(state.active)
        assertFalse(state.hasTimeLimit)
        assertEquals(Long.MAX_VALUE, state.remainingSeconds)
        assertFalse(state.expired)
    }

    @Test
    fun aDeadlineInThePastHasExpired() {
        start(minutes = 10)
        context.getSharedPreferences("kid_mode", Context.MODE_PRIVATE).edit()
            .putLong(KidModeState.KEY_DEADLINE, System.currentTimeMillis() - 1_000L).commit()
        assertTrue(fresh().expired)
        assertEquals(0L, fresh().remainingSeconds)
    }

    @Test
    fun wrongAnswersLockOutOnlyAfterTheLimit() {
        start(minutes = 10)
        repeat(KidModeState.MAX_UNLOCK_ATTEMPTS - 1) { assertFalse(fresh().registerFailedUnlock()) }
        assertEquals(0L, fresh().unlockBlockedSeconds)
        assertTrue(fresh().registerFailedUnlock())
        assertTrue(fresh().unlockBlockedSeconds in 299L..300L)
        fresh().clearUnlockFailures()
        assertEquals(0L, fresh().unlockBlockedSeconds)
    }

    @Test
    fun stoppingEndsTheSessionButKeepsThePin() {
        start(minutes = 10)
        fresh().markExpiryAlerted()
        fresh().stop()
        val state = fresh()
        assertFalse(state.active)
        assertFalse(state.hasTimeLimit)
        assertFalse(state.expiryAlerted)
        assertEquals("1234", state.pin)
    }

    @Test
    fun startingAgainClearsALeftoverLockout() {
        start(minutes = 10)
        repeat(KidModeState.MAX_UNLOCK_ATTEMPTS) { fresh().registerFailedUnlock() }
        start(minutes = 10)
        assertEquals(0L, fresh().unlockBlockedSeconds)
    }
}
