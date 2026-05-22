# 약속 바구니 Native Planner 함수별 명세

이 문서는 `SPECIFICATION.md`와 현재 `app/src/main/java/com/lss/onmyplate/nativeplanner` 구현을 기준으로, 주요 함수/메서드가 맡는 기능을 파일별로 정리한 문서입니다.

## 핵심 처리 흐름 요약

1. `ShareReceiverActivity.onCreate` receives shared appointment text and keeps using the candidate/notification intake path.
2. The navigator-centered `+` receives typed natural-language quick-add text, parses it, lets the user edit start/end/location inline, and saves a confirmed `ScheduleEntity` directly.
3. Shared text creates a pending `AppointmentCandidateEntity` detail setup through `PlannerRepository.createCandidate`; it is not a final schedule yet.
4. Candidate confirmation calls `saveFromCandidate`, writes the final personal schedule through `/api/planner/schedules`, marks the candidate confirmed, and returns to the weekly timetable.
5. Saved `ScheduleEntity` records and recurrence data are observed by `observeExpandedSchedules` and synced to `PlannerWidgetSync.saveSnapshot`.

---

## App Root

### `OnMyPlateApp.kt`

- `onCreate()`
  - 애플리케이션 시작 시 전역 인스턴스를 설정합니다.
  - 알림 채널을 생성합니다.
  - 현재 주 범위의 단일/반복 occurrence 목록을 관찰하다가 변경될 때마다 `PlannerWidgetSync.saveSnapshot`을 호출해 홈 위젯 데이터를 갱신합니다.


---

## 공유 입력

### `share/ShareReceiverActivity.kt`

- `onCreate(savedInstanceState)`
  - Android 공유 intent가 `ACTION_SEND`이고 MIME type이 `text/plain`인지 검증합니다.
  - 공유 텍스트, 공유한 앱 패키지, 수신 시각을 임시 보관합니다.
  - Android 13 이상에서 알림 권한이 없으면 권한을 요청하고, 권한이 있으면 `saveAndNotify()`를 실행합니다.

- `onRequestPermissionsResult(requestCode, permissions, grantResults)`
  - 알림 권한 요청 결과가 돌아오면 결과와 무관하게 보관된 공유 텍스트 저장을 계속 진행합니다.

- `saveAndNotify()`
  - 앱 세션, API 설정, 세션 토큰 캐시 상태를 민감정보 없이 로그로 남긴 뒤 저장을 시도합니다.
  - 앱 세션이 없으면 토스트와 로그만 남기고 `MainActivity`를 열지 않은 채 투명 공유 수신 Activity를 종료합니다.
  - Creates a pending detail setup with `PlannerRepository.createCandidate` and attempts to show an inline-title notification instead of saving a final schedule immediately.
  - 저장에 성공하면 토스트를 표시하고 앱 화면을 열지 않습니다.
  - 저장에 실패하면 오류 원인을 로그로 남기고 토스트를 표시한 뒤 앱 화면을 열지 않고 투명 공유 수신 Activity를 종료합니다.
  - 투명 공유 수신 Activity를 종료합니다.

- `needsNotificationPermission()`
  - Android 13 이상인지, `POST_NOTIFICATIONS` 권한이 없는지 확인합니다.

---

## 파싱

### `domain/parser/AppointmentLlmParser.kt`

- `parse(rawText, receivedAt)`
  - LLM 기반 일정 파서의 공통 인터페이스입니다.
  - 성공 시 `AppointmentParseResult`, 파싱 불가/실패 시 `null`을 반환하여 로컬 파서 fallback을 허용합니다.

### `domain/parser/KoreanAppointmentParser.kt`

- `parse(rawText, receivedAt)`
  - Returns the `AppointmentParseResult` from `parseWithOutcome(rawText, receivedAt)` for compatibility.

- `parseWithOutcome(rawText, receivedAt)`
  - Builds a local parse result, then uses or supplements it with the configured LLM parser.
  - Returns `AppointmentParseOutcome` with source provenance: `LlmSuccess`, `LlmWithLocalSupplement`, `LocalFallback`, `ParserError`, or `LocalOnly`.
  - With `preferLlm=true`, a non-null LLM result is preferred and missing fields may be filled from local parsing.
  - Inputs that start with `fallback` and contain a complete compact date/time-range/location pattern such as `fallback 5/17 1400-1600 강남` use the deterministic local result before LLM so the parsed start, end, and location are preserved.
  - If the LLM returns null/fails, the outcome source is `LocalFallback`; if the LLM result is supplemented by local fields, the source is `LlmWithLocalSupplement` and is not treated as user-visible fallback.
  - A high-confidence local result can return without LLM when `preferLlm=false`.

- `parseLocally(rawText, receivedAt)`
  - 수신 시각을 기준일로 변환합니다.
  - `parseDate`, `parseTime`, `parseLocation`을 호출해 날짜/시간/장소를 추출합니다.
  - Slash dates and compact time ranges such as `5/17 1400-1600` are supported; a leading `fallback` marker is removed before location inference.
  - 날짜와 시간이 모두 있으면 epoch milliseconds `startAt`을 생성하고, 명시적 시간 범위가 있으면 `endAt`도 생성합니다.
  - 시작 시각이 있으면 종료 시각 기본값은 1시간 뒤로 채우고, 제목이 비어 있으면 `M/d HHmm-HHmm 장소` 형식의 기본 제목을 만듭니다.

- `mergeFallback(fallback)`
  - LLM 결과의 `startAt`, `endAt`, `location`이 없으면 로컬 fallback 값을 채웁니다.
  - LLM 제목이 비어 있으면 fallback 제목을 사용하고, fallback 제목도 없으면 병합된 시작/종료/장소 값으로 기본 제목을 만듭니다.
  - confidence는 두 결과 중 더 높은 값을 사용합니다.

- `parseDate(text, baseDate)`
  - “오늘/내일/모레”, 월/일 표현, 이번 주/다음 주 요일 표현을 기준일에 맞춰 `LocalDate`로 변환합니다.
  - 연도가 없는 월/일이 이미 지난 날짜면 다음 해로 보정합니다.

- `parseTime(text)`
  - 오전/오후/저녁/밤, `HH:mm`, 한국어 숫자/아라비아 숫자 기반 시/분/반 표현을 `LocalTime`으로 변환합니다.
  - 명확한 시간은 `High`, 애매한 숫자 시간은 `Medium`, 시간대 단어만 있는 경우는 `Low` 신뢰도로 반환합니다.

- `normalizeKoreanHourNumbers(text)`
  - `한시`, `두시`, `열한시` 같은 한글 시간 숫자를 로컬 파서가 처리할 수 있는 숫자 시각 표현으로 정규화합니다.

- `parseLocation(text)`
  - `장소`/`위치` 같은 명시적 라벨과 “~에서”, “회의/미팅/약속” 주변 표현을 기준으로 장소 후보를 추출합니다.
  - 추출된 장소는 공백 제거 후 최대 길이를 제한합니다.

### `domain/parser/GeminiAppointmentParser.kt`

- `parse(rawText, receivedAt)`
  - API key blank or LLM request/response parsing failures may be reported through the optional diagnostics callback before the parser returns `null`.
  - Gemini API key가 비어 있으면 즉시 `null`을 반환합니다.
  - IO dispatcher에서 `post`와 `parseResponse`를 실행합니다.
  - 네트워크/API/JSON 오류가 발생하면 예외를 밖으로 던지지 않고 `null`로 처리합니다.

- `post(rawText, receivedAt)`
  - Gemini `generateContent` endpoint로 HTTP POST 요청을 보냅니다.
  - temperature 0, JSON 응답 MIME 설정을 포함합니다.
  - 2xx가 아닌 응답이면 오류를 발생시킵니다.

