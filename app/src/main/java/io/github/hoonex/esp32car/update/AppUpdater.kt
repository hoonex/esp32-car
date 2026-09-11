package io.github.hoonex.esp32car.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


enum class AppUpdateStage {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    WAITING_PERMISSION,
    INSTALLING,
    SIGNATURE_MISMATCH,
    ERROR
}

data class AppUpdateState(
    val stage: AppUpdateStage = AppUpdateStage.IDLE,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val progress: Int = 0,
    val message: String = "앱 업데이트 확인 대기",
    val releaseUrl: String = ""
)

object AppUpdater {
    private const val RELEASES_API = "https://api.github.com/repos/hoonex/esp32-car/releases?per_page=20"
    private const val RELEASES_PAGE = "https://github.com/hoonex/esp32-car/releases"
    private const val PREFS = "app_updater"
    private const val KEY_WAITING_PERMISSION = "waiting_unknown_sources_permission"
    private const val KEY_PENDING_APK = "pending_apk_path"
    private const val KEY_STAGED_APK = "staged_apk_path"
    private const val KEY_STAGED_VERSION = "staged_apk_version"

    private val running = AtomicBoolean(false)
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    @Volatile
    private var readyApk: File? = null

    @Volatile
    private var availableRelease: ReleaseInfo? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Restore a previously downloaded and validated APK, but never launch its installer automatically. */
    fun restoreStagedUpdate(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val path = prefs.getString(KEY_STAGED_APK, null) ?: return false
        val stagedVersion = prefs.getString(KEY_STAGED_VERSION, null).orEmpty()
        val apk = File(path)

        val updateRoot = updateDirectory(activity)
        val canonicalRoot = runCatching { updateRoot.canonicalFile }.getOrNull()
        val canonicalApk = runCatching { apk.canonicalFile }.getOrNull()
        if (
            canonicalRoot == null || canonicalApk == null ||
            !canonicalApk.path.startsWith(canonicalRoot.path + File.separator) ||
            !canonicalApk.isFile || canonicalApk.length() < 1024 * 1024
        ) {
            clearStagedUpdate(activity, deleteFile = true)
            return false
        }

        val current = runCatching { currentPackageInfo(activity) }.getOrNull() ?: return false
        val archive = archivePackageInfo(activity, canonicalApk)
        if (
            archive == null ||
            archive.packageName != activity.packageName ||
            PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(current) ||
            signingDigests(current) != signingDigests(archive)
        ) {
            clearStagedUpdate(activity, deleteFile = true)
            return false
        }

        readyApk = canonicalApk
        availableRelease = null
        val currentVersion = current.versionName.orEmpty().ifBlank { "0.0.0" }
        val latestVersion = archive.versionName.orEmpty().ifBlank { stagedVersion.ifBlank { "new" } }
        _state.value = AppUpdateState(
            stage = AppUpdateStage.READY,
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            progress = 100,
            message = "v$latestVersion 다운로드 및 검증 완료 · 설치 버튼을 누르세요"
        )
        return true
    }

    /** Check only release metadata. A newer APK is never downloaded until the user requests it. */
    suspend fun checkForUpdate(activity: Activity) {
        if (!running.compareAndSet(false, true)) return

        val current = currentPackageInfo(activity)
        val currentVersion = current.versionName.orEmpty().ifBlank { "0.0.0" }
        _state.value = AppUpdateState(
            stage = AppUpdateStage.CHECKING,
            currentVersion = currentVersion,
            message = "최신 공식 앱 버전 확인 중"
        )

        try {
            val release = withContext(Dispatchers.IO) { resolveLatestRelease(currentVersion) }
            if (release == null) {
                availableRelease = null
                readyApk = null
                clearStagedUpdate(activity, deleteFile = true)
                _state.value = AppUpdateState(
                    stage = AppUpdateStage.UP_TO_DATE,
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                    progress = 100,
                    message = "현재 앱이 최신 버전입니다"
                )
                return
            }

            availableRelease = release
            readyApk = null
            _state.value = AppUpdateState(
                stage = AppUpdateStage.AVAILABLE,
                currentVersion = currentVersion,
                latestVersion = release.version,
                progress = 0,
                message = "새 앱 v${release.version} 사용 가능 · 업데이트 버튼을 눌러 시작하세요",
                releaseUrl = release.releaseUrl
            )
        } catch (t: Throwable) {
            availableRelease = null
            readyApk = null
            _state.value = AppUpdateState(
                stage = AppUpdateStage.ERROR,
                currentVersion = currentVersion,
                latestVersion = _state.value.latestVersion,
                progress = 0,
                message = t.message ?: "앱 업데이트 확인 실패",
                releaseUrl = _state.value.releaseUrl
            )
        } finally {
            running.set(false)
        }
    }

