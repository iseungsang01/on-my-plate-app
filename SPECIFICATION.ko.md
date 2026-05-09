# On My Plate Native Planner 사양

이 문서는 현재 네이티브 Android 구현을 설명합니다. 코드 변경 전에 각 동작이 어디에 있는지 파악하기 위한 코드 중심 문서입니다.

## 핵심 흐름

1. Android가 `ShareReceiverActivity`를 통해 공유된 `text/plain`을 받습니다.
2. 공유 텍스트는 `AppointmentCandidateEntity`로 저장됩니다.
3. `KoreanAppointmentParser`가 로컬 한국어 날짜/시간/장소 패턴을 파싱합니다.
4. 설정된 경우 `GeminiAppointmentParser`가 같은 메타데이터를 Gemini로 파싱하고 로컬 fallback과 병합할 수 있습니다.
5. 파서는 일정 제목을 추출하지 않습니다. 제목 필드는 사용자가 입력하기 전까지 비어 있습니다.
6. `AppointmentNotificationManager`가 파싱된 시간/장소와 인라인 제목 입력을 포함한 후보 알림을 표시합니다.
7. `NotificationActionReceiver`가 알림 액션을 처리하고 저장 요청을 `PlannerRepository`로 전달합니다.
8. `PlannerRepository.saveFromCandidate`는 비어 있지 않은 제목을 요구하고, 충돌을 확인한 뒤 `ScheduleEntity`를 삽입하고 후보를 처리 완료로 표시합니다.
9. `MainActivity`가 플래너, 후보 편집, 충돌 해결 Compose 화면을 호스팅합니다.
10. 저장된 일정은 Room에서 `PlannerWidgetSync`로 흐른 뒤 네이티브 위젯 스냅샷에 반영됩니다.

## 데이터 규칙

- `rawText`: 공유 원본 텍스트 그대로.
- `extractedTitle`: 사용자가 입력한 제목. 파싱 직후에는 의도적으로 비어 있습니다.
- `extractedStartAt`: 파싱된 시작 시간의 epoch milliseconds, nullable.
- `extractedEndAt`: 파싱된 명시적 종료 시간의 epoch milliseconds, nullable.
- `extractedLocation`: 장소가 있으면 파싱된 값, 없으면 null 또는 UI상 빈 값.
- `confidence`: `0.0`부터 `1.0`까지의 파서 신뢰도.
- `timeConfidence`: `high`, `medium`, `low` 중 하나.
- 제목이 비어 있으면 일정을 저장할 수 없습니다.
- 시작 시간이 없거나 사용자가 미정 저장을 선택하면, 후보 생성 시간을 fallback 시작 시간으로 사용해 `uncertain` 상태로 저장합니다.
- 종료 시간이 없을 때 충돌 감지는 기본 1시간 길이를 가정합니다.

## 파일 지도

### 앱 루트

`app/src/main/AndroidManifest.xml`

- `INTERNET`과 `POST_NOTIFICATIONS`를 선언합니다.
- `ShareReceiverActivity`를 `text/plain`용 exported `ACTION_SEND` 대상으로 등록합니다.
- `MainActivity`, `NotificationActionReceiver`, `SummaryWidgetProvider`를 등록합니다.

`app/src/main/java/com/lss/onmyplate/nativeplanner/OnMyPlateApp.kt`

- 애플리케이션 수준 의존성 보관자입니다.
- `AppDatabase`, `KoreanAppointmentParser`, `PlannerRepository`, `AppointmentNotificationManager`를 생성합니다.
- `BuildConfig`에서 Gemini 설정을 연결합니다.
- 일정 관찰을 시작하고 `PlannerWidgetSync`를 통해 위젯 스냅샷을 씁니다.

### 공유 입력

`share/ShareReceiverActivity.kt`