- `prompt(rawText, receivedAt)`
  - Gemini에게 한 개의 일정에서 시작/종료 시각, 장소, confidence만 JSON으로 추출하라고 지시합니다.
  - `title`, `start_at_epoch_millis`, `end_at_epoch_millis`, `location`, `confidence` JSON을 반환하게 하고 few-shot 예시로 제목 형식과 기본 1시간 종료 시각을 고정합니다.
  - 상대 날짜는 `Asia/Seoul` 수신 시각 기준으로 해석하게 합니다.

- `parseResponse(responseText)`
  - Gemini 응답의 `candidates[0].content.parts[0].text`를 꺼내 JSON 코드블록 마커를 제거합니다.
  - `title`, `start_at_epoch_millis`, `end_at_epoch_millis`, `location`, `confidence`를 `AppointmentParseResult`로 변환합니다.
  - 시작 시각은 있지만 제목이나 종료 시각이 비어 있으면 앱 기본 규칙으로 제목과 1시간 종료 시각을 채웁니다.
  - 시작 시간이 있으면 시간 신뢰도는 `High`, 없으면 `Low`로 둡니다.

- `JSONObject.optNullableLong(name)`
  - JSON 필드가 없거나 null이면 Kotlin `null`, 있으면 long 값을 반환하는 helper입니다.

---

## 도메인 모델 및 충돌

### `domain/model/Models.kt`

- `ScheduleStatus`
  - 저장 일정 상태를 `confirmed`, `planned`, `uncertain` DB 값으로 표현합니다.

- `CandidateStatus`
  - 후보 상태를 `pending`, `confirmed`, `discarded` DB 값으로 표현합니다.

- `TimeConfidence`
  - 시간 파싱 신뢰도를 `high`, `medium`, `low` DB 값으로 표현합니다.

- `AppointmentParseResult`
  - 로컬/LLM 파서가 공유하는 출력 모델입니다. 제목, 시작/종료 시각, 장소, confidence, 시간 신뢰도를 담습니다.

### `domain/conflict/ConflictDetector.kt`

- `newEnd(startAt, endAt)`
  - 종료 시각이 있으면 그대로 사용하고, 없으면 시작 시각 + 기본 1시간을 종료 시각으로 간주합니다.

- `conflicts(newStart, newEnd, existing)`
  - 새 일정 구간과 기존 일정 구간이 겹치는지 검사합니다.
  - 기존 일정의 종료 시각이 없으면 기본 1시간 길이를 적용합니다.

---

## Planner Data Models

### `data/entity/*.kt`

- `ScheduleEntity`
  - Room annotation 없이 앱 내부에서 개인 일정 API 응답과 UI 상태를 전달하는 일정 모델입니다.

- `AppointmentCandidateEntity`
  - Room annotation 없이 앱 내부에서 후보 API 응답과 UI 상태를 전달하는 후보 모델입니다.

- `ScheduleRecurrenceRuleEntity` / `ScheduleRecurrenceExceptionEntity`
  - Room annotation 없이 반복 규칙과 skip 예외 API 응답을 표현하는 모델입니다.

---

## Repository

### `data/auth/AuthRepository.kt`

- `isConfigured()`
  - `PLANNER_API_BASE_URL` 기반 planner API 호출이 가능한지 반환합니다.

- `hasSession()` / `sessionToken()`
  - SharedPreferences에 저장된 앱 세션 토큰 존재 여부와 값을 반환합니다.

- `hasAppAccess()`
  - 저장된 세션 토큰이 있을 때만 앱 진입 가능 상태를 반환합니다.

- `login(identifier, password)` / `signUp(identifier, password)`
  - `planner-api` Edge Function의 `/api/auth/login` 또는 `/api/auth/signup`에 id/password를 보내고, 응답 세션 토큰을 SharedPreferences에 저장합니다. 로그인은 서버에 사용자가 없으면 새 `planner_users` row를 만들고, 있으면 저장된 비밀번호 해시와 비교합니다.

- `changePassword(currentPassword, newPassword)`
  - 저장된 세션 토큰을 `Authorization` 헤더로 보내 `/api/auth/password`에 현재 비밀번호와 새 비밀번호를 제출합니다.
  - 인증 API 설정과 로그인 세션이 없으면 오류를 반환하고, 성공 시 기존 세션은 유지합니다.

- `clearSession()` / `clearAccess()`
  - 저장된 세션 토큰을 삭제합니다.

### `data/supabase/SharingRepository.kt`

- `isConfigured()`
  - Returns whether `PLANNER_API_BASE_URL` is configured for Supabase Edge Function planner API calls.

- `cachedPublicId()`
  - Returns the cached `public_id` last received from the share API.

- `hasCachedSession()`
  - 설정 화면에서 앱 세션 토큰 존재 여부를 표시할 때 사용합니다.

- `clearAccountCache()`
  - 설정 화면 로그아웃 동작으로 세션 토큰과 공유 ID 캐시를 삭제합니다.

- `ensureProfile()`
  - Reads the existing app login token from SharedPreferences and calls `POST /api/planner/share/profile` on the `planner-api` Edge Function.
  - Caches the response `publicId` and returns `ShareProfile`.

- `createGroupWithPartner(partnerPublicId)`
  - Trims the partner sharing ID and calls `POST /api/planner/share/groups` to create or reuse a group.

- `listGroups()`
  - Calls `GET /api/planner/share/groups` and returns groups accessible to the current logged-in user.

- `uploadSchedule(groupId, schedule, recurrenceRule, recurrenceExceptions)`
  - Converts one selected Supabase personal schedule plus recurrence rule/exceptions to the API contract's camelCase JSON and uploads it with `POST /api/planner/share/groups/{groupId}/schedules`.
### `data/supabase/FeedbackRepository.kt`

- `FeedbackRepository(context)`
  - Reads the current app session token from SharedPreferences when available and submits feedback through `POST /api/planner/feedback`.
  - Sends the current app version name/code and the settings source screen with each feedback submission.
  - 저장 일정 1건과 반복 규칙/예외를 `POST /api/planner/schedules`에 업로드해 로그인 계정의 개인 일정으로 동기화합니다.

- `listSharedSchedules(groupId, includeDummy)`
  - Calls `GET /api/planner/share/groups/{groupId}/schedules?includeDummy=...` and returns schedules with recurrence metadata sorted by start time.

- `ScheduleEntity.toApiJson(recurrenceRule, recurrenceExceptions)`
  - 저장 일정과 일간/주간/월간 반복 규칙/skip 예외를 planner API 요청 JSON으로 변환합니다.

- `JSONObject.toSharedSchedule()` / `JSONObject.toSharedScheduleRecurrence()` / `JSONArray?.toRecurrenceExceptions()`
  - 공유 API 응답의 일정, 반복 규칙, 반복 예외를 Android 모델로 파싱합니다.

- `PlannerShareApiClient.request(...)`
  - Adds `Authorization: Bearer <existing-app-session-token>` to every share API request.
  - Does not use Supabase Auth, Supabase access/refresh tokens, service-role credentials, or direct PostgREST writes on Android.

### `data/repository/PlannerRepository.kt`

- `observeSchedules()`
  - 원본 저장 일정 목록 `Flow`를 반환합니다.

- `observeExpandedSchedules(rangeStart, rangeEnd)`
  - Observes stored schedules, recurrence rules, and exceptions as expanded `ScheduleOccurrence` values for the requested range.
  - Starts with a repository refresh, but repeated same-range refreshes may be served from the short TTL cache or coalesced with an in-flight request.

- `observeSchedule(id)` / `getSchedule(id)`
  - 저장 일정 편집 화면에서 사용할 일정 1건을 관찰하거나 조회합니다.

