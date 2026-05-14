# 약속 바구니 Native Planner 함수별 명세

이 문서는 `SPECIFICATION.md`와 현재 `app/src/main/java/com/lss/onmyplate/nativeplanner` 구현을 기준으로, 주요 함수/메서드가 맡는 기능을 파일별로 정리한 문서입니다.

## 핵심 처리 흐름 요약

1. `ShareReceiverActivity.onCreate` 또는 `PlannerScreen`의 직접 입력에서 공유/입력 텍스트를 받습니다.
2. `PlannerRepository.createCandidate`가 입력 텍스트를 보존한 더미 일정 후보를 만들고 `planner-api` 후보 API에 저장합니다.
3. `AppointmentNotificationManager.showCandidate` 또는 `CandidateEditScreen`이 자동 생성 제목을 기본값으로 보여 주고 사용자가 필요하면 수정합니다.
4. `PlannerRepository.saveFromCandidate`가 제목 필수 조건, 미정 저장, 충돌 확인, 강제 저장, 반복 규칙 저장을 처리합니다.
5. 저장된 `ScheduleEntity`와 반복 규칙/예외는 `observeExpandedSchedules`로 주간 occurrence가 된 뒤 `PlannerWidgetSync.saveSnapshot`으로 위젯 snapshot에 반영됩니다.

---

## App Root

### `OnMyPlateApp.kt`

- `onCreate()`
  - 애플리케이션 시작 시 전역 인스턴스를 설정합니다.
  - 알림 채널을 생성합니다.
  - 현재 주 범위의 단일/반복 occurrence 목록을 관찰하다가 변경될 때마다 `PlannerWidgetSync.saveSnapshot`을 호출해 홈 위젯 데이터를 갱신합니다.

- `deleteDatabase("on_my_plate_native.db")`
  - 앱 시작 시 기존 Room DB 파일과 WAL/SHM 파일을 삭제합니다.
  - 앱은 기존 `on_my_plate_native.db`를 읽거나 마이그레이션하지 않습니다.

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
  - 보관된 공유 텍스트로 `PlannerRepository.createCandidate`를 호출해 후보를 생성합니다.
  - 생성된 후보에 대해 `AppointmentNotificationManager.showCandidate`로 후보 확인/저장 알림을 띄웁니다.
  - 저장에는 성공했지만 알림 권한/앱 알림/채널 알림이 꺼져 알림을 표시할 수 없으면 토스트와 로그만 남기고 앱 화면을 열지 않습니다.
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
  - 로컬 파싱 결과를 먼저 만들고, 설정에 따라 LLM 결과를 우선하거나 보조 fallback으로 병합합니다.
  - `preferLlm=true`이면 LLM 결과를 우선 사용하되 누락 필드는 로컬 결과로 채웁니다.
  - 로컬 결과의 시작 시각과 신뢰도가 충분하면 LLM 없이 로컬 결과를 반환할 수 있습니다.

- `parseLocally(rawText, receivedAt)`
  - 수신 시각을 기준일로 변환합니다.
  - `parseDate`, `parseTime`, `parseLocation`을 호출해 날짜/시간/장소를 추출합니다.
  - 날짜와 시간이 모두 있으면 epoch milliseconds `startAt`을 생성합니다.
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
  - 원본 저장 일정, 반복 규칙, 반복 예외를 함께 관찰하고 지정 범위 안의 `ScheduleOccurrence` 목록으로 펼쳐 반환합니다.

- `observeSchedule(id)` / `getSchedule(id)`
  - 저장 일정 편집 화면에서 사용할 일정 1건을 관찰하거나 조회합니다.

- `getRecurrenceRule(scheduleId)`
  - 저장 일정에 연결된 반복 규칙 1건을 조회합니다.

- `getExpandedSchedules(rangeStart, rangeEnd)`
  - suspend 문맥에서 지정 범위 안의 단일/반복 occurrence 목록을 조회합니다.

- `observePendingCandidates()`
  - 플래너 화면에 표시할 미처리 후보 목록 `Flow`를 반환합니다.

- `observeCandidate(id)`
  - 후보 편집/충돌 화면에서 사용할 후보 1건 `Flow`를 반환합니다.

- `getCandidate(id)`
  - 알림 처리 등 suspend 문맥에서 후보 1건을 직접 조회합니다.

- `createCandidate(rawText, sourceApp, receivedAt)`
  - 공유/직접 입력 텍스트를 `rawText`로 보존합니다.
  - 파싱을 건너뛰고 더미 제목, 수신 시각 기준 시작/종료 시각, 더미 장소로 `AppointmentCandidateEntity`를 만듭니다.
  - `sourceApp`은 blank면 null로 저장합니다.
  - 후보 상태는 `pending`으로 저장합니다.
  - Planner API save failures log the candidate payload shape and response snippet without logging the raw shared text body.

