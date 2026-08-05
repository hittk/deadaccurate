package com.deadaccurate.app.watchlog

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies the real org.json the repository serializes with.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchLogRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun measurement(secPerDay: Float = 3.2f) = Measurement(
        timestampMs = 1_722_800_000_000,
        bph = 28800,
        secPerDay = secPerDay,
        beatErrorMs = 0.4f,
        mode = "CORRELATION",
        bandScores = listOf(1.2f, 3.4f, 7.1f, 2.0f),
    )

    @Test
    fun savedMeasurementsSurviveReload() = runTest {
        val file = File(tempFolder.root, "watch_log.json")
        val repository = WatchLogRepository(file)
        repository.load()

        val watch = repository.saveMeasurement(
            watchId = null,
            newWatchName = "SKX007",
            movementRef = "NH35",
            measurement = measurement(),
        )
        repository.saveMeasurement(
            watchId = watch.id,
            newWatchName = null,
            movementRef = null,
            measurement = measurement(secPerDay = 2.8f),
        )

        val reopened = WatchLogRepository(file)
        reopened.load()
        val entries = reopened.watches.value
        assertEquals(1, entries.size)
        assertEquals("SKX007", entries[0].name)
        assertEquals("NH35", entries[0].movementRef)
        assertEquals(2, entries[0].measurements.size)
        // Newest first; full fidelity round-trip.
        assertEquals(2.8f, entries[0].measurements[0].secPerDay, 1e-6f)
        assertEquals(
            listOf(1.2f, 3.4f, 7.1f, 2.0f),
            entries[0].measurements[1].bandScores,
        )
    }

    @Test
    fun blankMovementRefDoesNotEraseAnEarlierLabel() = runTest {
        val repository = WatchLogRepository(File(tempFolder.root, "log.json"))
        repository.load()
        val watch = repository.saveMeasurement(null, "GMT", "NH34", measurement())
        repository.saveMeasurement(watch.id, null, "  ", measurement())
        assertEquals("NH34", repository.watches.value[0].movementRef)
    }

    @Test
    fun corruptFileStartsFreshInsteadOfCrashing() = runTest {
        val file = File(tempFolder.root, "watch_log.json")
        file.writeText("{not json")
        val repository = WatchLogRepository(file)
        repository.load()
        assertTrue(repository.watches.value.isEmpty())
    }

    @Test
    fun deleteRemovesTheWatch() = runTest {
        val repository = WatchLogRepository(File(tempFolder.root, "log.json"))
        repository.load()
        val watch = repository.saveMeasurement(null, "Speedy", null, measurement())
        assertNull(repository.watches.value[0].movementRef)
        repository.deleteWatch(watch.id)
        assertTrue(repository.watches.value.isEmpty())
    }
}