- `getRecurrenceRule(scheduleId)`
  - 저장 일정에 연결된 반복 규칙 1건을 조회합니다.

- `getExpandedSchedules(rangeStart, rangeEnd)`
  - Returns single/recurring occurrences for the requested range from suspend callers.
  - Uses the same short range-keyed schedule refresh cache unless the caller requests force freshness.

- `observePendingCandidates()`
  - Returns the pending candidate list `Flow` for basket/detail setup screens.
  - Starts with a pending-candidate refresh that may use a short TTL or join an in-flight refresh to avoid repeated screen-entry calls.

- `observeCandidate(id)`
  - Returns a candidate `Flow` for edit/conflict screens and uses the in-memory candidate cache before fetching the individual candidate.

- `getCandidate(id, forceRefresh=false)`
  - Returns one candidate for suspend callers. It is cache-first by default, while conflict-sensitive save/complete flows can request force freshness.

- `createCandidate(rawText, sourceApp, receivedAt)`
  - Preserves shared appointment text as `rawText` for the candidate/notification intake path.
  - Calls `KoreanAppointmentParser.parseWithOutcome` and stores parser-derived start/end/location plus stable parse provenance on the pending `AppointmentCandidateEntity`.
  - The candidate title remains blank until the user enters it in the notification or edit screen.
  - Never writes parser debug markers into `extractedLocation`; fallback/parser-error candidates keep the parsed location when present and otherwise store null.
  - If no time can be parsed, start/end remain null instead of using the received/current time placeholder.
  - If the remote candidate API save fails after parsing, keeps an unsynced in-memory local candidate so the share notification/edit flow can continue instead of dropping the shared text.
  - Stores blank `sourceApp` as null.
  - Stores candidate status as `pending`.
  - Deduplicates identical `rawText`/`sourceApp` create requests that arrive within the rapid double-submit window by returning the already-created candidate.
  - Planner API save failures log the candidate payload shape and response snippet without logging the raw shared text body.
  - A 401 response clears the cached session token so stale local sessions from a reset/truncated backend require login again.
- `createScheduleFromInput(rawText, sourceApp, receivedAt)`
  - Compatibility helper for direct typed schedule saves; it now parses the text and delegates to the confirmed quick-add save path instead of creating a `planned` placeholder.

- `parseQuickAddInput(rawText, receivedAt)`
  - Calls `KoreanAppointmentParser.parseWithOutcome` for navigator `+` quick-add and returns start/end/location parse metadata without creating a candidate or notification.

- `createConfirmedScheduleFromQuickAdd(rawText, startAt, endAt, location, sourceApp)`
  - Saves a confirmed personal schedule directly through `/api/planner/schedules` using the raw input as the title, edited start/end/location fields, `sourceText` as the raw input, and `sourceApp` as `quick_add` by default.
  - Does not create, update, or remove pending candidates and does not trigger notification APIs.
  - Merges the saved schedule into the repository cache, invalidates schedule refresh TTL state, and refreshes the current-week widget snapshot from cache.

- `conflictsForCandidate(candidateId, titleOverride)`
  - 후보가 존재하지 않으면 `MissingCandidate`를 반환합니다.
  - 시작 시각이 없으면 `NeedsUncertain`을 반환합니다.
  - 시작/종료 구간으로 충돌 일정을 조회하고, 없으면 `Ready`, 있으면 `Conflict`를 반환합니다.

- `saveFromCandidate(candidateId, selectedStatus, titleOverride, force, recurrenceInput, memoOverride)`
  - transaction 안에서 후보 저장을 처리합니다.
  - 후보가 없으면 `MissingCandidate`, 이미 pending이 아니면 `AlreadyHandled`를 반환합니다.
  - Confirmed saves still require a nonblank `titleOverride` or candidate `extractedTitle`; otherwise `TitleRequired` is returned.
  - Uncertain saves may use a safe internal title from the candidate title, first raw-text line, or `미정 일정`; this fallback title is not written back to the candidate title.
  - Confirmed title overrides update the candidate `extractedTitle` before final save.
  - `memoOverride` is stored on the final schedule memo when provided.
  - Serializes candidate-to-schedule saves so rapid duplicate confirmation actions cannot create two final schedules for the same pending candidate.
  - 상태가 `Uncertain`이거나 시작 시간이 없으면 생성 시각을 fallback 시작 시각으로 사용해 저장하고 `SavedAsUncertain`을 반환합니다.
  - 충돌이 있고 `force=false`이면 `Conflict`를 반환합니다.
  - 충돌이 없거나 강제 저장이면 `insertSchedule` 후 반복 입력이 있으면 반복 규칙을 저장하고, 후보를 `confirmed`로 바꾸고 `Saved`를 반환합니다.

- `updateCandidate(candidateId, title, startAt, endAt, location)`
  - 사용자가 편집 화면에서 입력한 제목/시작/종료/장소 값을 후보에 반영합니다.
  - 제목은 trim하고, 장소 blank는 null로 저장합니다.

- `updateSchedule(scheduleId, title, startAt, endAt, location, memo, status, recurrenceInput)`
  - 저장된 최종 일정을 수정합니다.
  - 제목은 blank일 수 없고, 시작 시각 입력이 null이면 기존 시작 시각을 유지합니다.
  - 장소/메모 blank는 null로 저장하며 `updatedAt`을 현재 시각으로 갱신합니다.
  - 반복 입력이 전달되면 일간/주간/월간 반복 규칙을 저장하거나 반복 없음으로 삭제합니다.

- `skipRecurringOccurrence(scheduleId, occurrenceStartAt)`
  - 특정 반복 occurrence를 건너뛰는 예외를 저장합니다.

- `deleteSchedule(scheduleId)`
  - Calls `DELETE /api/planner/schedules/{id}` to remove the signed-in user's whole personal schedule, including its recurrence rule and recurrence exceptions on the backend.
  - Removes the schedule id directly from `scheduleRecords` after DELETE succeeds, then attempts a schedule refresh so weekly observers drop it immediately.

- `discardCandidate(candidateId)`
  - 후보가 존재하고 아직 `pending`이면 `discarded`로 표시합니다.

- `expandScheduleOccurrences(savedSchedules, rules, exceptions, rangeStart, rangeEnd)`
  - 단일 일정은 범위 안에 있을 때 occurrence로 포함하고, 반복 일정은 `expandRecurringSchedule` 결과로 대체합니다.

- `expandRecurringSchedule(schedule, rule, skipped, rangeStart, rangeEnd)`
  - 일간/주간/월간 반복 규칙을 기준으로 범위 안의 occurrence를 생성하고, skip 예외에 해당하는 occurrence는 제외합니다.
  - 월간 반복에서 기준 일자가 없는 달은 해당 달의 마지막 날로 occurrence를 생성합니다.

- `SaveAttempt`
  - 저장 전 상태 확인 결과입니다: `MissingCandidate`, `NeedsUncertain`, `Ready`, `Conflict`.

- `SaveResult`
  - 실제 저장 시도 결과입니다: `Saved`, `SavedAsUncertain`, `AlreadyHandled`, `MissingCandidate`, `TitleRequired`, `Conflict`.

---

## 알림

### `notification/AppointmentNotificationManager.kt`

- `ensureChannels()`
  - Android O 이상에서 일정 후보 채널과 충돌 채널을 생성합니다.

- `showCandidate(candidate)`
  - Shows the pending detail setup notification when notification permission/channel state allows it.
  - The notification exposes two inline `RemoteInput` actions: `미정` with `일정 메모 작성`, and `확정` with `일정 제목 작성`.
  - When the notification cannot be shown, callers fall back to opening the in-app detail setup screen.