    /** Download and validate the release selected by the metadata check. This is user initiated. */
    suspend fun downloadAvailableUpdate(activity: Activity) {
        if (!running.compareAndSet(false, true)) return

        val current = currentPackageInfo(activity)
        val currentVersion = current.versionName.orEmpty().ifBlank { "0.0.0" }
        try {
            val release = availableRelease ?: withContext(Dispatchers.IO) {
                resolveLatestRelease(currentVersion)
            }

            if (release == null) {
                availableRelease = null
                readyApk = null
                _state.value = AppUpdateState(
                    stage = AppUpdateStage.UP_TO_DATE,
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                    progress = 100,
                    message = "현재 앱이 최신 버전입니다"
                )
                return
            }

            availableRelease = release
            _state.value = AppUpdateState(
                stage = AppUpdateStage.DOWNLOADING,
                currentVersion = currentVersion,
                latestVersion = release.version,
                progress = 0,
                message = "v${release.version} 다운로드 중 · 0%",
                releaseUrl = release.releaseUrl
            )

            val downloaded = withContext(Dispatchers.IO) {
                downloadAndValidate(activity, current, release)
            }
            readyApk = downloaded.apk

            if (!downloaded.signatureMatches) {
                downloaded.apk.delete()
                readyApk = null
                clearStagedUpdate(activity, deleteFile = false)
                _state.value = AppUpdateState(
                    stage = AppUpdateStage.SIGNATURE_MISMATCH,
                    currentVersion = currentVersion,
                    latestVersion = release.version,
                    progress = 100,
                    message = "현재 설치본과 공식 릴리즈의 Android 서명이 다릅니다. 기존 앱 위에는 설치할 수 없습니다.",
                    releaseUrl = release.releaseUrl
                )
                return
            }

            persistStagedUpdate(activity, downloaded.apk, release.version)
            availableRelease = null
            _state.value = AppUpdateState(
                stage = AppUpdateStage.READY,
                currentVersion = currentVersion,
                latestVersion = release.version,
                progress = 100,
                message = "v${release.version} 다운로드 및 검증 완료 · 설치 버튼을 누르세요",
                releaseUrl = release.releaseUrl
            )
        } catch (t: Throwable) {
            readyApk = null
            _state.value = _state.value.copy(
                stage = AppUpdateStage.ERROR,
                currentVersion = currentVersion,
                message = t.message ?: "앱 업데이트 다운로드 실패"
            )
        } finally {
            running.set(false)
        }
    }

    fun installReadyUpdate(activity: Activity) {
        val apk = readyApk
        if (apk == null || !apk.isFile || apk.length() <= 0) {
            _state.value = _state.value.copy(
                stage = AppUpdateStage.ERROR,
                message = "설치할 업데이트 APK가 없습니다. 다시 확인해 주세요."
            )
            return
        }

        if (_state.value.stage == AppUpdateStage.SIGNATURE_MISMATCH) {
            _state.value = _state.value.copy(
                message = "서명이 다른 APK는 Android가 기존 앱 위에 설치하지 못합니다."
            )
            return
        }

        _state.value = _state.value.copy(
            stage = AppUpdateStage.INSTALLING,
            message = "Android 설치 화면 여는 중"
        )
        requestInstall(activity, apk)
    }

