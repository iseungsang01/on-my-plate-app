# Repository Agent Instructions

## Android Release Workflow

- Preserve the current app version unless the user explicitly requests a version bump. The active release version is read from `.env` or CI environment variables via `ANDROID_VERSION_CODE` and `ANDROID_VERSION_NAME`.
- When the user asks to create an AAB, release, upload, publish, or deploy the Android app, automate the full Play Console deployment path instead of stopping at local bundle generation.
- Use `.\gradlew.bat :app:publishAab --no-daemon` on Windows for Play Console deployment. This task builds the signed release AAB and uploads it to the configured Google Play track through the Gradle Play Publisher plugin.
- Before publishing, verify that release signing and Play publishing environment values are present: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `PLAY_SERVICE_ACCOUNT_JSON_PATH`.
- Treat `PLAY_TRACK` and `PLAY_RELEASE_STATUS` as deployment controls from `.env` or CI. Do not silently change them; report the configured target track/status in the final response.
- If publishing fails because credentials, signing files, Gradle wrapper access, or network permissions are unavailable, surface the exact blocker and retry with the required approval when appropriate.
- After a successful publish, report the version name, version code, target track, release status, and generated AAB path.