- `showConflict(candidate, existing)`
  - 충돌 알림을 표시합니다.
  - 겹치는 기존 일정 정보를 보여주고, 강제 추가/편집/취소 액션을 제공합니다.

- `cancelCandidate(candidateId)`
  - 후보 알림과 충돌 알림을 모두 제거합니다.

- `cancelCandidatePrompt(candidateId)`
  - 후보 입력 알림만 제거하고 충돌 알림은 유지할 때 사용합니다.

- `saveAction(candidateId, status, label, inputLabel)`
  - Builds an inline notification `RemoteInput` action for the selected save mode; uncertain input is memo text, confirmed input is title text.


- `conflictAction(candidateId, actionName, label)`
  - 충돌 알림의 broadcast 액션을 만듭니다. 강제 추가/취소에 사용됩니다.

- `conflictActivityAction(candidateId, label)`
  - 충돌 해결 화면을 여는 activity 액션을 만듭니다.

- `candidateSummary(candidate)`
  - Builds the one-line candidate notification summary with an explicit start-time row label, unknown-time copy for missing start time, and optional location.

- `candidateDetails(candidate)`
  - Builds expanded candidate notification text with explicit start time, end time, and location rows, plus guidance that uncertain saves use memo text and confirmed saves use title text.

- `formatRange(startAt, endAt)`
  - 알림에 표시할 일정 구간 문자열을 만듭니다. 종료 시각이 없으면 기본 1시간을 적용합니다.

- `canNotify(channelId)`
  - Android 13 런타임 알림 권한, 앱 전체 알림 허용 상태, 지정 알림 채널 차단 여부를 확인합니다.

- `immutablePendingFlags()` / `mutablePendingFlags()`
  - 일반 deep link/action에는 immutable pending intent를 쓰고, inline `RemoteInput` 저장 action에는 mutable pending intent를 사용합니다.

- `conflictNotificationId(candidateId)`
  - 후보 ID 기반으로 충돌 알림 ID를 생성합니다.

### `notification/NotificationActionReceiver.kt`

- `onReceive(context, intent)`
  - 알림 액션 broadcast를 받습니다.
  - `goAsync()`와 앱 scope를 사용해 repository 작업을 비동기로 실행합니다.
  - `ACTION_SAVE`, `ACTION_FORCE_ADD`, `ACTION_CANCEL`을 분기 처리합니다.
  - 처리 결과에 따라 후보 알림/충돌 알림을 정리합니다.

- `handleSave(app, intent, candidateId)`
  - `RemoteInput`에서 사용자가 입력한 텍스트를 읽고, `Uncertain`이면 memo override로, `Confirmed`이면 title override로 전달합니다.
  - intent의 상태 값을 `ScheduleStatus`로 변환합니다.
  - `PlannerRepository.saveFromCandidate`를 호출합니다.
  - 제목이 필요한 확정 저장에서 제목이 없으면 후보 알림을 다시 보여주며, 충돌이면 충돌 알림을 띄웁니다.
  - 충돌 알림을 유지해야 하는지 boolean으로 반환합니다.

---

## UI

### `ui/MainActivity.kt`

- `onCreate(savedInstanceState)`
  - 시작 intent를 route로 변환합니다.
  - Compose theme/surface를 구성하고 `AppRoot`를 표시합니다.
  - 알림 권한 요청과 릴리즈 빌드의 Play in-app update 확인을 시작합니다.

- `onResume()`
  - 진행 중이던 immediate in-app update가 있으면 재개합니다.

- `onNewIntent(intent)`
  - 알림 deep link 등으로 새 intent가 들어오면 화면 route를 갱신합니다.

- `maybeRequestNotifications()`
  - Android 13 이상에서 앱 진입 시 알림 권한을 요청합니다.

- `checkForAppUpdate()`
  - 디버그 빌드가 아니면 Play Core로 즉시 업데이트 가능 여부를 확인하고 update flow를 시작합니다.

- `resumeAppUpdateIfNeeded()`
  - 개발자 트리거 업데이트가 진행 중이면 update flow를 다시 시작합니다.

- `startRoute(intent)`
  - 바구니 route extra가 있으면 바구니 화면으로 이동하고, 후보/충돌 extra가 있으면 해당 상세 화면으로 이동합니다.
  - intent extra를 읽어 기본 일정 화면, 후보 편집, 충돌 해결 route 중 하나로 변환합니다.

- `candidateIntent(context, candidateId)`
  - 후보 편집 화면으로 이동하는 deep link intent를 생성합니다.

- `basketIntent(context)`
  - 공유 저장 실패나 바구니 직접 진입에 사용할 바구니 route intent를 생성합니다.

- `conflictIntent(context, candidateId)`
  - 충돌 해결 화면으로 이동하는 deep link intent를 생성합니다.

- `AppRoot(route, onRoute)`
  - Shows bottom navigation tabs for Schedule, Basket, Sharing, and Settings, with a centered `+` quick-add action between the tab groups.
  - The centered `+` opens a dimmed quick-add dialog with a focused one-line natural-language input, editable parsed start/end/location review, a primary confirm button, and a smaller cancel button.
  - Displays login, weekly schedule, basket, sharing, group availability subroutes, settings, schedule edit, candidate edit, conflict, and completion screens according to the current route.
  - Quick-add confirm calls `createConfirmedScheduleFromQuickAdd`; it bypasses pending candidates and notifications.
  - `Route.ScheduleEdit` preserves the caller as `returnRoute`; edit back/cancel/save/delete completion returns through that source route.

### `ui/LoginScreen.kt`

- `LoginScreen(authRepository, onAuthenticated)`
  - 저장된 세션 토큰이 없을 때 로그인/가입 화면을 표시합니다.
  - id와 password를 받아 `AuthRepository.login` 또는 `AuthRepository.signUp`을 호출합니다.
  - 가입 모드에서는 비밀번호 확인을 검증하고, 성공하면 메인 일정 화면으로 이동합니다.
  - API 미설정, 인증 실패, 입력 검증 오류를 한국어 메시지로 표시합니다.

### `ui/WeeklyScheduleScreen.kt`

- `WeeklyScheduleScreen(repository, onOpenSchedule)`
  - Shows the current Monday-to-Sunday schedule view as a full-screen timetable.
  - The top controls are compacted so the timetable body gets most of the available height.
  - Group availability coordination is not launched from the schedule screen; users enter it from the Sharing tab.

- `WeeklyTimetableWidget(days, schedulesByDay, onPreviousWeek, onNextWeek, onOpenSchedule, onMoveSchedule)`
  - Renders previous/date range/settings/edit/next controls on one compact row; settings opens a time-range dialog and edit mode enables drag-moving schedule blocks.
  - Expands the timetable area vertically so the visible schedule grid is taller.

- `WeeklyScheduleScreen(repository, onOpenSchedule)`
  - `observeExpandedSchedules()`를 구독해 현재 주 월요일부터 일요일까지의 단일/반복 occurrence를 전체 화면 시간표로 표시합니다.
  - 상단 이전 주/다음 주 버튼으로 표시 주간을 한 주씩 앞뒤로 이동합니다.
  - 시간표 일정 블록은 클릭 시 저장 일정 수정 route를 호출하며, 반복 occurrence는 원본 일정 ID와 occurrence 시작 시각을 함께 넘깁니다.

- `WeeklyTimetableWidget(days, schedulesByDay, onPreviousWeek, onNextWeek, onOpenSchedule)`
  - 월요일-일요일 7일 범위와 이전/다음 주 이동 버튼, 설정 버튼을 보여주고, 설정 대화상자에서 시작/끝 시간을 저장해 시간축 범위를 바꿀 수 있는 카드로 렌더링합니다.

