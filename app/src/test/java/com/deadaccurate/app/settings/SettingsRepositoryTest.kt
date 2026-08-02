package com.deadaccurate.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun runWithRepository(
        block: suspend (SettingsRepository, reopen: suspend () -> SettingsRepository) -> Unit,
    ) = runTest {
        val file = File(tempFolder.root, "test.preferences_pb")
        var scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        var repository =
            SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

        val reopen: suspend () -> SettingsRepository = {
            scope.cancel()  // release the file lock before reopening
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            repository =
                SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            repository
        }
        try {
            block(repository, reopen)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun defaultsWhenNothingStored() = runWithRepository { repository, _ ->
        assertEquals(UserSettings(), repository.settings.first())
    }

    @Test
    fun roundTripsAllSettings() = runWithRepository { repository, _ ->
        repository.setGateTrimDb(-7.5f)
        repository.setBphOverride(21600)
        repository.setInputPreference(InputPreference.BUILT_IN)
        repository.setOnboardingDismissed(true)
        repository.setClockCalSecPerDay(-1.7f)

        assertEquals(
            UserSettings(
                gateTrimDb = -7.5f,
                bphOverride = 21600,
                inputPreference = InputPreference.BUILT_IN,
                onboardingDismissed = true,
                clockCalSecPerDay = -1.7f,
            ),
            repository.settings.first(),
        )
    }

    @Test
    fun clearingBphOverrideReturnsToAuto() = runWithRepository { repository, _ ->
        repository.setBphOverride(28800)
        repository.setBphOverride(null)
        assertEquals(null, repository.settings.first().bphOverride)
    }

    @Test
    fun settingsSurviveReopen() = runWithRepository { repository, reopen ->
        repository.setGateTrimDb(4f)
        repository.setInputPreference(InputPreference.BUILT_IN)

        val reopened = reopen()
        val loaded = reopened.settings.first()
        assertEquals(4f, loaded.gateTrimDb)
        assertEquals(InputPreference.BUILT_IN, loaded.inputPreference)
    }
}
