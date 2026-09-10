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
            currentVersion = "4.0.4",
            latestVersion = "4.0.5",
            message = "update available"
        )

        assertEquals("4.0.4", state.currentVersion)
        assertEquals("4.0.5", state.latestVersion)
        assertEquals(AppUpdateStage.AVAILABLE, state.stage)
    }

    @Test
    fun versionComparatorRecognizesCurrentManualUpdateTarget() {
        assertTrue(compareVersions("4.0.5", "4.0.4") > 0)
        assertEquals(0, compareVersions("4.0.5", "4.0.5"))
        assertTrue(compareVersions("4.0.4", "4.0.5") < 0)
    }

    @Test
    fun versionComparatorUsesNumericSegmentsInsteadOfLexicalOrdering() {
        assertTrue(compareVersions("4.0.10", "4.0.9") > 0)
        assertTrue(compareVersions("4.10.0", "4.9.99") > 0)
    }

    @Test
    fun versionComparatorTreatsMissingSegmentsAsZero() {
        assertEquals(0, compareVersions("4.0", "4.0.0"))
        assertTrue(compareVersions("4.0.1", "4.0") > 0)
    }

    @Test
    fun versionComparatorIgnoresSuffixForNumericReleaseOrdering() {
        assertEquals(0, compareVersions("4.0.5-preview", "4.0.5"))
        assertTrue(compareVersions("4.0.6-preview", "4.0.5") > 0)
    }

    @Test
    fun androidReleaseTagNormalizationKeepsExpectedVersion() {
        assertEquals("4.0.5", normalizeReleaseVersion("android-v4.0.5"))
    }

    private fun compareVersions(left: String, right: String): Int =
        invokePrivate("compareVersions", left, right) as Int

    private fun normalizeReleaseVersion(tag: String): String =
        invokePrivate("normalizeReleaseVersion", tag) as String

    private fun invokePrivate(name: String, vararg args: String): Any {
        val parameterTypes = Array(args.size) { String::class.java }
        val method = AppUpdater::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(AppUpdater, *args)
    }
}
