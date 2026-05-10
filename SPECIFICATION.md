# On My Plate Native Planner Specification

This document describes the current native Android implementation. It is intentionally code-facing: use it to understand where each behavior lives before changing the app.

## Core Flow

1. Android receives shared `text/plain` through `ShareReceiverActivity`.
2. The shared text is stored as an `AppointmentCandidateEntity`.
3. `KoreanAppointmentParser` parses local Korean date/time/location patterns.
4. If configured, `GeminiAppointmentParser` can parse the same metadata through Gemini and merge with the local fallback.
5. Parsers do not extract an appointment title. The title field stays blank until the user types it.
6. `AppointmentNotificationManager` shows a candidate notification with parsed time/location and inline title input.
7. `NotificationActionReceiver` handles notification actions and forwards saves to `PlannerRepository`.
8. `PlannerRepository.saveFromCandidate` requires a non-blank title, checks conflicts, inserts a `ScheduleEntity`, and marks the candidate handled.
9. `MainActivity` hosts Compose screens for planner, candidate edit, and conflict resolution.
10. Saved schedules flow from Room to `PlannerWidgetSync`, then to the native widget snapshot.

## Data Rules

- `rawText`: the exact shared source text.
- `extractedTitle`: user-entered title. It is intentionally empty after parsing.
- `extractedStartAt`: parsed start time in epoch milliseconds, nullable.
- `extractedEndAt`: parsed explicit end time in epoch milliseconds, nullable.
- `extractedLocation`: parsed location when present, otherwise null/blank in UI.
- `confidence`: parser confidence from `0.0` to `1.0`.
- `timeConfidence`: `high`, `medium`, or `low`.
- A schedule cannot be saved until the title is non-blank.
- If start time is missing or the user chooses uncertain, the schedule is saved as `uncertain` using the candidate creation time as fallback start.
- Conflict detection assumes a default one-hour duration when an end time is missing.

## File Map

### App Root

`app/src/main/AndroidManifest.xml`

- Declares `INTERNET` and `POST_NOTIFICATIONS`.
- Registers `ShareReceiverActivity` as an exported `ACTION_SEND` target for `text/plain`.
- Registers `MainActivity`, `NotificationActionReceiver`, and `SummaryWidgetProvider`.

`app/src/main/java/com/lss/onmyplate/nativeplanner/OnMyPlateApp.kt`

- Application-level dependency holder.
- Creates `AppDatabase`, `KoreanAppointmentParser`, `PlannerRepository`, and `AppointmentNotificationManager`.
- Wires Gemini settings from `BuildConfig`.
- Starts observing schedules and writes widget snapshots through `PlannerWidgetSync`.

### Share Input

`share/ShareReceiverActivity.kt`

- `onCreate`: validates that the incoming intent is `ACTION_SEND` with `text/plain`.
- Stores pending shared text/source metadata while notification permission is requested.
- `saveAndNotify`: creates a candidate through the repository, shows the candidate notification, then closes the transparent activity.
- `needsNotificationPermission`: checks Android 13+ notification permission.

### Parsing

`domain/parser/AppointmentLlmParser.kt`

- Small interface for async LLM parsers.
- Returns `AppointmentParseResult?`; null means fallback to local parsing.

`domain/parser/KoreanAppointmentParser.kt`

- Orchestrates local parsing plus optional LLM parsing.
- `parse`: returns the local result, LLM result, or merged fallback depending on `preferLlm`.
- `parseLocally`: extracts date, time, location, confidence, and time confidence.
- `parseDate`: supports relative Korean dates, month/day, this-week/next-week weekday expressions.
- `parseTime`: supports morning/afternoon/evening/night, colon times, Korean `시`, `분`, and `반`.
- `parseLocation`: extracts explicit `장소`/`위치` labels and common Korean location phrasing.
- `mergeFallback`: merges nullable metadata from local and LLM results while keeping title blank.

`domain/parser/GeminiAppointmentParser.kt`