- `onCreate`: 들어온 intent가 `ACTION_SEND`와 `text/plain`인지 검증합니다.
- 알림 권한을 요청하는 동안 공유 텍스트와 source 메타데이터를 임시 보관합니다.
- `saveAndNotify`: repository로 후보를 만들고 후보 알림을 표시한 뒤 투명 activity를 종료합니다.
- `needsNotificationPermission`: Android 13 이상에서 알림 권한 필요 여부를 확인합니다.

### 파싱

`domain/parser/AppointmentLlmParser.kt`

- 비동기 LLM 파서를 위한 작은 인터페이스입니다.
- `AppointmentParseResult?`를 반환하며, null은 로컬 파싱으로 fallback한다는 뜻입니다.

`domain/parser/KoreanAppointmentParser.kt`

- 로컬 파싱과 선택적 LLM 파싱을 조율합니다.
- `parse`: `preferLlm` 설정에 따라 로컬 결과, LLM 결과, 병합 fallback 중 하나를 반환합니다.
- `parseLocally`: 날짜, 시간, 장소, 신뢰도, 시간 신뢰도를 추출합니다.
- `parseDate`: 상대 한국어 날짜, 월/일, 이번 주/다음 주 요일 표현을 지원합니다.
- `parseTime`: 오전/오후/저녁/밤, 콜론 시간, 한국어 `시`, `분`, `반` 표현을 지원합니다.
- `parseLocation`: 명시적 `장소`/`위치` 라벨과 일반적인 한국어 장소 표현을 추출합니다.
- `mergeFallback`: 제목은 비워 둔 채 로컬/LLM 결과의 nullable 메타데이터를 병합합니다.

`domain/parser/GeminiAppointmentParser.kt`

- Gemini `generateContent`를 사용하는 `AppointmentLlmParser` 구현입니다.
- `parse`: API 키가 비어 있으면 작업을 건너뛰고, 실패 시 null을 반환합니다.
- `post`: prompt와 JSON 응답 설정을 Gemini에 보냅니다.
- `prompt`: Gemini에 start/end/location/confidence만 파싱하고 제목은 추출하지 말라고 명시합니다.
- `parseResponse`: Gemini JSON을 제목이 비어 있는 `AppointmentParseResult`로 변환합니다.

### 도메인 모델

`domain/model/Models.kt`

- `ScheduleStatus`: confirmed, planned, uncertain 일정 상태.
- `CandidateStatus`: pending, confirmed, discarded 후보 상태.
- `TimeConfidence`: high, medium, low 시간 파싱 신뢰도.
- `AppointmentParseResult`: 로컬/LLM 파서가 공유하는 출력 모델.

`domain/conflict/ConflictDetector.kt`

- `newEnd`: 명시적 종료 시간이 있으면 사용하고, 없으면 기본 1시간 길이를 사용합니다.
- `conflicts`: 새 일정과 기존 일정의 구간 겹침을 검사합니다.

### 영속성

`data/db/AppDatabase.kt`

- Room database 정의입니다.
- `schedules`와 `appointment_candidates` 테이블을 소유합니다.
- `create`: 로컬 영구 database를 빌드합니다.

`data/entity/AppointmentCandidateEntity.kt`

- 공유 텍스트 후보용 Room entity입니다.
- 확정 전 파싱 결과와 source 메타데이터를 표현합니다.
- `extractedTitle`은 사용자가 입력하는 값이며 pending 동안 비어 있을 수 있습니다.

`data/entity/ScheduleEntity.kt`

- 저장된 일정용 Room entity입니다.
- 최종 제목, 시간, 장소, 상태, 원본 source text, timestamp를 저장합니다.

`data/dao/AppointmentCandidateDao.kt`

- `get`: 후보 하나를 로드합니다.
- `observe`: 편집/충돌 화면용 후보 하나를 stream합니다.
- `observePending`: 플래너 화면용 pending 후보를 stream합니다.
- `insert`와 `update`: 후보 상태를 저장합니다.

