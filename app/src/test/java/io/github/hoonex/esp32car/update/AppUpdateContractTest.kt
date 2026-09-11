package io.github.hoonex.esp32car.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun releaseApkSelectionRequiresExactVersionedAssetName() {
        assertTrue(isExpectedReleaseApkName("ESP32-Car-v4.0.5.apk", "4.0.5"))
        assertTrue(isExpectedReleaseApkName("esp32-car-v4.0.5.APK", "4.0.5"))
        assertFalse(isExpectedReleaseApkName("ESP32-Car-v4.0.4.apk", "4.0.5"))
        assertFalse(isExpectedReleaseApkName("another-app.apk", "4.0.5"))
    }

    @Test
    fun githubSha256DigestMustBePresentAndWellFormed() {
        val upper = "A".repeat(64)
        assertEquals("a".repeat(64), normalizeSha256Digest("sha256:$upper"))
        assertEquals("b".repeat(64), normalizeSha256Digest("b".repeat(64)))
        assertNull(normalizeSha256Digest(""))
        assertNull(normalizeSha256Digest("sha256:abcd"))
        assertNull(normalizeSha256Digest("sha256:${"g".repeat(64)}"))
    }

    private fun compareVersions(left: String, right: String): Int =
        invokePrivate("compareVersions", left, right) as Int

    private fun normalizeReleaseVersion(tag: String): String =
        invokePrivate("normalizeReleaseVersion", tag) as String

    private fun isExpectedReleaseApkName(name: String, version: String): Boolean =
        invokePrivate("isExpectedReleaseApkName", name, version) as Boolean

    private fun normalizeSha256Digest(raw: String): String? =
        invokePrivate("normalizeSha256Digest", raw) as String?

    private fun invokePrivate(name: String, vararg args: String): Any? {
        val parameterTypes = Array(args.size) { String::class.java }
        val method = AppUpdater::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(AppUpdater, *args)
    }
}
