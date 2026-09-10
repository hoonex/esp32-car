# Android release pipeline

Official Android releases are produced without committing signing material or requiring a local bootstrap step.

The normal `Build Android + Firmware` workflow validates firmware, the updater contract, Android unit tests, lint, and a debug APK. It does not receive the persistent Android signing identity.

After a successful `main` push build, `Dispatch Trusted Android Release` resolves the exact validated `main` commit, creates or verifies the immutable `android-signing-v1` anchor tag, and dispatches the trusted release workflow on that tag. The trusted workflow separately checks out the validated source commit for the actual build.

The persistent signing identity is stored only in tag-scoped GitHub Actions caches. Primary and backup cache copies must agree. If both copies are lost after an official Android release exists, the workflow fails rather than silently generating a replacement signer. Every release records the Android signing-certificate SHA-256 so later releases can prove signer continuity.

The first transition from a CI/debug-signed installation to the persistent-signed release may require one uninstall/reinstall because Android does not allow an in-place signer change. Subsequent releases can update in place while the signing lineage is retained.
