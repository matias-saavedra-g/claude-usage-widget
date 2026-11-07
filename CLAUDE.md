# CLAUDE.md

Notes for working on this repository in a Claude Code cloud session.

## Project

Android home-screen widget (Jetpack Glance) and companion app for Claude Pro/Max usage. Single module `:app`, Kotlin, compileSdk 36, minSdk 26, JDK 17. See README.md for features and the code map.

## Maven Central returns 429: use a mirror

Maven Central (`repo.maven.apache.org`) often answers **HTTP 429 Too Many Requests** to cloud session containers. That breaks Gradle dependency resolution, and Robolectric's own download of `android-all-instrumented` jars.

Use Google's public mirror of Maven Central, `https://maven-central.storage-download.googleapis.com/maven2/`. Configure it with a **user-level Gradle init script**, outside the repository, so the committed build and CI stay unchanged (GitHub Actions is not rate limited):

```bash
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/mirror.gradle <<'EOF'
// Local only: try Google's Maven Central mirror first (Maven Central rate-limits cloud containers).
beforeSettings { settings ->
    settings.pluginManagement.repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } }
    settings.dependencyResolutionManagement.repositories { maven { url "https://maven-central.storage-download.googleapis.com/maven2/" } }
}
allprojects {
    // Robolectric downloads android-all jars itself, not through Gradle.
    tasks.withType(Test).configureEach {
        systemProperty "robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/"
    }
}
EOF
```

How it works:
- `beforeSettings` adds the mirror **before** the repositories declared in `settings.gradle.kts`, so it is tried first. Gradle stops at a 429 instead of falling back to the next repository, so the order matters. Artifacts the mirror doesn't have (Google Maven, Gradle plugins) get a 404 and resolve from `google()` / `gradlePluginPortal()` as usual.
- Don't add `allprojects { repositories { ... } }`: `settings.gradle.kts` uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so project-level repositories fail the build.
- The `robolectric.dependency.repo.url` system property points Robolectric's `MavenArtifactFetcher` at the mirror. Without it, the render tests fail with an `IOException` from `HttpURLConnection` even when Gradle itself resolved everything.

If a single download still fails with 429, rerun the same Gradle command: Gradle caches what it already fetched.

## Android SDK in a cloud session

The container has no Android SDK. Install it once (about 1 GB):

```bash
mkdir -p /opt/android-sdk/cmdline-tools && cd /opt/android-sdk/cmdline-tools
curl -sSL -o ct.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q ct.zip && mv cmdline-tools latest && rm ct.zip
export ANDROID_HOME=/opt/android-sdk
yes | latest/bin/sdkmanager --licenses >/dev/null
latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=/opt/android-sdk" > /path/to/repo/local.properties   # gitignored
```

There is no KVM, so the Android emulator cannot run. Check UI with the Robolectric render tests instead.

## Build, test, check

- Build: `./gradlew :app:assembleDebug`
- Tests: `./gradlew :app:testDebugUnitTest`
  - `WidgetRenderTest` renders every widget style at common launcher sizes, in light and dark theme. It fails if any text is clipped or ellipsized, and writes contact sheets to `app/build/widget-renders/`. Look at them (Read the PNGs) after any widget layout change.
  - `WidgetConfigRenderTest` screenshots the widget style screen and checks per-widget settings.
  - Robolectric draws square corners and has no AndroidKeyStore, so `Storage` (EncryptedSharedPreferences) can't be read in tests. Real launchers apply the system corner radius.
- Lint: `./gradlew :app:lintDebug` (keep it at 0 errors; existing warnings are known).

## Widget layout rules

- Size classes are in `widget/UsageGlanceUi.kt` (`WidgetSizes`): widths 48/110/200 dp, heights 48/80/110/200 dp. They come from the cell math in Android's widget sizing guide. Layouts per style are in `widget/WidgetStyles.kt`.
- With `SizeMode.Responsive`, `LocalSize` is the size class, not the real size. Each layout must fit the smallest size of its class. Gauges use weights and `ContentScale` to fill extra room.
- Glance allows at most 10 children per `Row`/`Column`.
- Don't use `ColorProvider(@ColorRes)` from `androidx.glance.unit` (restricted API, lint error). For resource colors, use a drawable with `background(ImageProvider(...))`.

## Releases

On every push to `master` that touches app or build files, the **Build APK** workflow builds the APK. If `v<versionName>` has no tag yet, it also publishes a GitHub release. To release, bump `versionCode` and `versionName` in `app/build.gradle.kts`. Docs-only changes (README, CLAUDE.md) don't trigger the workflow.
