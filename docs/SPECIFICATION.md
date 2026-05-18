# 약속 바구니 Native Planner Specification

This document describes the current Android + Supabase Edge Function implementation. It is code-facing: use it to understand the source of truth, data flow, widget freshness policy, and file-level responsibilities before changing the app.

## Architecture Decision

The current architecture is API-centric.

- Supabase Edge Function `planner-api` is the source of truth for users, sessions, appointment candidates, personal schedules, sharing groups, and feedback.
- Android does not currently use Room as the runtime source of truth for personal schedules or appointment candidates.
- Android keeps short-lived in-memory state in `PlannerRepository` through `MutableStateFlow`.
- The native home-screen widget reads a generated SharedPreferences snapshot, not Room.
- The widget snapshot is generated from schedules fetched through `PlannerRepository`, then written by `PlannerWidgetSync`.

Room is not required for the current MVP. A stale widget snapshot is acceptable, but an empty snapshot after transient network failure is not. Therefore the preferred near-term policy is to keep the last valid snapshot and refresh opportunistically, instead of adding Room only for widget freshness.

## Product Scope

The app turns shared or typed appointment text into a schedule candidate.

Supported input:

- Android `ACTION_SEND` with `text/plain`, including KakaoTalk, SMS, memo apps, browsers, and other share sources.
- Direct text entry from the planner screen.

Out of scope:

- KakaoTalk scraping.
- Background chat monitoring.
- Notification-access monitoring of all messages.
- Supabase service-role credentials in Android.
- Direct Android writes to Supabase PostgREST.

## Core Flow

1. Android receives shared `text/plain` through `ShareReceiverActivity`, or the user enters text directly in `BasketScreen`.
2. `PlannerRepository.createCandidate` deduplicates rapid repeated requests, parses the text, builds an `AppointmentCandidateEntity`, and sends it to `planner-api`.
3. `KoreanAppointmentParser` performs deterministic Korean date/time/location parsing.
4. `GeminiAppointmentParser` optionally calls the `planner-api` parser proxy endpoint. Android never calls Gemini directly and never stores the Gemini API key.
5. The parser result may contain an internal summary title, but `candidateFromParseOutcome` stores `extractedTitle = ""`. The user-facing candidate title starts blank.
6. `AppointmentNotificationManager.showCandidate` posts a native notification with parsed time/location and title input actions.
7. `NotificationActionReceiver` handles notification actions with `goAsync`, reads `RemoteInput`, and calls `PlannerRepository.saveFromCandidate`.
8. `PlannerRepository.saveFromCandidate` requires a usable title for confirmed/planned saves, handles uncertain saves, checks conflicts, creates a schedule through `planner-api`, marks the candidate confirmed, refreshes pending candidates, and refreshes schedules.
9. `MainActivity` hosts Compose routes for planner, login/settings, candidate editing, conflict resolution, schedule editing, sharing, and feedback.
10. `OnMyPlateApp` observes the current week of expanded schedules and writes widget snapshots through `PlannerWidgetSync.saveSnapshot`.
11. `SummaryWidgetProvider` renders the widget from the latest SharedPreferences snapshot and requests a fresh sync when the widget is updated or resized.

## Source of Truth and Caching

### Source of Truth

`planner-api` owns durable state.

- Auth/session state lives in Supabase tables behind `planner-api`.
- Personal schedules live in `planner_personal_schedules` and related recurrence tables.
- Appointment candidates live in `planner_candidates`.
- Shared schedules live in `planner_schedules` and related sharing tables.
- Feedback lives in `planner_feedback`.

### Android Runtime Cache

`PlannerRepository` keeps runtime copies in memory:

- `scheduleRecords: MutableStateFlow<List<ScheduleRecord>>`
- `pendingCandidates: MutableStateFlow<List<AppointmentCandidateEntity>>`
- `candidateRecords: MutableStateFlow<Map<String, AppointmentCandidateEntity>>`
- `runtimeState: MutableStateFlow<PlannerRuntimeState>`