- `TimetableHeader(days)`
  - 시간표 상단의 7일 요일/날짜 header를 렌더링합니다.

- `TimetableBody(days, schedulesByDay, startHour, endHour, onOpenSchedule, modifier)`
  - 선택한 시작/끝 시간 범위에 맞춘 시간 grid, 빈 상태, 날짜별 일정 블록을 화면 높이에 맞는 시간표 본문으로 렌더링합니다.

- `TimetableEventBlock(event, dayIndex, dayWidth, railWidth, bodyHeight, onOpenSchedule)`
  - occurrence 1건을 시간 위치와 겹침 lane에 맞춰 클릭 가능한 시간표 블록으로 표시하고, 반복 occurrence에는 반복 라벨을 붙입니다.

  - Prioritizes readable title text; shows compact time/recurrence metadata and location only when the event block has enough height and width.

- `buildTimetableEvents(day, schedules)`
  - 하루 일정들을 시작 시간순으로 정렬하고 겹치는 일정이 나란히 보이도록 lane 정보를 계산합니다.

- `ScheduleOccurrence.localDate()` / `ScheduleEntity.minuteOfDay()` / `ScheduleEntity.endMinuteOfDay(day)`
  - 일정 시작/종료 시각을 시간표 배치용 분 단위 값으로 변환합니다.

- `formatHourLabel(hour)` / `formatCompactRange(startMinute, endMinute)` / `formatCompactMinute(minute)` / `labelOffset(y, bodyHeight)`
  - 시간표 축의 정각 숫자, 일정 블록에 표시할 compact 시간 문자열, 축 라벨 위치를 계산합니다.

### `ui/PlannerScreen.kt`

- `BasketScreen(repository, onOpenCandidate)`
  - Shows the promise basket screen for shared pending candidate setup/list and saved schedule checking.
  - Does not include direct text/detail creation input; new typed schedule creation starts from the navigator centered `+` quick-add action.
  - Shows pending candidates under the schedule detail setup section; selecting one opens `CandidateEditScreen`.
  - Shows final timetable records from `observeExpandedSchedules(rangeStart, rangeEnd)` only after the saved schedule section is expanded, avoiding a schedule fetch on initial basket entry.

### `ui/ScheduleEditScreen.kt`

- `ScheduleEditScreen(repository, scheduleId, occurrenceStartAt, onBack)`
  - `observeSchedule(scheduleId)`로 저장 일정 1건을 관찰합니다.
  - 제목, 달력으로 고르는 시작 날짜, 숫자 키보드로 입력하는 시작/종료 시각, 장소, 메모, 상태, 반복 없음/매일/매주/매월/맞춤 반복, 반복 종료 시각을 편집합니다.
  - 반복 occurrence에서 열린 경우 `skipRecurringOccurrence`로 해당 occurrence만 건너뛸 수 있습니다.
  - 제목과 시작 시각이 유효할 때 `PlannerRepository.updateSchedule`로 저장하고 일정 화면으로 돌아갑니다.

  - Android system back, visible back, cancel, save success, and delete success all call `onBack` so detail returns to its source route.
  - Whole-schedule delete is an upper-right action that calls `PlannerRepository.deleteSchedule` without a confirmation dialog and is distinct from occurrence-only skip/delete.
  - Status, memo, recurrence, and save/cancel controls are compacted into the main edit flow so normal phone layouts show the core settings with minimal overflow scrolling.
  - Save/delete/occurrence-delete actions set an in-flight guard and keep the user on detail with an error message if the repository call fails.

- `PlannerTextField(value, onValueChange, label, required)`
  - 저장 일정 편집 화면의 공통 텍스트 입력 필드를 렌더링합니다.

- `scheduleStatusFromDb(value)` / `statusDbLabel(value)`
  - DB status 문자열과 UI `ScheduleStatus`/라벨을 상호 변환합니다.

### `ui/SharingScreen.kt`

- `SharingScreen(plannerRepository, sharingRepository, onOpenAvailabilityGroups, onBack)`
  - Loads or creates the current user's sharing ID through the external share API, and creates groups with a partner sharing ID.
  - Provides a Sharing-tab entry point into group availability coordination.
  - Displays the signed-in user's personal Supabase schedules and uploads only the schedule selected by the user with its recurrence rule/exceptions.
  - Displays real shared schedules for the selected group, optionally including dummy schedules returned by the API; the initial screen refresh selects the group and lets the selected-group effect load shared schedules once.
  - Shows a setup/login error when the share API base URL or existing app session token is missing.
  - Provides a checkbox for including dummy schedules.

- `LocalShareRow(schedule, enabled, onUpload)`
  - Renders one personal schedule with a share button.

- `SharedScheduleRow(schedule)`
  - Renders one schedule returned by the share API. If `isDummy=true`, it shows the shared-only chip; if recurrence exists, it shows the recurring chip.

### `ui/CandidateEditScreen.kt`

- `CandidateEditScreen(repository, candidateId, onDone, onConflict, onBack)`
  - Observes one pending `AppointmentCandidateEntity` as a schedule detail setup, not as a confirmed final schedule.
  - Lets the user edit the calendar-selected start date, numeric-keyboard start/end time, location, recurrence, and either an uncertain memo or confirmed title before the candidate becomes a final schedule.
  - Shows `미정` and `확정` modes; blank-title candidates default to `미정`, while titled candidates default to `확정`.
  - `미정` shows `일정 메모 작성`, saves `ScheduleStatus.Uncertain`, and keeps memo separate from candidate title.
  - `확정` shows `일정 제목 작성`, requires a nonblank title, updates the candidate, and calls `saveFromCandidate(..., ScheduleStatus.Confirmed, ...)`.
  - Displays parse provenance as LLM parsing, fallback, or unknown.
  - Save, discard, back, and cancel actions use an in-flight guard so rapid duplicate taps cannot submit the same candidate twice.
  - On successful save it returns to the weekly timetable; conflicts still route to the conflict screen.

- `StatusSelector(status, onStatus)`
  - `confirmed`, `planned`, `uncertain` 상태를 고르는 FilterChip row를 렌더링합니다.
  - 각 상태는 `확정`, `예정`, `보류` 라벨로 표시됩니다.

### `ui/ConflictScreen.kt`

- `ConflictScreen(repository, candidateId, onEdit, onDone)`
  - 일정 충돌을 해결하는 화면입니다.
  - 진입 시 `conflictsForCandidate`를 호출해 겹치는 기존 일정을 조회합니다.
  - 시간을 바꿔서 추가, 겹치는 일정 조정, 보류 일정으로 추가, 추가하지 않음 옵션을 radio row로 제공합니다.
  - 시간 변경/일정 조정은 편집 화면으로 돌아가고, 보류는 `Uncertain` 상태로 저장하며, 추가하지 않음은 `discardCandidate`를 호출합니다.
  - 사용자가 강제로 추가하면 `saveFromCandidate(..., force = true)`로 저장합니다.
  - Hold, discard, and force-add actions use an in-flight guard so rapid duplicate taps cannot submit the same candidate twice.

### `ui/AppointmentAddedScreen.kt`

- `AppointmentAddedScreen(repository, candidateId, onOpenPlanner)`
  - 일정 추가 완료 화면입니다.
  - 추가된 후보의 제목, 시간, 장소를 요약해 표시합니다.
  - `일정 보기`와 `확인` 버튼은 주간 일정 화면으로 이동합니다.

### `ui/SettingsScreen.kt`

