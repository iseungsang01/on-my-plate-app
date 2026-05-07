# On My Plate Native Planner

Native Android Kotlin MVP for shared-text appointment ingestion.

## Run

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on a device or emulator.

Debug builds do not require Android release signing or Google Play publishing variables. For a local debug build, provide only app/runtime values such as `ANDROID_APPLICATION_ID`, version fields, and optional Gemini settings when needed.

Build a debug APK with the Gradle Wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
```

On Unix-like shells:

```sh
./gradlew :app:assembleDebug
```

The app registers an Android `ACTION_SEND` target for `text/plain`. Share text from KakaoTalk, SMS, memo apps, browsers, or any Android app into `On My Plate Planner`; the share receiver parses the text, creates an appointment candidate, and shows a native notification with inline title input and actions.

## MVP Scope

- Native Android Kotlin, Jetpack Compose, Room, coroutines.
- Korean rule-based parser for date/time/location/title extraction.
- Notification actions with `RemoteInput`: `확정`, `예정`, `미정`.
- Local conflict detection using Room schedules.
- Candidate edit and conflict resolution screens.

No KakaoTalk scraping, login, background chat monitoring, web app, cloud sync, or LLM API is included.

## Native widget snapshot scope

The native Android widget is part of this MVP and renders only Room-backed schedules saved by the native planner.

- `PlannerWidgetSync` writes a `native-room-schedules-v1` summary snapshot.
- The snapshot includes `manualEventsByDate` grouped by local date in `Asia/Seoul`.
- It intentionally does not write `autoPlans`, category columns, or generated `days`; the native widget derives the visible week from `manualEventsByDate`.
- The reusable `widget/` bundle has a wider snapshot model for host apps that already have manual daily schedules plus auto/category plans. See `widget/README.md` for that contract.

## AAB auto-update flow

- The app checks Google Play In-App Updates on launch and foreground return.
- If Play reports a newer AAB version and allows an immediate update, the Play update UI opens automatically.
- If an immediate update was interrupted, the app resumes it from `onResume()`.
- Before publishing a new AAB, increase `ANDROID_VERSION_CODE` and optionally `ANDROID_VERSION_NAME` in `.env` or CI env vars.
- Signed release builds require `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.
- Play publishing also requires `PLAY_SERVICE_ACCOUNT_JSON_PATH`; `PLAY_TRACK` defaults to `internal` and `PLAY_RELEASE_STATUS` defaults to `DRAFT`.
- Upload with `publishAab`; for user-visible updates, publish to a Play track with a non-draft release status.