These flows are populated by API refreshes. They are not durable across process death.

### Widget Snapshot Cache

The widget must not fetch network data during RemoteViews rendering.

Instead:

1. `PlannerWidgetSync.syncFromPlannerApiSnapshot` checks whether a session exists.
2. If there is no session, the snapshot may be cleared because the user is effectively logged out.
3. If there is a session, the sync path fetches the current week through `app.repository.getExpandedSchedules(rangeStart, rangeEnd)`.
4. `PlannerWidgetSync.saveSnapshot` groups schedule occurrences by local date in `Asia/Seoul`.
5. The snapshot is saved to SharedPreferences using schema `native-supabase-schedules-v1`.
6. `SummaryWidgetProvider` reads the latest snapshot synchronously and renders RemoteViews.

The method name `syncFromPlannerApiSnapshot` is historical. In the current implementation, it syncs from the planner repository/API path, not from Room.

## Room Decision

Do not reintroduce Room for the current MVP.

Reasoning:

- The app already requires login for schedule features.
- The canonical schedule state already lives behind `planner-api`.
- The widget only needs a readable last-known weekly snapshot, not a full relational local database.
- A stale widget snapshot is acceptable, while an empty snapshot caused by transient network failure is not.
- Room would introduce an additional synchronization layer, ID-mapping complexity, migration cost, and conflict semantics before offline editing is actually required.

Use SharedPreferences snapshot retention instead of Room for widget robustness.

Required policy:

- Clear snapshot on explicit logout or missing session.
- Do not clear snapshot on timeout, DNS failure, HTTP 5xx, parser failure, or other transient API/network failures.
- Keep displaying the last valid snapshot when refresh fails.
- Store `generatedAt` in the snapshot.
- Optionally surface a subtle stale indicator later if product UX requires it.
- Refresh snapshot after successful schedule create/update/delete.

Reconsider Room only if the product requires one or more of the following:

- offline schedule creation/editing,
- durable local access after process death without network,
- instant widget rendering from a full local mirror rather than a generated snapshot,
- conflict checks that must run against durable local state while offline,
- background retry queues for failed writes,
- multi-device merge semantics with explicit local sync status.

If Room is reintroduced later, use it as a local mirror of API state, not as a competing source of truth.

Recommended future hybrid model if needed:

1. API remains canonical.
2. Room stores mirrored personal schedules, recurrence rules, recurrence exceptions, and pending candidates.
3. Writes go to API first in online-only mode.
4. On successful API response, Room is updated transactionally from the returned server object.
5. If offline writes are supported, Room also stores a `pending_ops` queue.
6. Widget snapshots are generated from Room synchronously.
7. Repository exposes Room-backed flows and separately triggers API refreshes.
8. Conflict detection uses Room for immediate checks and API refresh for final consistency.


### Legacy Room Naming Note

`ScheduleEntity` and `AppointmentCandidateEntity` are currently plain Kotlin data classes used across the repository/API boundary. They are not Room entities unless Room annotations and DAO/database wiring are reintroduced. Avoid creating new DAO/database code for the current MVP.

## Widget Freshness Strategy

Current behavior:

- App startup observes current-week expanded schedules and writes snapshots when schedules change.
- Widget update/resizing triggers `PlannerWidgetSync.syncFromPlannerApiSnapshot` and immediately renders from the latest available snapshot.
- Schedule save/update/delete refreshes repository schedule state; the app-level observer can then write a new snapshot.

Potential delay sources:

- API fetch latency.
- Session expiration.
- App process not alive.
- Widget rendering before async sync finishes.
- API/network failure during widget sync.

Near-term improvement without Room:

- Preserve the last valid widget snapshot on transient refresh failure.
- Refresh widget snapshot explicitly after successful schedule create/update/delete.
- Debounce overlapping refresh calls from multiple collectors.
- Keep range-based fetch behavior explicit.
- Treat stale data as acceptable and empty data as meaningful only when logged out or there are truly no schedules.

