# Releasing

## One-time: a release signing key

Android only installs an update over an existing install when both are signed with the same key.
Sign public builds with a dedicated key that you back up, not a machine's debug key.

1. Create the key once and keep it outside the repository (back it up — a lost key means users
   must uninstall before they can update):

   ```bash
   keytool -genkeypair -v -keystore ~/keys/facebridge-release.jks -alias facebridge -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` in the project root. It is git-ignored; never commit it:

   ```properties
   storeFile=/home/you/keys/facebridge-release.jks
   storePassword=…
   keyAlias=facebridge
   keyPassword=…
   ```

Without this file, `assembleRelease` falls back to the local debug key.

Switching keys: users who installed a build signed with a different key (for example a
debug-signed one) must uninstall it once (`adb uninstall io.github.staperfeed.facebridge`) and grant
the permissions again.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and update `CHANGELOG.md`.
2. If the launcher icon changed, bumping `versionCode` is required, because PICO's launcher caches icons per version.
3. Build and check the signature:

   ```bash
   ./gradlew clean assembleRelease
   ```

   ```bash
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

4. Test on the headset: install, Start, VRChat with a face-tracked avatar → Tracking; an avatar without
   face tracking → standby; close VRChat → Waiting for VRChat. Run a calibration.
5. Refresh the screenshots if the UI changed (with the headset awake and the PICO home screen in front):

   ```bash
   adb shell am start -n io.github.staperfeed.facebridge/.AdbControl --ez screenshots true
   ```

   ```bash
   adb shell am start -n io.github.staperfeed.facebridge/.AdbControl --ez calibrate true --ez screenshots true
   ```

   ```bash
   adb pull /sdcard/Android/data/io.github.staperfeed.facebridge/files/screenshots/. docs/screenshots/
   ```

6. Commit, tag and push:

   ```bash
   git tag -a v2.3.0 -m "Face Bridge v2.3.0"
   ```

   ```bash
   git push origin main --tags
   ```

7. Create the GitHub release from the tag. Attach the APK renamed to `FaceBridge-v2.3.0.apk`, and
   paste the CHANGELOG section plus the install steps from the README.

   ```bash
   cp app/build/outputs/apk/release/app-release.apk FaceBridge-v2.3.0.apk
   ```

   ```bash
   gh release create v2.3.0 FaceBridge-v2.3.0.apk --title "Face Bridge v2.3.0" --notes-file release-notes.md
   ```

APKs are not committed (`releases/` is git-ignored); they belong on the Releases page.
