package com.deadaccurate.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimegrapherUiStateTest {

    @Test
    fun correctedRateAddsClockCalibration() {
        val state = TimegrapherUiState(secPerDay = 5.0f, clockCalSecPerDay = -1.7f)
        assertEquals(3.3f, state.correctedSecPerDay, 1e-4f)
    }

    @Test
    fun zeroCalibrationLeavesMeasurementUntouched() {
        val state = TimegrapherUiState(secPerDay = -8.2f)
        assertEquals(-8.2f, state.correctedSecPerDay, 1e-4f)
    }
}