- `SettingsScreen(authRepository, sharingRepository, feedbackRepository, onLoggedOut)`
  - 계정 관리 카드에서 세션 여부, 캐시된 공유 ID, 로그인 상태, 비밀번호 변경 폼, 로그아웃 동작을 표시합니다.
  - 피드백 남기기 카드에서 사용자가 남긴 문장을 `POST /api/planner/feedback`로 전송하고, 앱 버전과 설정 화면 출처를 함께 저장합니다.
  - 세션 여부, 캐시된 공유 ID, 공유 API 설정 상태, 앱 버전을 표시합니다.
  - 계정 관리 섹션에서 로그인 세션과 인증 API가 있으면 비밀번호 변경 폼을 펼쳐 현재 비밀번호 확인, 새 비밀번호, 새 비밀번호 확인 입력으로 비밀번호를 변경하고 성공 메시지를 표시합니다.
  - 로그아웃 시 저장된 세션 토큰, 공유 ID 캐시, 위젯 snapshot을 삭제하고 로그인 화면으로 이동합니다.

- `SettingsCard(title, content)`
  - 설정 화면의 카드형 섹션을 렌더링합니다.

- `SettingLine(label, value)`
  - 설정 항목의 라벨과 값을 한 줄로 표시합니다.

### `ui/DateTimePickerField.kt`

- `DateTimePickerField(value, onValueChange, label, required)`
  - 날짜/시간 값을 읽기 전용 필드로 표시하고 탭하면 Android `DatePickerDialog`와 `TimePickerDialog`를 순서대로 엽니다.
  - 선택한 날짜/시간을 epoch milliseconds로 변환해 상위 상태에 전달하며, 선택 값이 없는 선택 항목은 비울 수 있습니다.

- `DateAndTimeRangeFields(startMillis, onStartChange, endMillis, onEndChange, requiredStart)`
  - 일정/후보 편집에서 시작 날짜를 달력 필드로 분리하고, 시작 시간과 끝 시간은 `HHMM` 숫자 키보드 입력 필드로 받습니다.
  - 유효한 시간 입력을 선택된 시작 날짜와 결합해 epoch milliseconds로 전달하며, 선택 항목에서는 시작/끝 시간을 함께 지울 수 있습니다.

### `ui/UiFormat.kt`

- `formatDateTime(millis)`
  - epoch milliseconds를 `Asia/Seoul` 기준 `yyyy-MM-dd HH:mm` 문자열로 변환합니다. null이면 빈 문자열입니다.

- `formatDay(millis)`
  - epoch milliseconds를 `Asia/Seoul` 기준 `yyyy-MM-dd` 날짜 문자열로 변환합니다.

- `formatTime(millis)`
  - epoch milliseconds를 `Asia/Seoul` 기준 `HH:mm` 시간 문자열로 변환합니다.

- `parseDateTimeOrNull(value)`
  - 사용자가 입력한 `yyyy-MM-dd HH:mm` 문자열을 epoch milliseconds로 변환합니다.
  - 파싱 실패 시 null을 반환합니다.

### `ui/FeedLoopTheme.kt`

- `FeedLoopCardColors()`
  - 앱 카드에서 공통으로 사용하는 Material3 Card color 설정을 반환합니다.
  - `FeedLoopColors`와 `FeedLoopColorScheme`은 `promise_basket_design.md`의 Promise Basket 팔레트(Soft Apricot, Warm Ivory, Charcoal Brown, 상태 색상 등)를 Material3 색상으로 제공합니다.

---

## 위젯 동기화/렌더링

### `widget/PlannerWidgetSync.kt`

- `syncFromPlannerApiSnapshot(context)`
  - 앱 context가 `OnMyPlateApp`이 아니면 기존 위젯 snapshot을 지우지 않고 동기화를 중단합니다.
  - When the context is `OnMyPlateApp` and a login session exists, fetches current-week expanded schedules through `PlannerRepository.getExpandedSchedules`. Repeated background sync attempts inside the throttle window are skipped.
  - 세션이 없거나 API 조회에 실패하면 빈 snapshot을 저장합니다.
  - 조회 결과로 `saveSnapshot`을 호출합니다.

- `saveSnapshot(context, schedules, refreshWidgets=true)`
  - occurrence를 시작 시각 기준으로 정렬합니다.
  - `Asia/Seoul` 날짜별로 `manualEventsByDate` JSON을 구성합니다.
  - 각 occurrence는 title, startMinute, endMinute, source=`manual`, isRecurring 값을 저장합니다.
  - 현재 주 월요일, viewport 기본값, `native-supabase-schedules-v1` schema, generatedAt을 포함한 snapshot JSON을 SharedPreferences에 저장합니다.
  - When `refreshWidgets=false`, stores only the snapshot and skips widget refresh so serialization tests avoid provider sync side effects.

### `widget/PlannerWidgetStore.java`

- `getPrefs(context)`
  - 위젯 snapshot 저장에 쓰는 SharedPreferences를 반환합니다.

- `saveSummarySnapshot(context, snapshotJson[, refreshWidgets])`
  - `summary_snapshot` key에 snapshot JSON을 동기적으로 저장합니다.
  - 저장 직후 `SummaryWidgetProvider.refreshAll`로 모든 위젯을 갱신합니다.
  - When `refreshWidgets=false`, only saves the snapshot and does not call refresh.

### `widget/SummaryWidgetProvider.java`

- `onUpdate(context, appWidgetManager, appWidgetIds)`
  - 위젯 업데이트 시 DB에서 snapshot 동기화를 요청하고, 각 위젯의 RemoteViews를 갱신합니다.

- `onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)`
  - 위젯 크기/옵션 변경 시 snapshot을 동기화하고 해당 위젯을 다시 렌더링합니다.

- `onDeleted(context, appWidgetIds)`
  - 삭제된 위젯별 week offset과 viewport 축소 상태 preference를 제거합니다.

- `onReceive(context, intent)`
  - 위젯 클릭 액션을 처리합니다.
  - planner 열기, 이전 주, 다음 주, viewport 토글을 분기합니다.
  - 상태 변경 후 해당 위젯 RemoteViews를 갱신합니다.

- `refreshAll(context)`
  - 현재 provider에 속한 모든 위젯 ID를 찾아 다시 렌더링합니다.

- `buildRemoteViews(context, manager, appWidgetId)`
  - SharedPreferences snapshot과 위젯별 상태를 읽습니다.
  - 주간 라벨과 timetable bitmap을 설정합니다.
  - 루트/이전/다음/viewport 토글 클릭 액션을 연결합니다.

- `bindAction(context, views, appWidgetId, viewId, action)`
  - 특정 view ID에 broadcast PendingIntent를 연결합니다.
  - action과 appWidgetId로 unique data URI를 만들어 PendingIntent 충돌을 방지합니다.

- `renderTimetableBitmap(context, manager, appWidgetId, state, days)`
  - 위젯 크기와 viewport에 맞는 주간 시간표 bitmap을 직접 그립니다.
  - 요일 헤더, 시간 grid, 빈 상태 문구, 일정 블록, 겹침 lane 배치를 처리합니다.
  - 위젯 배경/칩/시간표 bitmap은 Promise Basket의 Warm Basket Gradient, warm border, Apricot 일정 블록 색상으로 렌더링합니다.

- `getWidgetSize(context, manager, appWidgetId)`
  - AppWidget option에서 min width/height dp를 읽고 px 단위 `WidgetSize`로 변환합니다.

- `dpToPx(context, dp)`
  - dp 값을 현재 density 기준 px로 변환합니다.

- `formatHourLabel(hour)`
  - 시간 rail에 표시할 `HH:00` 라벨을 만듭니다.

- `formatMinute(minute)`
  - 0~24시 범위로 보정한 minute 값을 `HH:mm` 문자열로 변환합니다.