## Data Rules

### Candidate Data

- `rawText`: exact shared or typed source text.
- `sourceApp`: share source package/app name when available, or an internal marker for direct input.
- `extractedTitle`: user-entered title. It is stored blank immediately after parsing.
- `extractedStartAt`: parsed start time in epoch milliseconds on Android; ISO string in API payloads.
- `extractedEndAt`: parsed explicit or defaulted end time in epoch milliseconds on Android; ISO string in API payloads.
- `extractedLocation`: parsed location when present.
- `confidence`: parser confidence from `0.0` to `1.0`.
- `timeConfidence`: `high`, `medium`, or `low`.
- `parseSource`: one of `llm_success`, `llm_with_local_supplement`, `local_fallback`, `parser_error`, `local_only`, or `unknown`.
- `status`: `pending`, `confirmed`, or `discarded`.

### Schedule Data

- Confirmed/planned schedules require a non-blank title.
- If the selected status is uncertain, the app may derive a fallback title from explicit input, candidate title, raw text, or `미정 일정`.
- If start time is missing or the selected status is uncertain, the schedule is saved as `uncertain` using candidate creation time as fallback start.
- Conflict detection assumes a default one-hour duration when end time is missing.
- Recurrence expansion is performed on Android for displayed occurrences.

### Recurrence Contract

Android and `planner-api` support `daily`, `weekly`, and `monthly` recurrence.

The deployed Supabase schema must match this contract:

- `frequency in ('daily', 'weekly', 'monthly')`
- `interval >= 1`
- `day_of_week` is required only for weekly recurrence
- `day_of_month` is required only for monthly recurrence
- daily recurrence must not require weekday or month-day anchors

For existing deployments based on older weekly-only SQL, run `docs/supabaseRecurrenceMigration.sql` before testing daily/monthly recurrence writes.

## File Map

### App Root

`app/src/main/AndroidManifest.xml`

- Declares `INTERNET` and `POST_NOTIFICATIONS`.
- Registers `ShareReceiverActivity` as an exported `ACTION_SEND` target for `text/plain`.
- Registers `MainActivity`, `NotificationActionReceiver`, and `SummaryWidgetProvider`.

`app/src/main/java/com/lss/onmyplate/nativeplanner/OnMyPlateApp.kt`

- Application-level dependency holder.
- Creates `KoreanAppointmentParser`, `GeminiAppointmentParser`, `PlannerRepository`, `AuthRepository`, `FeedbackRepository`, `SharingRepository`, and `AppointmentNotificationManager`.
- Passes `PLANNER_API_BASE_URL` and session token provider to the Gemini proxy parser.
- Ensures notification channels.
- Starts current-week schedule observation and writes widget snapshots.

### Build Configuration

`app/build.gradle.kts`

- Defines Android application configuration.
- Reads `.env` or process environment values.
- Sets `ANDROID_APPLICATION_ID`, version fields, and `PLANNER_API_BASE_URL` BuildConfig values.
- Configures release signing only when release tasks require signing values.
- Configures Gradle Play Publisher with `PLAY_SERVICE_ACCOUNT_JSON_PATH`, `PLAY_TRACK`, and `PLAY_RELEASE_STATUS`.
- Defines `publishAab` as the release bundle upload task.

Required Android runtime config:

- `PLANNER_API_BASE_URL=https://<project-ref>.supabase.co/functions/v1/planner-api`

Server-only config:

- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `GEMINI_API_KEY`
- `GEMINI_MODEL`
- `GEMINI_API_BASE_URL`

Never ship service-role or Gemini credentials in Android BuildConfig.

### Authentication

`data/auth/AuthRepository.kt`

- Owns Android-side login/signup/change-password calls.
- Stores only `sessionToken` in SharedPreferences.
- Reads preference names from BuildConfig.
- Does not use Supabase Auth SDK.

`supabase/functions/planner-api/index.ts`

