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
        amplitudeDeg = 271.5f,
        mode = "CORRELATION",
        bandEnergies = listOf(1.2f, 3.4f, 7.1f, 2.0f),
        input = "wired",
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
            entries[0].measurements[1].bandEnergies,
        )
        assertEquals("wired", entries[0].measurements[1].input)
        assertEquals(271.5f, entries[0].measurements[0].amplitudeDeg!!, 1e-4f)
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
    fun updateCorrectsAMislabeledMovement() = runTest {
        val file = File(tempFolder.root, "log.json")
        val repository = WatchLogRepository(file)
        repository.load()
        // The real mistake this exists for: NH35 typed for an NH34 watch.
        val watch = repository.saveMeasurement(null, "Blizzard", "NH35", measurement())
        repository.updateWatch(watch.id, "Blizzard", "NH34")
        assertEquals("NH34", repository.watches.value[0].movementRef)
        // Survives reload; measurements untouched.
        val reopened = WatchLogRepository(file)
        reopened.load()
        assertEquals("NH34", reopened.watches.value[0].movementRef)
        assertEquals(1, reopened.watches.value[0].measurements.size)
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