- `fitWidgetText(text, paint, width)`
  - 주어진 폭에 맞게 텍스트를 그대로 사용하거나 ellipsize합니다.
  - ellipsize 결과가 실질적으로 비어 보이면 폭 기반 최소 문자 수로 fallback합니다.

- `buildWidgetTitle(item)`
  - 일정 제목에 반복 일정 표시 `(P)` suffix를 붙입니다.

- `formatCompactRange(startMinute, endMinute)`
  - 일정 블록 안에 표시할 `시작-종료` compact 문자열을 만듭니다.

- `formatCompactMinute(minute)`
  - minute 값을 compact 시간 문자열로 변환합니다. 정각이면 `HH`, 아니면 `HH:mm`입니다.

- `buildEventLayouts(items)`
  - 같은 날짜의 일정들을 겹침 여부에 따라 lane에 배치합니다.
  - 각 `EventLayout`에 lane 번호와 전체 lane 수를 설정합니다.

- `getWeekOffsetKey(appWidgetId)`
  - 위젯별 주 이동 offset preference key를 만듭니다.

- `getViewportShrunkKey(appWidgetId)`
  - 위젯별 viewport 축소 상태 preference key를 만듭니다.

- `updateWeekOffset(context, appWidgetId, delta)`
  - 위젯별 week offset을 delta만큼 변경해 저장합니다.

- `formatWeekLabel(weekStart)`
  - 주 시작일~종료일 범위 라벨을 만듭니다.

- `formatMonthDay(calendar, includeMonth)`
  - 월/일 라벨을 만듭니다. `includeMonth=false`이면 일자만 표시합니다.

- `parseDate(dateStr)`
  - `yyyy-MM-dd` 문자열을 자정 Calendar로 변환합니다.
  - null/잘못된 값이면 오늘 자정 Calendar를 반환합니다.

- `formatDate(calendar)`
  - Calendar를 `yyyy-MM-dd` 문자열로 변환합니다.

- `WidgetState.from(context, appWidgetId, snapshot)`
  - 위젯별 week offset과 viewport 축소 여부를 읽어 현재 렌더링 상태를 만듭니다.
  - 시작 시간은 08:00, 종료 시간은 축소 시 18:00 / 기본 24:00입니다.

- `SharedPreferencesSnapshot.getWeekStartForOffset(offset)`
  - snapshot의 기준 주 시작일에 offset 주 수를 더한 Calendar를 반환합니다.

- `SharedPreferencesSnapshot.buildWeekDays(offset)`
  - offset이 적용된 월~일 7일 데이터를 만듭니다.
  - 날짜별 수동 일정을 병합하고 시작/종료/제목 순으로 정렬합니다.

- `SharedPreferencesSnapshot.from(context)`
  - SharedPreferences의 snapshot JSON을 읽어 위젯 렌더링 모델로 변환합니다.
  - 파싱 실패 시 빈 snapshot fallback을 반환합니다.

- `ScheduleItemSnapshot.from(json)`
  - JSON 일정 item을 위젯 내부 모델로 변환합니다.
  - 시작/종료 minute 값이 유효하지 않으면 null을 반환합니다.

---

## Supabase Edge Function

### `supabase/functions/planner-api/index.ts`

- `normalizePath(pathname)`
  - Edge Function URL에서 `/planner-api` 또는 `/functions/v1` prefix를 제거해 내부 route path로 정규화합니다.

- `signUp(request)`
  - id/password 요청을 읽어 user id로 salt 처리한 비밀번호 해시를 `planner_users`에 저장하고 `planner_profiles` 행을 보장합니다.
  - user id를 `sessionToken`과 `userId`로 반환합니다.

- `login(request)`
  - 제출된 id가 없으면 `planner_users` 행을 즉시 만들고, 있으면 저장된 비밀번호 해시와 비교한 뒤 Android 인증 응답을 반환합니다.
  - 기존 평문 비밀번호 row는 로그인 성공 시 해시 값으로 교체합니다.

- `changePassword(request)`
  - `Authorization: Bearer <session-token>`에서 사용자 id를 읽고 현재 비밀번호가 저장된 해시와 일치하는지 검증합니다.
  - 새 비밀번호가 6자 이상이면 PBKDF2-SHA256 해시로 `planner_users.password_hash`를 갱신합니다.

- `profile(userId)`
  - 현재 로그인 사용자의 공유 프로필을 조회하거나 생성해 반환합니다.

- `createGroup(userId, request)` / `listGroups(userId)`
  - 공유 ID로 상대 사용자를 찾아 그룹을 만들거나 현재 사용자가 속한 공유 그룹 목록을 반환합니다.

- `uploadSharedSchedule(userId, groupId, request)` / `listSharedSchedules(userId, groupId, includeDummy)`
  - 그룹 멤버 권한을 확인한 뒤 공유 일정과 반복 규칙/예외 업로드 및 조회를 처리합니다.

- `createAvailabilityGroup(userId, request)` / `joinAvailabilityGroup(userId, request)` / `listAvailabilityGroups(userId)`
  - Creates a P0 availability group, joins one by share code, or lists only groups where the caller is already a member. Creation uses a database RPC so the group row and owner membership row are written in one transaction. Join rejects duplicate membership, backfills pending proposal response rows for proposals that existed before the join, and returns only the caller-safe membership summary.

- `getAvailabilityGroup(userId, groupId)` / `listAvailabilityGroupMembers(userId, groupId)` / `getAvailability(userId, groupId, request)`
  - Requires availability group membership before returning metadata or slot counts. Member-list access is owner-only and returns only safe member ids, roles, caller-relative `isMe`, and join timestamps. Availability supports `sort=time|rank`, defaults to chronological `time`, and responses expose aggregate member metadata and `availableCount`/`unavailableCount`/`totalCount` only; they do not return personal schedule titles, memos, locations, source fields, unavailable reasons, or other members' raw login ids.

- `uploadPersonalSchedule(userId, request)`
  - 로그인 사용자의 개인 일정 row를 생성/upsert하고 반복 규칙/예외를 개인 일정 반복 테이블에 저장한 뒤 schedule JSON을 반환합니다.

- `listPersonalSchedules(userId)` / `getPersonalSchedule(userId, scheduleId)` / `updatePersonalSchedule(userId, scheduleId, request)` / `deletePersonalSchedule(userId, scheduleId)`
  - 로그인 사용자가 소유한 개인 일정 목록/단건 조회와 PATCH 수정을 처리하고 반복 metadata를 응답에 포함합니다.

  - DELETE removes the whole personal schedule plus its personal recurrence rule/exceptions atomically through `delete_personal_schedule`, and returns `{ ok: true }`; read/update responses include recurrence metadata.

- `addPersonalScheduleRecurrenceException(userId, scheduleId, request)`
  - 로그인 사용자가 소유한 반복 일정 occurrence skip 예외를 저장합니다.

- `createCandidate(userId, request)` / `listCandidates(userId, status)` / `getCandidate(userId, candidateId)` / `updateCandidate(userId, candidateId, request)` / `discardCandidate(userId, candidateId)`
  - `planner_candidates`에 현재 사용자 후보를 생성, pending 목록 조회, 단건 조회, 편집/상태 변경, discard 처리합니다.

- `submitFeedback(request)`
  - 세션 토큰이 있으면 사용자 ID를 함께 저장하고, 없으면 익명으로 `planner_feedback` row를 추가합니다.
  - 피드백 문장, 앱 버전, 설정 출처를 검증한 뒤 Supabase DB에 저장하고 `{ ok: true }`를 반환합니다.
  - 로그인 사용자의 개인 일정 row와 반복 규칙/예외를 개인 일정 테이블에 upsert/replace합니다.