- `conflictsForCandidate(candidateId, titleOverride)`
  - 후보가 존재하지 않으면 `MissingCandidate`를 반환합니다.
  - 시작 시각이 없으면 `NeedsUncertain`을 반환합니다.
  - 시작/종료 구간으로 충돌 일정을 조회하고, 없으면 `Ready`, 있으면 `Conflict`를 반환합니다.

- `saveFromCandidate(candidateId, selectedStatus, titleOverride, force, recurrenceInput)`
  - transaction 안에서 후보 저장을 처리합니다.
  - 후보가 없으면 `MissingCandidate`, 이미 pending이 아니면 `AlreadyHandled`를 반환합니다.
  - `titleOverride` 또는 후보의 `extractedTitle`이 비어 있으면 `TitleRequired`를 반환합니다.
  - 제목 override가 있으면 후보의 `extractedTitle`을 먼저 갱신합니다.
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
  - 알림 권한이 있으면 후보 알림을 표시합니다.
  - 후보 알림을 실제로 게시하면 `true`, 권한/앱 알림/채널 알림 차단으로 표시할 수 없으면 `false`를 반환합니다.
  - 파싱된 제목/시간/장소를 보여주고, inline `RemoteInput`으로 제목을 수정해 저장할 수 있는 액션을 제공합니다.
  - 편집 화면으로 이동하는 액션도 제공합니다.

- `showConflict(candidate, existing)`
  - 충돌 알림을 표시합니다.
  - 겹치는 기존 일정 정보를 보여주고, 강제 추가/편집/취소 액션을 제공합니다.

- `cancelCandidate(candidateId)`
  - 후보 알림과 충돌 알림을 모두 제거합니다.

- `cancelCandidatePrompt(candidateId)`
  - 후보 입력 알림만 제거하고 충돌 알림은 유지할 때 사용합니다.

- `saveAction(candidateId, status, label)`
  - 알림에서 제목을 입력하고 저장하는 `RemoteInput` 액션을 만듭니다.

- `editAction(candidateId, label)`
  - 후보 편집 화면을 여는 알림 액션을 만듭니다.

- `conflictAction(candidateId, actionName, label)`
  - 충돌 알림의 broadcast 액션을 만듭니다. 강제 추가/취소에 사용됩니다.

- `conflictActivityAction(candidateId, label)`
  - 충돌 해결 화면을 여는 activity 액션을 만듭니다.

- `candidateSummary(candidate)`
  - 후보 알림의 한 줄 요약을 만듭니다. 시작 시각이 없으면 “시간 미정” 취지의 문구를 사용하고 장소를 덧붙입니다.

- `candidateDetails(candidate)`
  - 후보 알림의 expanded text를 만듭니다. 자동 제목을 수정할 수 있다는 안내와 공유 출처를 포함합니다.

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
  - `RemoteInput`에서 사용자가 입력한 제목을 읽습니다.
  - intent의 상태 값을 `ScheduleStatus`로 변환합니다.
  - `PlannerRepository.saveFromCandidate`를 호출합니다.
  - 제목 override가 없으면 후보의 자동 생성 제목으로 저장하고, 제목이 여전히 없으면 후보 알림을 다시 보여주며, 충돌이면 충돌 알림을 띄웁니다.
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
  - 하단 탭은 `일정`, `바구니`, `설정`만 표시해 MVP 화면 표면을 주간 일정 확인, 후보 정리, 설정으로 제한합니다.
  - 현재 route에 따라 로그인, 주간 일정, 후보 바구니, 공유, 설정, 일정 편집, 후보 편집, 충돌 해결, 추가 완료 화면 중 하나를 표시합니다. 공유 화면 route와 구현은 유지하지만 하단 탭에서는 노출하지 않습니다.
  - 메인 탭 route는 `MascotScaffold`로 감싸고, 후보 저장 완료 후에는 `Route.Complete(candidateId)`로 이동합니다.

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

- `WeeklyTimetableWidget(days, schedulesByDay, onPreviousWeek, onNextWeek, onOpenSchedule)`
  - Renders a single-line top bar with previous week, date range, compact start/end inputs, apply, and next week.
  - Expands the timetable area vertically so the visible schedule grid is taller.