- Implements `/api/auth/signup`, `/api/auth/login`, and `/api/auth/password`.
- Stores password hashes in `planner_users`.
- Creates random session tokens, stores token hashes in `planner_sessions`, and returns the raw token to Android.
- Validates Bearer tokens for protected planner routes.

### Share Input

`share/ShareReceiverActivity.kt`

- Validates incoming `ACTION_SEND` + `text/plain` intents.
- Handles notification permission request flow on Android 13+.
- Calls `PlannerRepository.createCandidate`.
- Shows a candidate notification through `AppointmentNotificationManager`.
- Finishes as a transparent receiver activity.

### Parsing

`domain/parser/AppointmentLlmParser.kt`

- Async parser interface.
- Returns `AppointmentParseResult?`; null means local fallback should be used.

`domain/parser/KoreanAppointmentParser.kt`

- Parses Korean relative dates, month/day, slash dates, this-week/next-week weekdays, meridiem expressions, Korean hour words, compact time ranges, and common location patterns.
- Can prefer LLM output when configured.
- Merges LLM output with local fallback when fields are missing.
- Generates default end time and an internal summary title, but repository candidate creation intentionally discards parser title for user-facing candidate title.

`domain/parser/GeminiAppointmentParser.kt`

- Calls `PLANNER_API_BASE_URL/api/parser/appointment` with the app session token.
- Skips parsing when base URL or session token is missing.
- Parses proxy JSON into `AppointmentParseResult`.
- Does not call Gemini directly from Android.

`supabase/functions/planner-api/index.ts`

- Implements `/api/parser/appointment`.
- Requires a valid app session.
- Calls Gemini using server-side env secrets.
- Returns normalized JSON fields for start, end, location, title summary, and confidence.

### Domain Model

`domain/model/Models.kt`

- `ScheduleStatus`: `confirmed`, `planned`, `uncertain`.
- `CandidateStatus`: `pending`, `confirmed`, `discarded`.
- `TimeConfidence`: `high`, `medium`, `low`.
- `AppointmentParseResult`: parser output.
- `AppointmentParseOutcome`: parser output plus source metadata.
- Parse-source conversion helpers for API values.

`domain/conflict/ConflictDetector.kt`

- Computes default end time when explicit end is missing.
- Checks interval overlap against existing schedules.

### Repository and API Client

`data/repository/PlannerRepository.kt`

- Main Android use-case boundary.
- Reads session token from SharedPreferences.
- Uses `PlannerApiClient` to call `planner-api`.
- Maintains in-memory state flows for schedules, pending candidates, candidate records, loading state, and user-facing errors.
- Deduplicates rapid candidate creation requests within a small time window.
- Creates candidates through API.
- Lists, refreshes, updates, deletes, and expands schedules.
- Saves candidates as schedules through API.
- Updates candidate status through API.
- Performs local conflict detection after fetching schedules.
- Expands daily, weekly, and monthly recurrence rules for UI/widget display.

`PlannerApiClient` inside `PlannerRepository.kt`

- Uses `HttpURLConnection` directly.
- Sends `Authorization: Bearer <sessionToken>`.
- Converts Android model objects to API JSON.
- Converts API JSON back to Android model objects.
- Clears cached session on HTTP 401.
- Maps API and network errors to user-facing messages through `PlannerRepository`.

### Supabase Edge Function

`supabase/functions/planner-api/index.ts`

Current responsibilities:

- request routing,
- CORS response handling,
- app auth/session validation,
- password hashing and legacy hash migration,
- Gemini parser proxy,
- personal schedule CRUD,
- recurrence rule/exception persistence,
- candidate CRUD,
- sharing profile/group/schedule endpoints,
- feedback submission,
- response normalization.

The file is monolithic in the current implementation. If it grows further, split it into `auth.ts`, `parser.ts`, `schedules.ts`, `candidates.ts`, `sharing.ts`, `recurrence.ts`, `http.ts`, and `db.ts`.

### Supabase SQL

`docs/supabaseSQL.md`