- `ensureProfile(userId)` / `findExistingGroup(userId, partnerUserId)` / `groupsForUser(userId)`
  - 프로필, 기존 그룹, 사용자별 그룹 목록을 조회하는 database helper입니다.

- `requireGroupMember(userId, groupId)` / `requireUserId(request)`
  - 공유 그룹 접근 권한과 `Authorization: Bearer <session-token>` 인증 값을 검증합니다.
  - `requireUserId` also verifies the bearer id still exists in `planner_users`; missing rows return 401 instead of causing downstream FK errors.

- `readSchedulePayload(request)` / `readRecurrenceRule(value)` / `readRecurrenceExceptions(value)`
  - 요청 JSON에서 일정 본문, 일간/주간/월간 반복 규칙, skip 예외 목록을 읽고 유효성을 검사합니다.

- `saveScheduleRecurrence(scheduleId, recurrence, exceptions, ruleTable, exceptionTable)`
  - 반복 규칙이 없으면 규칙/예외를 삭제하고, 있으면 규칙을 upsert한 뒤 예외 목록을 replace합니다.

- `attachRecurrenceToSchedules(schedules, ruleTable, exceptionTable)`
  - 일정 목록에 반복 규칙과 예외 목록을 붙여 API 응답 JSON 변환에 사용할 수 있게 합니다.

- `readJson(request)`
  - 요청 본문 JSON을 읽고 일정 payload 또는 일반 object로 반환합니다.

- `normalizeIdentifier(value)` / `requiredString(value, message)` / `optionalString(value)`
  - API 입력 문자열을 정규화하고 필수/선택 값을 검증합니다.

- `toGroupJson(group)` / `toScheduleJson(schedule)`
  - DB row를 Android client 응답 JSON 형태로 변환합니다.

- `jsonResponse(body, status)` / `errorResponse(status, message)` / `apiError(status, message)` / `toApiError(error)`
  - 성공/오류 HTTP 응답과 route 처리용 오류 객체를 생성합니다.

---

## Entity 데이터 구조

### `data/entity/AppointmentCandidateEntity.kt`

- `AppointmentCandidateEntity`
  - 공유/직접 입력으로 생성된 “일정 후보”입니다.
  - Stores raw text, source app, parsed start/end/location, user-edited or generated title, stable parse source, status, and creation time.

### `data/entity/ScheduleEntity.kt`

- `ScheduleEntity`
  - 최종 저장된 일정입니다.
  - 제목, 시작/종료 시각, 장소, 메모, 상태, 원본 텍스트/출처, 생성/수정 시각을 보관합니다.

### `data/entity/ScheduleRecurrenceRuleEntity.kt`

- `ScheduleRecurrenceRuleEntity`
  - 저장 일정에 연결된 반복 규칙입니다.
  - 일간/주간/월간 frequency, 반복 간격, 기준 요일/일자, 종료 시각/횟수, 생성/수정 시각을 보관합니다.

### `data/entity/ScheduleRecurrenceExceptionEntity.kt`

- `ScheduleRecurrenceExceptionEntity`
  - 반복 일정의 특정 occurrence에 적용되는 예외입니다.
  - 현재는 occurrence 시작 시각 기준 skip 예외를 보관합니다.

### `data/repository/ScheduleOccurrence.kt`

- `ScheduleOccurrence`
  - 시간표와 위젯에 표시할 단일 occurrence입니다.
  - 원본 일정, 원본 schedule ID, occurrence 시작 시각, 반복 여부를 보관합니다.

- `RecurrenceInput`
  - 저장/수정 화면에서 repository로 전달하는 반복 입력입니다.
  - 반복 없음 또는 일간/주간/월간 반복과 interval, 종료 조건을 표현합니다.

## Build And Release

### `scripts/publish_aab.py`

- `main()`
  - Loads release values from `.env` and the process environment.
  - Verifies that release signing and Play publishing variables are present before running the upload.
  - Runs `gradlew.bat :app:publishAab --no-daemon` on Windows, which builds the signed release AAB and uploads it to the configured Google Play track.
  - Prints the expected release AAB path after a successful publish.


---

## Availability Group Sharing API

### `supabase/functions/planner-api/availability_groups.ts`

- `createAvailabilityGroup(userId, request)`
  - Creates a busy-only availability group with an owner membership in the same database RPC and returns the share code only to the creator.

- `listAvailabilityGroups(userId)`
  - Lists availability groups where the caller has a membership, including safe group metadata, caller membership, and aggregate member summary. Share codes remain creator/owner-only.

- `joinAvailabilityGroup(userId, request)`
  - Joins an active group by share code, rejects duplicate membership, backfills pending response rows for pending proposals created before the caller joined, and returns only the caller-safe membership summary.

- `getAvailabilityGroup(userId, groupId)`
  - Requires group membership and returns group metadata plus aggregate member counts without exposing raw member user IDs. The metadata includes the group's server-owned `visibilityMode`, limited `visibilitySettings`, and `suggestionMode`.

- `listAvailabilityGroupMembers(userId, groupId)`
  - Requires the active group owner and returns the safe member-management list needed for leader assignment. Each member contains only member id, role, caller-relative `isMe`, and `joinedAt`; raw user ids are not serialized.

- `updateAvailabilityGroupSettings(userId, groupId, request)`
  - Requires the active group owner and updates the availability group's suggestion mode or limited visibility settings. The default remains `busy_only`, and expanded visibility is constrained to a non-schedule-detail policy skeleton.

- `assignAvailabilityGroupLeader(userId, groupId, memberId)` / `unassignAvailabilityGroupLeader(userId, groupId, memberId)`
  - Requires the active group owner, changes a non-owner member between `member` and `leader`, and refuses to modify the owner's role.

- `getAvailability(userId, groupId, request)`
  - Requires group membership and returns busy-only slot counts: start/end, available count, unavailable count, total count, and rank score. The optional `sort` query accepts `time` for chronological order or `rank` for best-slot order, defaults to `time`, and rejects invalid values with 400. It includes personal schedules and group-scoped dummy schedules in busy math without returning schedule title, memo, location, source, reason, dummy-vs-real source, or raw member IDs.

- `listAvailabilityGroupDummySchedules(userId, groupId)`
  - Requires group membership and lists only the caller's dummy schedules for that group, including the caller's private note.

- `createAvailabilityGroupDummySchedule(userId, groupId, request)`
  - Requires active group membership and creates a group-scoped dummy busy block owned by the caller. The private note is stored for the caller and is not serialized to other members.

- `deleteAvailabilityGroupDummySchedule(userId, groupId, dummyScheduleId)`
  - Requires group membership and deletes only the caller's own dummy schedule in that group.

- `listAvailabilityGroupProposals(userId, groupId)`
  - Requires group membership and returns proposals with busy-only availability snapshots, response counts, and only the caller's own response detail.

- `createAvailabilityGroupProposal(userId, groupId, request)`
  - Requires active group membership and passes the group's server-side suggestion-mode gate. It snapshots available/unavailable/total counts for the proposed time range using the current visibility policy, creates the proposal, and creates pending responses for all current members.

- `respondToAvailabilityGroupProposal(userId, groupId, proposalId, request)`
  - Requires group membership and updates only the caller's response to `accepted` or `rejected` while the proposal is pending.

- `finalizeAvailabilityGroupProposal(userId, groupId, proposalId)`
  - Requires the active group owner and finalizes a pending proposal by creating confirmed personal schedules only for accepted members. The created schedule title is exactly the proposal title.

- `listAvailabilityGroupProposalComments(userId, groupId, proposalId)` / `createAvailabilityGroupProposalComment(userId, groupId, proposalId, request)`
  - Requires group membership plus participation in the proposal response set before reading or writing comments. Comment responses include the body and caller-relative author flag without raw user IDs or member IDs.
