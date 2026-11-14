# Shortcut Decoder for Android

This is a separate Android WebView app for Shortcut Decoder. It bundles its own copy of the web interface at `app/src/main/assets/www/index.html` and uses a small native Java bridge for public iCloud Shortcut links.

## What is different

- The original repository-root `index.html` is not changed or removed.
- The Android copy first calls the native bridge, which requests the public iCloud record and downloads its `.shortcut` file over HTTPS.
- The existing browser fetch, manual fallback and proxy fallback remain in the bundled page for non-Android use.
- The AEA and plist decoder runs locally in the bundled page after download.

The iCloud record endpoint is an undocumented Apple endpoint. Apple can change it, and this implementation only supports publicly shared links that expose a downloadable record. It does not access private shortcuts or bypass Apple account permissions.

## Build

Open this directory in Android Studio, or run:

```sh
gradle assembleDebug
```

The debug APK is created at:

```
app/build/outputs/apk/debug/app-debug.apk
```

A GitHub Actions workflow also builds and uploads the debug APK as an artifact for commits on `feature/android-icloud-native-v2`.