    fun openReleasePage(activity: Activity) {
        val url = _state.value.releaseUrl.ifBlank { RELEASES_PAGE }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun resumePendingInstall(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WAITING_PERMISSION, false)) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            _state.value = _state.value.copy(
                stage = AppUpdateStage.WAITING_PERMISSION,
                message = "Android의 '알 수 없는 앱 설치' 권한 대기 중"
            )
            return
        }

        val path = prefs.getString(KEY_PENDING_APK, null) ?: return
        val apk = File(path)
        prefs.edit().putBoolean(KEY_WAITING_PERMISSION, false).apply()
        if (apk.isFile && apk.length() > 0) {
            readyApk = apk
            _state.value = _state.value.copy(
                stage = AppUpdateStage.INSTALLING,
                message = "업데이트 설치 화면 여는 중"
            )
            launchPackageInstaller(activity, apk)
        }
    }

    private fun resolveLatestRelease(currentVersion: String): ReleaseInfo? {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ESP32-Car-Android/$currentVersion")
            .header("Cache-Control", "no-cache")
            .build()

        val releases = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub release HTTP ${response.code}")
            JSONArray(response.body?.string().orEmpty())
        }

        val candidates = mutableListOf<ReleaseInfo>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue

            val tag = release.optString("tag_name").trim()
            if (!tag.startsWith("android-v")) continue
            val version = normalizeReleaseVersion(tag)
            if (version.isBlank()) continue

            val assets = release.optJSONArray("assets") ?: continue
            var exactApk: JSONObject? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                if (isExpectedReleaseApkName(name, version)) {
                    exactApk = asset
                    break
                }
            }

            val apk = exactApk ?: continue
            val apkUrl = apk.optString("browser_download_url")
            if (apkUrl.isBlank()) continue
            val expectedDigest = normalizeSha256Digest(apk.optString("digest")) ?: continue

            candidates += ReleaseInfo(
                version = version,
                apkUrl = apkUrl,
                expectedDigest = expectedDigest,
                releaseUrl = release.optString("html_url").ifBlank { RELEASES_PAGE }
            )
        }

        val newest = candidates.maxWithOrNull(
            Comparator { left, right -> compareVersions(left.version, right.version) }
        ) ?: error("공식 Android APK 릴리즈를 찾지 못했습니다.")

        when (compareVersions(newest.version, currentVersion)) {
            -1 -> error(
                "최신 공개 앱은 v${newest.version}이고 현재 설치본은 v$currentVersion 입니다. " +
                    "현재 설치본이 공개 릴리즈보다 앞선 개발 버전입니다."
            )
            0 -> return null
        }

        return newest
    }

    private fun normalizeReleaseVersion(tag: String): String =
        tag.removePrefix("android-v").removePrefix("v").trim()

    private fun isExpectedReleaseApkName(name: String, version: String): Boolean =
        name.equals("ESP32-Car-v$version.apk", ignoreCase = true)

    private fun normalizeSha256Digest(raw: String): String? {
        val normalized = raw.trim().removePrefix("sha256:").lowercase()
        return normalized.takeIf { digest ->
            digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }
        }
    }

    private fun downloadAndValidate(
        activity: Activity,
        current: PackageInfo,
        release: ReleaseInfo
    ): DownloadedUpdate {
        val updateDir = updateDirectory(activity)
        val finalFile = File(updateDir, "ESP32-Car-v${release.version}.apk")
        val tempFile = File(updateDir, "download.tmp")
        if (tempFile.exists()) tempFile.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "ESP32-Car-Android/${current.versionName}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("APK download HTTP ${response.code}")
            val body = response.body ?: error("APK body missing")
            val total = body.contentLength()
            var received = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        val progress = if (total > 0) {
                            ((received * 100L) / total).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        _state.value = _state.value.copy(
                            stage = AppUpdateStage.DOWNLOADING,
                            progress = progress,
                            message = "v${release.version} 다운로드 중 · $progress%"
                        )
                    }
                    output.fd.sync()
                }
            }
        }

        if (tempFile.length() < 1024 * 1024) {
            tempFile.delete()
            error("다운로드된 APK가 비정상적으로 작습니다.")
        }
        val magic = tempFile.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())) {
            tempFile.delete()
            error("다운로드 파일이 APK 형식이 아닙니다.")
        }

        val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualDigest != release.expectedDigest) {
            tempFile.delete()
            error("APK SHA-256 검증 실패")
        }

        if (finalFile.exists()) finalFile.delete()
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }

        val archive = archivePackageInfo(activity, finalFile)
            ?: error("다운로드된 APK 메타데이터를 읽지 못했습니다.")
        if (archive.packageName != activity.packageName) {
            finalFile.delete()
            error("APK 패키지명이 다릅니다.")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(current)) {
            finalFile.delete()
            error("다운로드된 APK의 versionCode가 현재 설치본보다 새 버전이 아닙니다.")
        }

        return DownloadedUpdate(
            apk = finalFile,
            signatureMatches = signingDigests(current) == signingDigests(archive)
        )
    }

    private fun requestInstall(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WAITING_PERMISSION, true)
                .putString(KEY_PENDING_APK, apk.absolutePath)
                .apply()
            _state.value = _state.value.copy(
                stage = AppUpdateStage.WAITING_PERMISSION,
                message = "'알 수 없는 앱 설치' 권한을 허용하면 요청한 업데이트 설치를 계속합니다."
            )
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        launchPackageInstaller(activity, apk)
    }

    private fun launchPackageInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    private fun updateDirectory(activity: Activity): File =
        File(activity.filesDir, "app-updates").apply { mkdirs() }

    private fun persistStagedUpdate(activity: Activity, apk: File, version: String) {
        activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit()
            .putString(KEY_STAGED_APK, apk.absolutePath)
            .putString(KEY_STAGED_VERSION, version)
            .apply()
    }

    private fun clearStagedUpdate(activity: Activity, deleteFile: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val path = prefs.getString(KEY_STAGED_APK, null)
        if (deleteFile && !path.isNullOrBlank()) {
            runCatching { File(path).delete() }
        }
        prefs.edit()
            .remove(KEY_STAGED_APK)
            .remove(KEY_STAGED_VERSION)
            .apply()
    }

    private fun currentPackageInfo(activity: Activity): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun archivePackageInfo(activity: Activity, apk: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private data class ReleaseInfo(
        val version: String,
        val apkUrl: String,
        val expectedDigest: String,
        val releaseUrl: String
    )

    private data class DownloadedUpdate(
        val apk: File,
        val signatureMatches: Boolean
    )
}