- Defines tables for app users, sessions, profiles, groups, memberships, shared schedules, personal schedules, recurrence rules, recurrence exceptions, dummy schedules, and feedback.
- Enables RLS and revokes anon/authenticated access.
- Grants access to service_role because Edge Function owns authorization.
- Must stay synchronized with API payloads accepted by `planner-api`.

Important consistency checks:

- Recurrence frequency constraints must match Android and API support.
- `local_schedule_id` must remain unique per user or group context.
- `planner_sessions` expiration behavior must match mobile session UX.

### Sharing

`data/supabase/SharingRepository.kt`

- Calls `/api/planner/share/profile`, `/api/planner/share/groups`, and group schedule endpoints.
- Uses the same app session token from SharedPreferences.
- Caches only the returned `public_id`.
- Does not store Supabase Auth tokens.
- Does not write directly to PostgREST.

Sharing model:

- A user has a public share ID.
- Entering another user's public ID creates or reuses a group.
- Selected local schedules can be uploaded into a shared group.
- Dummy shared-screen entries are loaded from backend dummy tables and must not be inserted into personal schedules, local conflict checks, or widget snapshots.

### Notifications

`notification/AppointmentNotificationManager.kt`

- Creates channels for candidate prompts, conflicts, and action failures.
- Shows candidate prompt notifications with parsed time/location.
- Provides inline title entry for confirmed/planned saves.
- Provides uncertain save and edit actions.
- Shows conflict notifications with force-add, edit, and cancel actions.
- Cancels candidate/conflict prompts after handled actions.

`notification/NotificationActionReceiver.kt`

- Handles notification actions asynchronously with `goAsync`.
- Reads `RemoteInput` text.
- Treats confirmed/planned input as title.
- Treats uncertain input as memo.
- Calls `PlannerRepository.saveFromCandidate`.
- Re-shows candidate prompt if title is required.
- Shows conflict notification if conflict is detected.
- Shows failure notification if repository/API work throws.

### UI

`ui/MainActivity.kt`

- Compose host and route owner.
- Handles app launch and notification deep links.
- Requests notification permission from app entry paths.
- Checks Google Play in-app updates for release builds.
- Routes to planner, candidate edit, conflict, schedule edit, sharing, settings, and feedback screens.

`ui/PlannerScreen.kt`

- Shows direct appointment-text input.
- Creates candidates through `PlannerRepository.createCandidate`.
- Opens candidate edit after candidate creation.
- Shows pending candidates that require title/time/location confirmation.
- Shows saved schedules in a collapsible section with date filters.
- Uses `observeExpandedSchedules(rangeStart, rangeEnd)` so recurring occurrences are included.

`ui/CandidateEditScreen.kt`

- Lets the user enter title and edit parsed start/end/location.
- Disables save until title is non-blank for confirmed/planned flows.
- Saves through `PlannerRepository.saveFromCandidate`.
- Routes to conflict screen when conflicts are reported.

`ui/ConflictScreen.kt`

- Displays the candidate and overlapping schedules.
- Lets the user force-add, edit, or cancel.
- Force-add should only proceed when title requirements are satisfied.

`ui/ScheduleEditScreen.kt`

- Edits saved schedule fields.
- Uses repository update/delete paths.
- Supports recurrence-related edits according to current UI coverage.

`ui/UiFormat.kt`

- Shared formatting/parsing helpers for Compose screens.
- Parses user-entered date-time strings such as `yyyy-MM-dd HH:mm`.

### Widget

`widget/PlannerWidgetSync.kt`

- Current-week snapshot generator.
- Fetches expanded schedules through repository/API when explicitly syncing.
- Clears snapshot when no session exists.
- Preserves the previous snapshot on transient sync failure.
- Writes SharedPreferences snapshot schema `native-supabase-schedules-v1`.
- Groups manual schedule occurrences by local date in `Asia/Seoul`.
- Calls widget refresh when saving snapshots.

`widget/PlannerWidgetStore.java`