`data/dao/ScheduleDao.kt`

- `observeAll`: UI/위젯용으로 정렬된 일정 stream을 제공합니다.
- `getAll`: 테스트/헬퍼용 전체 일정 로드입니다.
- `findConflicts`: start/end 범위로 겹침을 찾는 SQL 쿼리입니다.
- `insert`와 `update`: 일정을 저장합니다.

`data/repository/PlannerRepository.kt`

- 앱의 주요 data/use-case 경계입니다.
- `createCandidate`: 공유 텍스트를 파싱하고 제목이 비어 있는 pending 후보를 삽입합니다.
- `conflictsForCandidate`: 후보가 기존 일정과 겹치는지 검사합니다.
- `saveFromCandidate`: 입력된 제목을 요구하고, 미정 저장과 충돌 감지를 처리하며, 일정을 삽입하고 후보 상태를 갱신합니다.
- `updateCandidate`: 제목, 시작, 종료, 장소에 대한 사용자 수정값을 저장합니다.
- `discardCandidate`: pending 후보를 discarded로 표시합니다.
- `insertSchedule`: 최종 `ScheduleEntity`를 만드는 private helper입니다.
- `SaveAttempt`: 저장 준비 상태 또는 충돌 상태를 설명합니다.
- `SaveResult`: `TitleRequired`를 포함한 최종 저장 결과를 설명합니다.

### 알림

`notification/AppointmentNotificationManager.kt`

- 후보와 충돌 알림 채널을 생성합니다.
- `showCandidate`: 파싱된 시간/장소를 보여 주고 사용자에게 제목 입력을 요청합니다.
- `showConflict`: force-add, edit, cancel 액션과 함께 충돌 정보를 표시합니다.
- `cancelCandidate`와 `cancelCandidatePrompt`: 후보/충돌 알림을 정리합니다.
- `saveAction`: 제목 입력과 저장을 위한 `RemoteInput` 액션을 생성합니다.
- `editAction`: 후보 편집 화면을 엽니다.
- `candidateSummary`와 `candidateDetails`: 제목을 지어내지 않고 알림 문구를 포맷합니다.

`notification/NotificationActionReceiver.kt`

- 알림 액션용 broadcast receiver입니다.
- `onReceive`: `goAsync`로 repository 작업을 비동기 실행합니다.
- `handleSave`: `RemoteInput`에서 입력 제목을 읽고 repository로 저장하며, 제목이 없으면 후보 알림을 다시 보여 주고, 충돌이 있으면 충돌 처리를 엽니다.

### UI

`ui/MainActivity.kt`

- Compose host activity와 route 소유자입니다.
- 앱 진입 경로에서 알림 권한을 요청합니다.
- 릴리스 빌드에서 Google Play 인앱 업데이트를 확인합니다.
- `startRoute`: intent를 플래너, 후보 편집, 충돌 route로 매핑합니다.
- `candidateIntent`와 `conflictIntent`: 알림 deep link를 만듭니다.
- `AppRoot`: 현재 Compose 화면을 선택합니다.

`ui/PlannerScreen.kt`

- pending 후보와 저장된 일정을 보여 줍니다.
- 제목이 없는 pending 후보에는 `제목 입력 필요`를 표시합니다.
- pending chip을 누르면 후보 편집 화면을 엽니다.

`ui/CandidateEditScreen.kt`

- 사용자가 제목을 입력하고 파싱된 시작/종료/장소 필드를 수정할 수 있습니다.
- 제목이 비어 있으면 저장이 비활성화됩니다.
- `PlannerRepository.saveFromCandidate`를 통해 저장합니다.
- repository가 충돌을 보고하면 충돌 화면으로 이동합니다.

`ui/ConflictScreen.kt`

- 후보와 겹치는 기존 일정을 표시합니다.
- 사용자가 강제 추가, 편집, 취소를 선택할 수 있습니다.
- 후보에 제목이 없으면 강제 추가가 비활성화됩니다.