- Implements `AppointmentLlmParser` using Gemini `generateContent`.
- `parse`: skips work when the API key is blank and returns null on failures.
- `post`: sends the prompt and JSON response configuration to Gemini.
- `prompt`: asks Gemini to parse start/end/location/confidence only, and explicitly not to extract a title.
- `parseResponse`: converts Gemini JSON into `AppointmentParseResult` with blank title.

### Domain Model

`domain/model/Models.kt`

- `ScheduleStatus`: confirmed, planned, uncertain schedule values.
- `CandidateStatus`: pending, confirmed, discarded candidate values.
- `TimeConfidence`: high, medium, low time parse confidence.
- `AppointmentParseResult`: parser output shared by local and LLM parsing.

`domain/conflict/ConflictDetector.kt`

- `newEnd`: uses explicit end time or a default one-hour duration.
- `conflicts`: tests interval overlap between a new schedule and an existing schedule.

### Persistence

`data/db/AppDatabase.kt`

- Room database definition.
- Owns `schedules` and `appointment_candidates` tables.
- `create`: builds the local persistent database.

`data/entity/AppointmentCandidateEntity.kt`

- Room entity for shared-text candidates.
- Represents unconfirmed parse output plus source metadata.
- `extractedTitle` is user-entered and may be blank while pending.

`data/entity/ScheduleEntity.kt`

- Room entity for saved schedules.
- Stores final title, time, location, status, original source text, and timestamps.

`data/dao/AppointmentCandidateDao.kt`

- `get`: loads one candidate.
- `observe`: streams one candidate for edit/conflict screens.
- `observePending`: streams pending candidates for the planner screen.
- `insert` and `update`: persist candidate state.

`data/dao/ScheduleDao.kt`

- `observeAll`: streams schedules ordered for UI/widget use.
- `getAll`: test/helper load of all schedules.
- `findConflicts`: SQL overlap query using start/end range.
- `insert` and `update`: persist schedules.

`data/repository/PlannerRepository.kt`

- Main data/use-case boundary for the app.
- `createCandidate`: parses shared text and inserts a pending candidate with blank title.
- `conflictsForCandidate`: checks whether the candidate would overlap an existing schedule.
- `saveFromCandidate`: requires a typed title, handles uncertain saves, detects conflicts, inserts schedules, and updates candidate status.
- `updateCandidate`: stores user edits for title, start, end, and location.
- `discardCandidate`: marks pending candidates discarded.
- `insertSchedule`: private helper that creates the final `ScheduleEntity`.
- `SaveAttempt`: describes readiness or conflict before saving.
- `SaveResult`: describes final save result, including `TitleRequired`.

`data/supabase/SharingRepository.kt`

- Client boundary for the Supabase Edge Function planner API.
- Reads the existing app session token from SharedPreferences and sends it as a Bearer token.
- Calls profile, group create/list, schedule upload, and schedule list endpoints under `/api/planner/share`.
- Caches only the returned `public_id`; it does not store Supabase Auth tokens or call Supabase PostgREST directly.

### Notifications

`notification/AppointmentNotificationManager.kt`

- Creates notification channels for candidates and conflicts.
- `showCandidate`: shows parsed time/location and asks the user to type the title.
- `showConflict`: shows conflict information with force-add, edit, and cancel actions.
- `cancelCandidate` and `cancelCandidatePrompt`: clear candidate/conflict notifications.
- `saveAction`: creates `RemoteInput` actions for title entry and save.
- `editAction`: opens the candidate edit screen.
- `candidateSummary` and `candidateDetails`: format notification text without inventing a title.

`notification/NotificationActionReceiver.kt`

- Broadcast receiver for notification actions.
- `onReceive`: runs repository work asynchronously with `goAsync`.
- `handleSave`: reads the typed title from `RemoteInput`, saves through the repository, re-shows the candidate if title is missing, and opens conflict handling when needed.

### UI

`ui/MainActivity.kt`

- Compose host activity and route owner.
- Requests notification permission from the app entry path.
- Checks Google Play in-app updates for release builds.
- `startRoute`: maps intents to planner, candidate edit, or conflict routes.
- `candidateIntent` and `conflictIntent`: build notification deep links.
- `AppRoot`: selects the current Compose screen.