- `WeeklyScheduleScreen(repository, onOpenSchedule)`
  - `observeExpandedSchedules()`를 구독해 현재 주 월요일부터 일요일까지의 단일/반복 occurrence를 전체 화면 시간표로 표시합니다.
  - 상단 이전 주/다음 주 버튼으로 표시 주간을 한 주씩 앞뒤로 이동합니다.
  - 시간표 일정 블록은 클릭 시 저장 일정 수정 route를 호출하며, 반복 occurrence는 원본 일정 ID와 occurrence 시작 시각을 함께 넘깁니다.

- `WeeklyTimetableWidget(days, schedulesByDay, onPreviousWeek, onNextWeek, onOpenSchedule)`
  - 월요일-일요일 7일 범위와 이전/다음 주 이동 버튼을 보여주고, 시작/끝 시간 숫자 입력과 적용 버튼으로 시간축 범위를 바꿀 수 있는 카드로 렌더링합니다.

- `TimetableHeader(days)`
  - 시간표 상단의 7일 요일/날짜 header를 렌더링합니다.

- `TimetableBody(days, schedulesByDay, startHour, endHour, onOpenSchedule, modifier)`
  - 선택한 시작/끝 시간 범위에 맞춘 시간 grid, 빈 상태, 날짜별 일정 블록을 화면 높이에 맞는 시간표 본문으로 렌더링합니다.

- `TimetableEventBlock(event, dayIndex, dayWidth, railWidth, bodyHeight, onOpenSchedule)`
  - occurrence 1건을 시간 위치와 겹침 lane에 맞춰 클릭 가능한 시간표 블록으로 표시하고, 반복 occurrence에는 반복 라벨을 붙입니다.

- `buildTimetableEvents(day, schedules)`
  - 하루 일정들을 시작 시간순으로 정렬하고 겹치는 일정이 나란히 보이도록 lane 정보를 계산합니다.

- `ScheduleOccurrence.localDate()` / `ScheduleEntity.minuteOfDay()` / `ScheduleEntity.endMinuteOfDay(day)`
  - 일정 시작/종료 시각을 시간표 배치용 분 단위 값으로 변환합니다.

- `formatHourLabel(hour)` / `formatCompactRange(startMinute, endMinute)` / `formatCompactMinute(minute)` / `labelOffset(y, bodyHeight)`
  - 시간표 축의 정각 숫자, 일정 블록에 표시할 compact 시간 문자열, 축 라벨 위치를 계산합니다.

### `ui/PlannerScreen.kt`

- `BasketScreen(repository, onOpenCandidate)`
  - `observePendingCandidates()`를 구독해 아직 처리되지 않아 정리가 필요한 약속 후보를 표시합니다.
  - 화면 제목을 `약속 바구니`로 표시하고, 상단 문구는 확인할 후보 수를 안내합니다.
  - 사용자가 약속 메시지를 직접 입력하면 `createCandidate`로 후보를 만들고 후보 상세 화면으로 이동합니다.
  - 후보 생성 실패 시 실제 예외 메시지를 토스트와 카드 안의 `저장 실패` 문구로 표시합니다.
  - 후보 목록은 `CandidateBasketCard`로 표시하며, 제목/시간/장소와 확인 필요 상태를 보여줍니다.
  - pending 후보가 없으면 체크할 애매한 일정이 없다는 안내 카드를 표시합니다.
  - 하단의 `확정한 일정` 접힘 섹션에서 `observeExpandedSchedules(rangeStart, rangeEnd)`로 저장된 일정과 반복 occurrence를 함께 표시합니다.
  - 확정 일정 필터는 `하루`, `7일`, `한 달`, `기간`이며, `기간`은 시작일 00:00부터 종료일 다음날 00:00 전까지 조회합니다.
  - Direct input save failures are logged, shown as a toast, and kept inside the current screen instead of escaping the coroutine.

- `CandidateBasketCard(candidate, onClick, modifier)`
  - 후보 카드 클릭 시 후보 상세 편집 화면을 엽니다.
  - 제목과 시작 시간이 있으면 `확인 후 저장`, 부족하면 `정보 확인 필요` chip과 success/warning accent를 표시합니다.

### `ui/ScheduleEditScreen.kt`

- `ScheduleEditScreen(repository, scheduleId, occurrenceStartAt, onBack)`
  - `observeSchedule(scheduleId)`로 저장 일정 1건을 관찰합니다.
  - 제목, 시작/종료 시각, 장소, 메모, 상태, 반복 없음/매일/매주/매월/맞춤 반복, 반복 종료 시각을 편집합니다.
  - 반복 occurrence에서 열린 경우 `skipRecurringOccurrence`로 해당 occurrence만 건너뛸 수 있습니다.
  - 제목과 시작 시각이 유효할 때 `PlannerRepository.updateSchedule`로 저장하고 일정 화면으로 돌아갑니다.

