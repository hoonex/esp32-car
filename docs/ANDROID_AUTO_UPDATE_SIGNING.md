# Android automatic app updates

The app has a real self-update path: it checks official GitHub `android-v*` releases, downloads a newer APK, validates it, stages it persistently, and opens Android's installer at a safe time when the car is not actively connected.

Android requires every in-place update of an installed app to be signed by the same private signing identity. CI debug keys are not a valid release channel because their signer is not guaranteed to remain the same. The repository is public, so the release keystore must **never** be committed to Git.

## One-time release-signing bootstrap

Use a trusted Windows machine with JDK 17+ and GitHub CLI installed.

1. Authenticate GitHub CLI once:

   ```powershell
   gh auth login
   ```

2. From the repository root run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\android\bootstrap-release-signing.ps1
   ```

3. The script automatically:
   - creates the long-lived `release-signing.jks` signing key;
   - creates `.github-release-signing.env` as a local recovery copy;
   - uploads `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` as GitHub Actions repository secrets;
   - triggers the Android release workflow on `main`.

4. Back up `release-signing.jks` and `.github-release-signing.env` somewhere private/offline. Do not regenerate the key and do not commit either file. Both paths are gitignored.

If GitHub CLI is unavailable, the script still generates the key and local recovery file. In that case the four values can be added manually under **Settings → Secrets and variables → Actions**. `-NoGitHubUpload` can also be used intentionally for this manual mode.

## First transition from CI debug APK

An APK currently installed from an old CI debug artifact may have a different signer. Android will not accept the first persistent-signed release as an in-place replacement. That transition therefore requires **one uninstall/reinstall** from the first official persistent-signed APK.

After that one transition, all later official Android releases use the same signing key and can update in place through the app. Losing or regenerating the private release key breaks that update lineage.

## Runtime update behavior

On app launch the updater first restores any previously downloaded verified APK. If none is staged, it checks the public GitHub release channel. A newer release is downloaded in the background and validated before installation is possible.

The APK must pass all of these checks:

- published, non-draft, non-prerelease `android-v*` release;
- newer semantic app version and Android `versionCode`;
- matching application package name;
- valid APK/ZIP structure;
- GitHub SHA-256 asset digest match when GitHub exposes a digest;
- same Android signing identity as the currently installed app.

A verified APK is stored in the app's persistent files directory, so a completed download survives process restarts. If the car is connected, installation is deferred instead of interrupting a drive. Once Bluetooth is disconnected, or on the next launch before reconnecting, the app continues the install flow automatically.

Android 8+ may ask once for this app's **Install unknown apps** permission. The app resumes the already-downloaded update after that permission is granted.

## Android system limitation

A normal sideloaded Android application cannot silently replace itself. The app can perform release discovery, download, validation, staging, and launch the installer automatically, but Android still shows the system package-installer confirmation unless the phone is managed as a device owner or is rooted. This confirmation is an OS security boundary, not an app limitation that should be bypassed.