`ui/PlannerScreen.kt`

- Shows pending candidates and saved schedules.
- Pending candidates with no title display `제목 입력 필요`.
- Opens the candidate edit screen when a pending chip is tapped.

`ui/CandidateEditScreen.kt`

- Lets the user type title and edit parsed start/end/location fields.
- Save is disabled until title is non-blank.
- Saves through `PlannerRepository.saveFromCandidate`.
- Routes to conflict screen when the repository reports a conflict.

`ui/ConflictScreen.kt`

- Displays a candidate and overlapping existing schedules.
- Lets the user force-add, edit, or cancel.
- Force-add is disabled until the candidate has a title.

`ui/UiFormat.kt`

- Shared date/time formatting for Compose UI.
- `parseDateTimeOrNull`: parses `yyyy-MM-dd HH:mm` user input.

### Widget

`widget/PlannerWidgetSync.kt`

- Converts Room schedules into the widget snapshot format.
- `syncFromPlannerDatabase`: loads saved schedules and writes a snapshot.
- `saveSnapshot`: groups schedules by local date in `Asia/Seoul` and persists JSON to shared preferences.

`widget/PlannerWidgetStore.java`

- SharedPreferences helper used by native widget code.
- Defines the snapshot storage key used by `PlannerWidgetSync` and `SummaryWidgetProvider`.

`widget/SummaryWidgetProvider.java`

- Native Android `AppWidgetProvider`.
- Reads the saved snapshot and renders the weekly summary widget.
- Contains widget sizing, snapshot parsing, and RemoteViews layout logic.

## Build And Release Files

`app/build.gradle.kts`

- Android application Gradle config.
- Reads `.env`/environment values for application id, version, Gemini config, release signing, and Play publishing.
- Defines `publishAab`, which publishes the signed release bundle through Gradle Play Publisher.

`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`

- Root Gradle and wrapper configuration.

## Tests

`app/src/test/java/.../domain/parser/KoreanAppointmentParserTest.kt`

- Verifies Korean relative date/time/location parsing.
- Verifies LLM merge behavior.
- Verifies parser output keeps the title blank.

`app/src/test/java/.../data/repository/PlannerRepositoryTest.kt`

- Verifies candidate creation, save behavior, conflict behavior, and title-required behavior.

`app/src/test/java/.../domain/conflict/ConflictDetectorTest.kt`

- Verifies schedule overlap logic.

`app/src/test/java/.../widget/PlannerWidgetSyncTest.kt`

- Verifies widget snapshot generation from Room schedules.

## Supabase sharing architecture

- Local Room remains the source of truth for personal schedules, conflict checks, candidate parsing, and widget snapshots.
- The sharing feature is opt-in from the planner screen. Android calls the Supabase Edge Function `planner-api` configured by `PLANNER_API_BASE_URL`.
- Android reads the existing app login token from SharedPreferences (`PLANNER_SESSION_PREFS_NAME`, default `planner_auth`; `PLANNER_SESSION_TOKEN_KEY`, default `session_token`) and sends it as `Authorization: Bearer <token>`.
- Android does not create anonymous Supabase Auth sessions, store Supabase access/refresh tokens, or write directly to Supabase PostgREST tables.
- The `planner-api` Edge Function treats the app session token as the user id, checks group membership/ownership with that id, and performs Supabase DB work with server-only credentials.
- A user shares by giving their `public_id` to another user. Entering a partner `public_id` asks `planner-api` to create or reuse a group and return accessible groups/schedules.
- Only selected local schedules are uploaded to `planner-api`; they remain independent copies of Room rows.
- Fake shared-screen-only entries are read from `planner_dummy_schedules` through the API and must not be inserted into Room, conflict detection, notifications, or widget snapshots.
- Required mobile configuration: `PLANNER_API_BASE_URL=https://<project-ref>.supabase.co/functions/v1/planner-api`; `SUPABASE_SERVICE_ROLE_KEY` is Edge-Function-only and must never be shipped in the app.