`ui/UiFormat.kt`

- Compose UI에서 공유하는 날짜/시간 포맷입니다.
- `parseDateTimeOrNull`: `yyyy-MM-dd HH:mm` 사용자 입력을 파싱합니다.

### 위젯

`widget/PlannerWidgetSync.kt`

- Room 일정을 위젯 스냅샷 형식으로 변환합니다.
- `syncFromPlannerDatabase`: 저장된 일정을 로드하고 스냅샷을 씁니다.
- `saveSnapshot`: `Asia/Seoul` 로컬 날짜별로 일정을 묶고 JSON을 shared preferences에 저장합니다.

`widget/PlannerWidgetStore.java`

- 네이티브 위젯 코드가 사용하는 SharedPreferences helper입니다.
- `PlannerWidgetSync`와 `SummaryWidgetProvider`가 사용하는 스냅샷 저장 key를 정의합니다.

`widget/SummaryWidgetProvider.java`

- 네이티브 Android `AppWidgetProvider`입니다.
- 저장된 스냅샷을 읽고 주간 요약 위젯을 렌더링합니다.
- 위젯 sizing, 스냅샷 파싱, RemoteViews layout 로직을 포함합니다.

## 빌드와 릴리스 파일

`app/build.gradle.kts`

- Android application Gradle 설정입니다.
- `.env`/환경 변수에서 application id, version, Gemini config, release signing, Play publishing 값을 읽습니다.
- Gradle Play Publisher로 서명된 release bundle을 게시하는 `publishAab`를 정의합니다.

`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`

- 루트 Gradle과 wrapper 설정입니다.
- `gradlew`와 `gradlew.bat`는 루트에서 실행하는 표준 launcher이고, wrapper jar/properties는 `gradle/wrapper/` 폴더에 보관됩니다.

## 테스트

`app/src/test/java/.../domain/parser/KoreanAppointmentParserTest.kt`

- 한국어 상대 날짜/시간/장소 파싱을 검증합니다.
- LLM 병합 동작을 검증합니다.
- 파서 출력의 제목이 비어 있는지 검증합니다.

`app/src/test/java/.../data/repository/PlannerRepositoryTest.kt`

- 후보 생성, 저장 동작, 충돌 동작, 제목 필수 동작을 검증합니다.

`app/src/test/java/.../domain/conflict/ConflictDetectorTest.kt`

- 일정 구간 겹침 로직을 검증합니다.

`app/src/test/java/.../widget/PlannerWidgetSyncTest.kt`

- Room 일정에서 위젯 스냅샷이 생성되는지 검증합니다.

## Supabase sharing architecture

- Local Room remains the source of truth for personal schedules, conflict checks, candidate parsing, and widget snapshots.
- Sharing is opt-in from the planner screen. Android calls the external planner sharing API configured by `PLANNER_API_BASE_URL`.
- Android reads the existing app login token from SharedPreferences and sends it as `Authorization: Bearer <token>`. Defaults: `PLANNER_SESSION_PREFS_NAME=planner_auth`, `PLANNER_SESSION_TOKEN_KEY=session_token`.
- Android does not create anonymous Supabase Auth sessions, store Supabase access/refresh tokens, or write directly to Supabase PostgREST tables.
- A trusted backend verifies the existing session token, resolves the app user ID, checks group membership/ownership, and performs Supabase DB work with server-only credentials.
- Entering a partner `public_id` asks the sharing API to create or reuse a group and return accessible groups/schedules.
- Only selected local schedules are uploaded to the sharing API; they remain independent copies of Room rows.
- Shared-screen-only dummy schedules are read through the API but are never inserted into Room, conflict detection, notifications, or widget snapshots.
- Required mobile configuration: `PLANNER_API_BASE_URL`. `SUPABASE_SERVICE_ROLE_KEY` is server-only and must never be included in the app.
