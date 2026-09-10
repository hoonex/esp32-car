package io.github.hoonex.esp32car.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateContractTest {
    @Test
    fun availableStageIsDistinctFromDownloading() {
        assertTrue(AppUpdateStage.AVAILABLE != AppUpdateStage.DOWNLOADING)
    }

    @Test
    fun availableStateCarriesCurrentAndLatestVersions() {
        val state = AppUpdateState(
            stage = AppUpdateStage.AVAILABLE,
            currentVersion = "4.0.3",
            latestVersion = "4.0.4",
            message = "update available"
        )

        assertEquals("4.0.3", state.currentVersion)
        assertEquals("4.0.4", state.latestVersion)
        assertEquals(AppUpdateStage.AVAILABLE, state.stage)
    }
}
