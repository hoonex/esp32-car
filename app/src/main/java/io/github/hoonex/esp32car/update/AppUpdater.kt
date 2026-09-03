package io.github.hoonex.esp32car.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/hoonex/esp32-car/releases/latest"
    private const val PREFS = "app_updater"
    private const val KEY_WAITING_PERMISSION = "waiting_unknown_sources_permission"
    private const val KEY_PENDING_APK = "pending_apk_path"

    private val running = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkForUpdate(activity: Activity, installWhenReady: Boolean = true) {
        if (!running.compareAndSet(false, true)) return
        try {
            val result = withContext(Dispatchers.IO) { resolveAndDownloadUpdate(activity) } ?: return
            if (!result.signatureMatches) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        "새 APK는 찾았지만 기존 설치본과 서명이 달라 자동 업데이트할 수 없습니다. 새 서명 체계로 1회 재설치가 필요합니다.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
            if (installWhenReady) {
                withContext(Dispatchers.Main) { requestInstall(activity, result.apk) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed: ${t.message}", t)
        } finally {
            running.set(false)
        }
    }

    fun resumePendingInstall(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WAITING_PERMISSION, false)) return
        if (!activity.packageManager.canRequestPackageInstalls()) return

        val path = prefs.getString(KEY_PENDING_APK, null) ?: return
        val apk = File(path)
        prefs.edit().putBoolean(KEY_WAITING_PERMISSION, false).apply()
        if (apk.isFile && apk.length() > 0) launchPackageInstaller(activity, apk)
    }

    private fun resolveAndDownloadUpdate(activity: Activity): DownloadedUpdate? {
        val current = currentPackageInfo(activity)
        val currentVersion = current.versionName.orEmpty().ifBlank { "0.0.0" }

        val releaseRequest = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ESP32-Car-Android/${currentVersion}")
            .header("Cache-Control", "no-cache")
            .build()

        val releaseJson = client.newCall(releaseRequest).execute().use { response ->
            if (!response.isSuccessful) error("GitHub release HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }

        val latestVersion = releaseJson.optString("tag_name")
            .removePrefix("android-v")
            .removePrefix("v")
            .trim()
        if (latestVersion.isBlank() || compareVersions(latestVersion, currentVersion) <= 0) return null

        val assets = releaseJson.optJSONArray("assets") ?: return null
        var apkAsset: JSONObject? = null
        for (i in 0 until assets.length()) {
            val candidate = assets.optJSONObject(i) ?: continue
            val name = candidate.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkAsset = candidate
                break
            }
        }
        val asset = apkAsset ?: error("Release has no APK asset")
        val url = asset.optString("browser_download_url").ifBlank { error("APK URL missing") }
        val expectedDigest = asset.optString("digest").removePrefix("sha256:").lowercase()

        val updateDir = File(activity.cacheDir, "app-updates").apply { mkdirs() }
        val finalFile = File(updateDir, "ESP32-Car-v${latestVersion}.apk")
        val tempFile = File(updateDir, "download.tmp")
        if (tempFile.exists()) tempFile.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val downloadRequest = Request.Builder()
            .url(url)
            .header("User-Agent", "ESP32-Car-Android/${currentVersion}")
            .build()

        client.newCall(downloadRequest).execute().use { response ->
            if (!response.isSuccessful) error("APK download HTTP ${response.code}")
            val body = response.body ?: error("APK body missing")
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        }

        if (tempFile.length() < 1024 * 1024) error("Downloaded APK is unexpectedly small")
        val magic = tempFile.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte())) error("Downloaded file is not an APK/ZIP")

        val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedDigest.isNotBlank() && actualDigest != expectedDigest) {
            tempFile.delete()
            error("APK SHA-256 mismatch")
        }

        if (finalFile.exists()) finalFile.delete()
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }

        val archive = archivePackageInfo(activity, finalFile) ?: error("Downloaded APK package metadata is invalid")
        if (archive.packageName != activity.packageName) error("Downloaded APK package name mismatch")
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(current)) {
            finalFile.delete()
            return null
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
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }.onFailure {
                Log.w(TAG, "Unable to open unknown-sources settings", it)
            }
            return
        }
        launchPackageInstaller(activity, apk)
    }

    private fun launchPackageInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { Log.e(TAG, "Unable to launch package installer", it) }
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

    private data class DownloadedUpdate(
        val apk: File,
        val signatureMatches: Boolean
    )
}
