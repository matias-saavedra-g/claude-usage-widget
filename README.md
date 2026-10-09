# Claude Usage Widget

Android home-screen widget showing your Claude Pro/Max usage: the 5-hour and 1-week percentages and when each resets.

Maintained by [Matías Saavedra](https://github.com/matias-saavedra-g). Forked from [utaysi/claude-usage-widget](https://github.com/utaysi/claude-usage-widget) by [utaysi](https://github.com/utaysi).

<img src="docs/promo.png" alt="Widget on an Android home screen" width="720" />

## Changes in this fork

- Sign in by pasting your `sessionKey` cookie instead of using the in-app WebView login, which can fail on some phones.
- GitHub Actions builds the APK on every push.

## Requirements

- A Claude Pro or Max subscription. API-key usage is not shown.
- Android 8.0 or newer.

## Install

1. Open the [Actions](https://github.com/matias-saavedra-g/claude-usage-widget/actions) tab, select the latest successful **Build APK** run, and download the `claude-usage-widget` artifact. You must be signed in to GitHub.
2. Unzip it and install `app-debug.apk` on your phone. Allow installs from unknown sources when asked.
3. If you have the original app installed, uninstall it first. The two are signed with different keys.

## Setup

1. In a desktop browser signed in to [claude.ai](https://claude.ai), open DevTools → Application → Cookies → `https://claude.ai` and copy the value of `sessionKey` (starts with `sk-ant-sid`).
2. In the app, paste it, tap **Save session key**, then **Test fetch now**. The percentages should match [claude.ai/settings/usage](https://claude.ai/settings/usage).
3. Tap **Disable battery optimization** so Android does not stop the background refresh.
4. Add the **Claude Usage** widget to your home screen. Tap it to refresh.

The session key expires after a few weeks, or when you sign out of claude.ai in that browser. When the widget says to sign in, paste a new key.

## How it works

The app calls `claude.ai/api/organizations/{orgId}/usage`, the same undocumented endpoint the claude.ai usage page uses, with your `sessionKey` cookie. It finds your organization ID on its own. When Cloudflare blocks a request, the app loads claude.ai in a hidden WebView to get a new `cf_clearance` cookie and retries. A background job refreshes every 15 minutes.

The widget has a compact and a full layout depending on its size, and follows the system light or dark theme. An amber dot means the data is out of date.

Anthropic does not support this. It may stop working if the endpoint or Cloudflare setup changes.

## Security

The session key gives full access to your Claude account. The app stores it with `EncryptedSharedPreferences` and excludes it from backups. Never put it in the source code or share it.

## Build locally

Requires JDK 17 and the Android SDK with platform 36.

1. Create `local.properties` in the project root with `sdk.dir=/path/to/Android/Sdk`.
2. Run `./gradlew :app:assembleDebug`.
3. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

| Path | Contents |
| --- | --- |
| `data/` | Endpoints, encrypted storage, cookie handling, Cloudflare retry, fetch logic |
| `auth/LoginActivity.kt` | WebView login |
| `ui/MainActivity.kt` | Setup screen and session key entry |
| `widget/` | Widget layouts |
| `work/` | Background refresh |
