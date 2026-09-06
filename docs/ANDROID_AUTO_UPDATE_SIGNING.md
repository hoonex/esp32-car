# Android auto-update signing bootstrap

The in-app updater can only replace an installed APK when every release is signed by the same Android signing identity. GitHub Actions debug keys are ephemeral, so debug artifacts from different runs are not a valid update channel.

## One-time setup

1. On a trusted local machine, run `tools/android/bootstrap-release-signing.ps1` from PowerShell.
2. The script creates a private `release-signing.jks` and a local `.github-release-signing.env` file. Both are ignored by git.
3. In GitHub repository **Settings → Secrets and variables → Actions**, create these repository secrets from `.github-release-signing.env`:
   - `ANDROID_KEYSTORE_B64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_ALIAS`
   - `ANDROID_KEY_PASSWORD`
4. Keep the generated JKS in an offline/private backup. Do not regenerate or publish it.
5. Push/merge a new Android version to `main`. The existing workflow will decode the keystore, produce the persistent-signed release APK, verify its signature, and publish the Android release.

## Existing debug installs

An APK already installed from a CI debug artifact has a different signer. Android will reject a persistent-signed APK as an in-place update. That transition requires one uninstall/reinstall from the first persistent-signed release. From that release onward, the app updater can perform normal in-place updates as long as the same signing key is retained.

## Release-channel invariant

The app updater scans published, non-draft, non-prerelease `android-v*` releases and only considers releases that actually contain an APK. The selected APK must satisfy all of the following before installation is offered:

- newer semantic app version and Android `versionCode`
- matching application package name
- valid APK/ZIP header
- GitHub SHA-256 digest match when the release asset exposes a digest
- same Android signing identity as the currently installed app

If the installed app is a test build newer than the newest published Android release, the UI reports that the official channel is behind instead of incorrectly saying that the test build is current.
