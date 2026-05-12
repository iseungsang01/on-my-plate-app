# Repository Agent Instructions

## Documentation

When changing app behavior, architecture, public workflows, or functions documented in `docs/spec_func.md`, update only the affected descriptions so the file remains a current function-level spec. Do not add changelog/date notes or prose about what changed.

## MVP Focus / Context Hygiene

Default MVP work should stay centered on external share intake, notification prompts, appointment candidates, candidate editing/saving, and weekly schedule review. Use `docs/mvp_spec.md` as the product scope reference.

When investigating MVP issues, search hot-path files first: share intake, notifications, `PlannerScreen`, candidate edit/complete/conflict screens, weekly schedule UI, parser, auth, and the needed `PlannerRepository` functions. For `PlannerRepository`, prefer targeted searches and small surrounding ranges over repeated full-file reads.

Treat these as cold zones unless the user asks about them directly or a bug clearly points there: `supabase/`, `SharingScreen`, `SharingRepository`, `widget/`, recurrence UI/API internals, and long design/reference documents. Keep code in those areas available for later reconnection, but do not spend context on them for unrelated MVP tasks.

## Android Release

Preserve the current app version unless explicitly asked to bump it; version values come from `.env` or CI via `ANDROID_VERSION_CODE` and `ANDROID_VERSION_NAME`.

For AAB/release/upload/publish/deploy requests, run the full Play Console path with `.\gradlew.bat :app:publishAab --no-daemon`. Before publishing, verify `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `PLAY_SERVICE_ACCOUNT_JSON_PATH`. Respect configured `PLAY_TRACK` and `PLAY_RELEASE_STATUS`, retry with approval when blocked by credentials/files/network, and report version name/code, track, status, and AAB path on success.