- `PlannerTextField(value, onValueChange, label, required)`
  - 저장 일정 편집 화면의 공통 텍스트 입력 필드를 렌더링합니다.

- `scheduleStatusFromDb(value)` / `statusDbLabel(value)`
  - DB status 문자열과 UI `ScheduleStatus`/라벨을 상호 변환합니다.

### `ui/SharingScreen.kt`

- `SharingScreen(plannerRepository, sharingRepository, onBack)`
  - Loads or creates the current user's sharing ID through the external share API, and creates groups with a partner sharing ID.
  - Displays the signed-in user's personal Supabase schedules and uploads only the schedule selected by the user with its recurrence rule/exceptions.
  - Displays real shared schedules for the selected group, optionally including dummy schedules returned by the API.
  - Shows a setup/login error when the share API base URL or existing app session token is missing.
  - Provides a checkbox for including dummy schedules.

- `LocalShareRow(schedule, enabled, onUpload)`
  - Renders one personal schedule with a share button.

- `SharedScheduleRow(schedule)`
  - Renders one schedule returned by the share API. If `isDummy=true`, it shows the shared-only chip; if recurrence exists, it shows the recurring chip.

### `ui/CandidateEditScreen.kt`

- `CandidateEditScreen(repository, candidateId, onDone, onConflict, onBack)`
  - 후보 ID로 약속 후보를 관찰합니다.
  - 후보의 제목, 시작/종료 날짜와 시간, 장소, 반복 없음/매일/매주/매월/맞춤 반복, 반복 종료 시각을 편집할 수 있습니다.
  - 반복 기본값은 원문 내용과 관계없이 `반복 안함`입니다.
  - 원문 메시지, 신뢰도 progress, 상태 선택 chip을 표시합니다.
  - 후보가 없으면 후보를 찾을 수 없다는 안내를 표시합니다.
  - 저장 시 먼저 `updateCandidate`로 편집 값을 반영한 뒤 반복 입력과 함께 `saveFromCandidate`를 호출합니다.
  - 충돌이 있으면 충돌 화면으로 이동하고, 저장 성공 시 개인 일정 동기화를 요청한 뒤 완료 화면으로 이동합니다.

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

- `syncFromPlannerDatabase(context)`
  - 앱 context가 `OnMyPlateApp`이고 로그인 세션이 있으면 Supabase 개인 일정 API에서 현재 주 범위의 단일/반복 occurrence를 조회합니다.
  - 세션이 없거나 API 조회에 실패하면 빈 snapshot을 저장합니다.
  - 조회 결과로 `saveSnapshot`을 호출합니다.

- `saveSnapshot(context, schedules)`
  - occurrence를 시작 시각 기준으로 정렬합니다.
  - `Asia/Seoul` 날짜별로 `manualEventsByDate` JSON을 구성합니다.
  - 각 occurrence는 title, startMinute, endMinute, source=`manual`, isRecurring 값을 저장합니다.
  - 현재 주 월요일, viewport 기본값, `native-supabase-schedules-v1` schema, generatedAt을 포함한 snapshot JSON을 SharedPreferences에 저장합니다.

### `widget/PlannerWidgetStore.java`

- `getPrefs(context)`
  - 위젯 snapshot 저장에 쓰는 SharedPreferences를 반환합니다.

- `saveSummarySnapshot(context, snapshotJson)`
  - `summary_snapshot` key에 snapshot JSON을 동기적으로 저장합니다.
  - 저장 직후 `SummaryWidgetProvider.refreshAll`로 모든 위젯을 갱신합니다.

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
  - Edge Function URL에서 `/planner-api` prefix를 제거해 내부 route path로 정규화합니다.

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

- `uploadPersonalSchedule(userId, request)`
  - 로그인 사용자의 개인 일정 row를 생성/upsert하고 반복 규칙/예외를 개인 일정 반복 테이블에 저장한 뒤 schedule JSON을 반환합니다.

- `listPersonalSchedules(userId)` / `getPersonalSchedule(userId, scheduleId)` / `updatePersonalSchedule(userId, scheduleId, request)`
  - 로그인 사용자가 소유한 개인 일정 목록/단건 조회와 PATCH 수정을 처리하고 반복 metadata를 응답에 포함합니다.

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
  - 원본 텍스트, 공유 출처, 파싱된 시작/종료/장소, 자동 생성 또는 사용자가 수정한 제목, 상태, 생성 시각을 보관합니다.

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
