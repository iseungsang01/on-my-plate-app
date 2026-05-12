# 약속 바구니 Native Planner

Native Android Kotlin MVP for shared-text appointment ingestion.

## Product Logic

The app turns shared text into a schedule candidate, but it does not decide the appointment title.

1. The user shares `text/plain` content from KakaoTalk, SMS, a memo app, a browser, or any Android share source into the app.
2. `ShareReceiverActivity` receives the text and asks for notification permission when needed.
3. `PlannerRepository.createCandidate` parses the shared text into appointment metadata:
   - start time
   - optional end time
   - optional location
   - confidence/time-confidence values
4. The parser intentionally leaves the appointment title blank. The title must be typed by the user.
5. `AppointmentNotificationManager.showCandidate` posts a native notification showing the parsed time/location and asking for the title.
6. From the notification the user can:
   - type a title and save as confirmed
   - type a title and save as uncertain
   - open the app to edit title, time, end time, and location before saving
7. `PlannerRepository.saveFromCandidate` refuses to create a schedule if the title is still blank.
8. If the candidate overlaps an existing schedule, the app shows a conflict notification/screen before saving unless the user explicitly forces the add.
9. Saved schedules are stored through the Supabase `planner-api` for the signed-in account and synced into the native home-screen widget snapshot.

See `docs/SPECIFICATION.md` for the file-level implementation map.

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

The app registers an Android `ACTION_SEND` target for `text/plain`. Share text from KakaoTalk, SMS, memo apps, browsers, or any Android app into `약속 바구니`; the share receiver parses time/location details, creates an appointment candidate with an empty title, and shows a native notification with inline title input and actions.

## MVP Scope

- Native Android Kotlin, Jetpack Compose, coroutines, and Supabase Edge Function-backed planner data.
- Korean rule-based parser plus optional Gemini LLM parsing for shared text metadata.
- Appointment title is user-entered text, not parsed from the shared text.
- Notification actions with `RemoteInput`: `확정 저장`, `미정 저장`, `세부 수정`.
- Conflict detection using the signed-in user's Supabase-backed personal schedules.
- Candidate edit and conflict resolution screens.

No KakaoTalk scraping, background chat monitoring, or web app is included. Login is required for schedule features.

## Native widget snapshot scope

The native Android widget is part of this MVP and renders the signed-in user's personal schedules fetched through `planner-api`.

- `PlannerWidgetSync` writes a `native-supabase-schedules-v1` summary snapshot.
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

## Supabase Edge Function planner API

The Android app uses a single Supabase Edge Function, `planner-api`, for app login, candidate storage, personal schedules, widget sync, and sharing. Configure `PLANNER_API_BASE_URL` in `.env` (or CI env) as `https://<project-ref>.supabase.co/functions/v1/planner-api`. Supabase Auth is not used; `planner-api` uses the app's own `planner_users` rows, creates a user row on first login, stores only a user-id salted password hash, returns the user id as the app session token, and then writes to Supabase with server-only service-role credentials.

The Android app never stores service-role credentials, does not read the old `on_my_plate_native.db` Room database, and no longer depends on any PC-local backend. Shared-screen-only fake entries live in `planner_dummy_schedules` and are never copied into personal schedules or the home widget. See `docs/supabaseSQL.md` for the server-side schema and RLS posture.