- SharedPreferences helper for saving and reading the widget snapshot.
- Defines the snapshot storage key used by sync and provider code.

`widget/SummaryWidgetProvider.java`

- Native Android `AppWidgetProvider`.
- On widget update or resize, triggers async sync and immediately renders from the latest available snapshot.
- Handles previous-week, next-week, viewport-toggle, and open-planner actions.
- Renders the weekly timetable as a bitmap inside RemoteViews.

## Fetching and Refresh Policy

Current API fetch policy:

- `observeSchedules()` triggers `refreshSchedules()` on start.
- `observeExpandedSchedules(rangeStart, rangeEnd)` triggers `refreshSchedules(rangeStart, rangeEnd)` on start.
- `observePendingCandidates()` triggers `refreshPendingCandidates()` on start.
- Candidate/schedule writes call API first, then refresh related state.
- Widget sync fetches current-week expanded schedules when `SummaryWidgetProvider` asks for update/resize sync.

Required refresh policy for the MVP:

- Preserve the last valid widget snapshot on transient refresh failure.
- Clear snapshot only on explicit logout, missing session, or a confirmed empty schedule response.
- Refresh widget snapshot explicitly after successful schedule create/update/delete.
- Debounce overlapping refresh calls from multiple collectors.
- Treat `runtimeState.errorMessage` as UI state, not as a signal to delete cached widget data.

## Tests

`app/src/test/java/.../domain/parser/KoreanAppointmentParserTest.kt`

- Verifies Korean relative date/time/location parsing.
- Verifies compact date/time ranges.
- Verifies LLM preference and fallback behavior.
- Verifies parser outcome source labeling.

`app/src/test/java/.../data/repository/PlannerRepositoryTest.kt`

- Should verify candidate creation, save behavior, conflict behavior, recurrence behavior, title-required behavior, and widget refresh trigger behavior against a fake API client or extracted repository interface.

`app/src/test/java/.../domain/conflict/ConflictDetectorTest.kt`

- Verifies schedule overlap logic.

`app/src/test/java/.../widget/PlannerWidgetSyncTest.kt`

- Should verify widget snapshot generation from schedule occurrences and stale-snapshot preservation behavior.


## Supabase Migration Policy

Remote database schema changes must be managed through Supabase CLI migration files under `supabase/migrations/`.

Do not document a schema change as SQL Editor-only work. SQL snippets or files under `docs/` are reference material; the canonical migration source is the timestamped SQL file under `supabase/migrations/` and the expected apply command is `supabase db push`.

See `docs/SUPABASE_MIGRATION_POLICY.md` for the full policy.

## Release Checklist

Before internal testing or production release:

1. Confirm `PLANNER_API_BASE_URL` points to the deployed Edge Function.
2. Confirm `planner-api` was deployed after the latest `supabase/functions/planner-api/index.ts` change.
3. Run signup/login smoke tests.
4. Run `GET /api/planner/candidates?status=pending` with a valid and invalid token.
5. Run candidate creation and schedule save smoke tests.
6. Verify recurrence SQL constraints match Android/API recurrence support.
7. Verify notification permission flow on Android 13+.
8. Verify share target from KakaoTalk or another text source.
9. Verify widget refresh after schedule create/update/delete.
10. Verify widget keeps the last valid snapshot during transient network/API failure.
11. Increment `ANDROID_VERSION_CODE` before AAB upload.
12. Provide release signing variables only for release tasks.
13. Provide Play service account credentials only for Play publishing tasks.

## Known Architectural Risks

- `planner-api/index.ts` is large and should be modularized as the API grows.
- Android has multiple direct `HttpURLConnection` clients; consolidate if error/session handling becomes inconsistent.
- Current widget freshness depends on API fetch + snapshot generation timing.
- Room-related historical names may mislead future development.
- Daily/monthly recurrence support must be reconciled with deployed SQL constraints.
- Login/session expiration behavior should be tested around app startup and widget update paths.
