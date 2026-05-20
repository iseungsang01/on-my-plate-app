# Edge Function 호출량 절감 계획서

대상 레포: `iseungsang01/on-my-plate-app`  
대상 Edge Function: Supabase `planner-api`  
목표: 의미 없거나 반복되는 Edge Function 호출을 줄이고, UX 저하 없이 API 사용량과 비용을 낮춘다.

---

## 0. 문제 정의

현재 앱은 Supabase Edge Function `planner-api`를 단일 API gateway처럼 사용한다. 로그인, 일정 조회, 후보 일정 생성, 후보 조회, 일정 저장, 위젯 동기화, 공유 기능, 피드백, LLM parser proxy가 모두 이 Edge Function을 통과한다.

기존에 의심했던 “로그인 토큰 만료 때문에 반복 호출이 발생한다”는 가설은 핵심 원인이 아니다. 현재 구조는 이미 로그인 시 session token을 발급하고, Android가 SharedPreferences에 저장한 뒤 `Authorization: Bearer <token>` 방식으로 API를 호출한다. 서버 쪽 세션 TTL도 기본 30일로 설정되어 있다.

따라서 호출량 절감의 핵심은 다음이다.

1. 화면 진입 시 자동 refresh가 과도하게 발생하는 부분 제거
2. 위젯 sync와 앱 내부 schedule refresh 중복 제거
3. local parser로 충분한 경우 LLM parser proxy 호출 생략
4. 공유 화면 등에서 같은 데이터를 연속으로 다시 가져오는 부분 제거
5. repository 레벨에서 TTL, in-flight request coalescing, explicit refresh 정책 도입

---

## 1. 호출량이 커질 가능성이 높은 지점

### 1.1 일정 조회

주요 경로:

- `PlannerRepository.observeSchedules()`
- `PlannerRepository.observeExpandedSchedules(rangeStart, rangeEnd)`
- `PlannerRepository.getSchedules()`
- `PlannerRepository.getExpandedSchedules(rangeStart, rangeEnd)`

위 함수들은 내부적으로 `refreshSchedules()` 또는 `refreshSchedules(rangeStart, rangeEnd)`를 호출한다. 특히 `observeExpandedSchedules()`는 Flow 시작 시점에 `onStart { refreshSchedules(...) }`를 실행하므로, 화면 collector가 새로 시작될 때마다 Edge Function 호출이 발생할 수 있다.

영향이 큰 화면:

- `WeeklyScheduleScreen`
- `BasketScreen`
- `OnMyPlateApp`의 앱 시작 widget snapshot observer
- `PlannerWidgetSync.syncFromPlannerApiSnapshot()`
- `SharingScreen`의 개인 일정 목록 조회

예상 호출 endpoint:

```text
GET /api/planner/schedules
```

---

### 1.2 후보 일정 조회

주요 경로:

- `PlannerRepository.observePendingCandidates()`
- `PlannerRepository.observeCandidate(id)`
- `PlannerRepository.getCandidate(id)`
- `refreshPendingCandidates()`
- `refreshCandidate(id)`

바구니 화면 진입 시 pending candidate 목록을 가져오고, candidate edit 화면 진입 시 candidate 단건 조회가 발생한다. 저장 후에도 pending candidates refresh가 반복된다.

예상 호출 endpoint:

```text
GET /api/planner/candidates?status=pending
GET /api/planner/candidates/{candidateId}
```

---

### 1.3 LLM parser proxy

주요 경로:

- `OnMyPlateApp`에서 `KoreanAppointmentParser(preferLlm = true)`로 생성
- `KoreanAppointmentParser.parseWithOutcome(...)`
- `GeminiAppointmentParser.parse(...)`
- `PlannerHttpClient.request("POST", "/api/parser/appointment", ...)`

현재 `preferLlm = true`이면 local parser가 충분히 잘 맞아도 LLM proxy를 먼저 호출할 수 있다. 공유 텍스트나 직접 입력이 많은 경우 Edge Function 호출량과 Gemini 비용이 같이 증가한다.

예상 호출 endpoint:

```text
POST /api/parser/appointment
```

---

### 1.4 위젯 동기화

주요 경로:

- `OnMyPlateApp.onCreate()`에서 현재 주간 schedule observe
- `PlannerWidgetSync.syncFromPlannerApiSnapshot(context)`
- `SummaryWidgetProvider`의 update/resize 트리거

위젯은 마지막 유효 snapshot을 SharedPreferences에 저장하는 구조인데, update/resize마다 API fetch가 발생할 수 있다. 앱 시작 observer와 widget sync가 겹치면 같은 주간 schedule fetch가 반복될 수 있다.

예상 호출 endpoint:

```text
GET /api/planner/schedules
```

---

### 1.5 공유 화면

주요 경로:

- `SharingRepository.ensureProfile()`
- `SharingRepository.listGroups()`
- `SharingRepository.listSharedSchedules(...)`
- `plannerRepository.getSchedules()`

`SharingScreen.refresh()`에서 shared schedules를 가져온 뒤, `LaunchedEffect(selectedGroup?.id, includeDummy)`에서도 다시 shared schedules를 가져올 수 있다. 초기 진입 시 중복 호출 가능성이 있다.

예상 호출 endpoint:

```text
POST /api/planner/share/profile
GET /api/planner/share/groups
GET /api/planner/share/groups/{groupId}/schedules?includeDummy=true
GET /api/planner/schedules
```

---

## 2. P0: 즉시 적용 가능한 호출 차단

P0의 목표는 구조 변경을 최소화하면서 “명백히 낭비인 호출”을 먼저 제거하는 것이다. 위험도가 낮고, 실패해도 롤백이 쉽다.

---

### P0-1. 바구니 화면의 저장 일정 lazy fetch

#### 문제

`BasketScreen`은 저장된 일정 섹션이 접혀 있어도 `repository.observeExpandedSchedules(...)`를 collect한다. 따라서 사용자가 바구니 탭에 들어가기만 해도 schedule fetch가 발생한다.

#### 변경

`confirmedExpanded == true`일 때만 `observeExpandedSchedules(...)`를 collect한다. 접힌 상태에서는 `flowOf(emptyList())`를 사용한다.

#### 기대 효과

- 바구니 탭 진입 시 불필요한 `GET /api/planner/schedules` 제거
- 사용자가 실제로 “시간표에 들어간 일정” 섹션을 펼칠 때만 fetch

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/ui/PlannerScreen.kt
```

#### 검증

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

수동 확인:

1. 바구니 탭 진입
2. 저장 일정 섹션을 펼치기 전에는 schedule fetch 로그가 증가하지 않는지 확인
3. 섹션을 펼치면 일정이 정상 표시되는지 확인

---

### P0-2. 위젯 sync throttle 추가

#### 문제

`PlannerWidgetSync.syncFromPlannerApiSnapshot()`은 widget update/resize가 발생할 때마다 schedule fetch를 만들 수 있다. 위젯은 초단위 최신성이 필요하지 않다.

#### 변경

`PlannerWidgetSync`에 마지막 sync 시작 시간을 저장하고, 45초 이내 반복 sync는 무시한다.

#### 기대 효과

- 위젯 update/resize 반복에 따른 schedule fetch 감소
- 앱 시작 observer와 widget sync가 겹칠 때 중복 fetch 감소

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/widget/PlannerWidgetSync.kt
```

#### 검증

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

수동 확인:

1. 위젯 resize/update 반복
2. 45초 이내에는 API fetch가 반복되지 않는지 확인
3. 일정 저장/수정/삭제 후 widget snapshot은 정상 갱신되는지 확인

---

### P0-3. 공유 화면 초기 shared schedule 중복 fetch 제거

#### 문제

`SharingScreen.refresh()`에서 selected group의 shared schedules를 가져오고, 곧바로 `LaunchedEffect(selectedGroup?.id, includeDummy)`에서도 같은 목록을 다시 가져올 수 있다.

#### 변경

초기 `refresh()`에서는 profile, groups, local schedules만 불러오고 selectedGroup만 설정한다. shared schedule fetch는 `LaunchedEffect(selectedGroup?.id, includeDummy)`에 맡긴다.

#### 기대 효과

- 공유 화면 첫 진입 시 `GET /share/groups/{id}/schedules` 중복 호출 제거

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/ui/SharingScreen.kt
```

#### 검증

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

수동 확인:

1. 공유 화면 진입
2. 공유 그룹 목록 정상 표시
3. selected group의 공유 일정 정상 표시
4. includeDummy toggle 시에만 shared schedule 재조회

---

### P0-4. local parser 우선 정책 적용

#### 문제

`OnMyPlateApp`에서 `preferLlm = true`로 설정되어 있어 local parser가 날짜와 시간을 충분히 잡아도 LLM proxy가 호출될 수 있다.

#### 변경

`preferLlm = false`로 변경한다. local parser가 `startAt`을 만들고 confidence 기준을 넘으면 LLM proxy를 생략한다.

추가로 local parser confidence가 날짜+시간만 잡힌 경우 `2 / 3 = 0.666...`이므로 기준값을 `0.67f`에서 `0.66f`로 낮춘다.

#### 기대 효과

- 명확한 한국어 일정 문장에 대한 `POST /api/parser/appointment` 호출 감소
- Gemini API 비용과 Edge Function invocation 동시 감소

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/OnMyPlateApp.kt
app/src/main/java/com/lss/onmyplate/nativeplanner/domain/parser/KoreanAppointmentParser.kt
```

#### 검증

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

수동 확인:

1. “내일 오후 3시 회의” 입력
2. local parser만으로 candidate 생성되는지 확인
3. “다음에 밥 한번 먹자”처럼 애매한 문장은 LLM fallback이 필요한지 확인
4. parsing 품질이 과하게 나빠지지 않는지 확인

---

## 3. P1: Repository 레벨 중복 요청 제거

P1의 목표는 같은 데이터를 여러 화면이 동시에 요구해도 실제 Edge Function 요청은 하나만 발생하도록 만드는 것이다. P0가 화면별 낭비 제거라면, P1은 repository 수준의 구조적 절감이다.

---

### P1-1. `refreshSchedules()` TTL cache

#### 문제

`refreshSchedules(rangeStart, rangeEnd)`가 짧은 시간 안에 반복 호출될 수 있다. 예를 들어 앱 시작, 주간 화면, 위젯 sync, 바구니 화면이 연속으로 schedule refresh를 요청할 수 있다.

#### 변경

`PlannerRepository` 내부에 schedule fetch timestamp를 저장한다.

정책:

- 마지막 성공 fetch가 30초 이내이면 API 호출 없이 `scheduleRecords.value` 반환
- 단, create/update/delete 이후에는 강제 refresh 또는 cache 직접 업데이트
- rangeStart/rangeEnd가 다르면 별도 cache key로 처리

권장 cache key:

```kotlin
data class ScheduleRangeKey(
    val rangeStart: Long?,
    val rangeEnd: Long?,
)
```

#### 기대 효과

- 짧은 시간 내 반복 schedule fetch 감소
- 화면 전환, recomposition, widget sync 중복 방지

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt
```

#### 검증

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

---

### P1-2. `refreshSchedules()` in-flight coalescing

#### 문제

TTL만으로는 동시에 시작된 요청을 막지 못한다. 예를 들어 두 collector가 같은 순간에 `refreshSchedules()`를 호출하면 둘 다 API 요청을 만들 수 있다.

#### 변경

같은 `ScheduleRangeKey`에 대해 이미 진행 중인 refresh가 있으면 새 요청을 만들지 않고 기존 요청을 await한다.

개념:

```kotlin
private val refreshSchedulesMutex = Mutex()
private val inFlightScheduleRefreshes = mutableMapOf<ScheduleRangeKey, Deferred<List<ScheduleRecord>>>()
```

#### 기대 효과

- 동시에 발생한 동일 schedule refresh를 1회 API 호출로 합침
- 앱 시작 시 중복 호출 감소 효과가 큼

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt
```

#### 검증 포인트

1. 앱 시작 직후 schedule fetch가 1회만 발생하는지 확인
2. 주간 화면과 widget sync가 겹쳐도 동일 range 요청이 합쳐지는지 확인
3. 다른 range, 예를 들어 이번 주와 다음 주는 별도 fetch되는지 확인

---

### P1-3. pending candidates TTL + in-flight coalescing

#### 문제

`observePendingCandidates()`는 바구니 화면 진입 시마다 pending candidates를 refresh한다. candidate 생성/저장 직후에도 다시 refresh한다.

#### 변경

pending candidates에도 10~30초 TTL을 적용하고, 동시 refresh를 합친다.

정책:

- 마지막 성공 fetch가 15초 이내이면 API 호출 생략
- candidate create/update/discard/save 이후에는 cache를 직접 업데이트하거나 force refresh
- 화면 진입에 따른 반복 fetch는 억제

#### 기대 효과

- 바구니 화면 재진입 시 `GET /api/planner/candidates?status=pending` 감소
- candidate edit/save 직후 중복 refresh 감소

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt
```

---

### P1-4. candidate 단건 조회 cache-first

#### 문제

`observeCandidate(id)`는 시작 시 `refreshCandidate(id)`를 호출한다. 그런데 candidate는 이미 pending list나 candidateRecords에 있는 경우가 많다.

#### 변경

`candidateRecords` 또는 `pendingCandidates`에 해당 candidate가 있으면 우선 그 값을 사용한다. 네트워크 refresh는 다음 조건에서만 실행한다.

- local cache에 없음
- candidate가 오래됨
- 사용자가 명시적으로 새로고침
- 저장 직전 서버 최신성이 반드시 필요함

#### 기대 효과

- candidate edit screen 진입 시 단건 candidate fetch 감소

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt
```

---

### P1-5. write 이후 refresh 최소화

#### 문제

현재 일정 저장/수정/삭제 후 여러 refresh가 이어질 수 있다.

예시:

- schedule create
- candidate status update
- pending candidates refresh
- schedules refresh
- widget snapshot refresh

이 중 일부는 서버 응답 객체로 local state를 바로 갱신할 수 있다.

#### 변경

쓰기 API 응답으로 받은 객체를 local state에 반영한다.

정책:

- `createSchedule()` 성공 응답의 schedule을 `scheduleRecords`에 merge
- `patchSchedule()` 성공 응답의 schedule을 `scheduleRecords`에 merge
- `deleteSchedule()` 성공 시 local state에서 제거
- `updateCandidateStatus()` 성공 시 local candidate status 직접 변경
- pending candidates 전체 refresh는 필요한 경우에만 수행

#### 기대 효과

- write 한 번당 뒤따르는 read 호출 감소
- 저장 UX도 빨라짐

#### 영향 파일

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt
```

---

## 4. P2: API 설계 및 장기 최적화

P2의 목표는 호출 횟수 자체를 줄이는 것뿐 아니라, 한 번의 호출에서 필요한 데이터를 묶어 가져오도록 API contract를 개선하는 것이다.

---

### P2-1. 앱 시작 bootstrap endpoint 추가

#### 문제

앱 시작 시 필요한 데이터가 여러 endpoint로 흩어져 있다.

예상 데이터:

- 현재 주간 schedules
- pending candidates count 또는 list
- user/profile 상태
- widget snapshot에 필요한 최소 일정 데이터

#### 변경

Edge Function에 bootstrap endpoint를 추가한다.

```text
GET /api/planner/bootstrap?rangeStart=...&rangeEnd=...
```

응답 예시:

```json
{
  "user": {
    "id": "user-id"
  },
  "schedules": [],
  "pendingCandidates": [],
  "profile": {
    "publicId": "pb-..."
  }
}
```

#### 기대 효과

- 앱 시작 시 여러 API 호출을 1회로 통합
- 초기 로딩 상태 관리 단순화

#### 영향 파일

```text
supabase/functions/planner-api/index.ts
supabase/functions/planner-api/*.ts
app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt
```

---

### P2-2. ETag 또는 version 기반 conditional fetch

#### 문제

일정이 바뀌지 않았는데도 전체 schedules를 계속 내려받는다.

#### 변경

서버가 user schedule version 또는 updated_at max timestamp를 내려준다. 클라이언트는 다음 요청에 `ifChangedSince` 또는 `knownVersion`을 보낸다.

예시:

```text
GET /api/planner/schedules?rangeStart=...&rangeEnd=...&knownVersion=123
```

변경 없음 응답:

```json
{
  "notModified": true,
  "version": 123
}
```

#### 기대 효과

- Edge Function invocation 수는 유지될 수 있지만 DB read와 payload 크기 감소
- 향후 CDN/cache 전략과 결합 가능

---

### P2-3. parser proxy 호출 정책 고도화

#### 문제

local parser와 LLM parser의 역할이 명확히 나뉘어 있지 않으면 LLM proxy 호출이 다시 늘어날 수 있다.

#### 변경

parser policy를 명시적인 decision function으로 분리한다.

예시:

```kotlin
private fun shouldCallLlm(rawText: String, local: AppointmentParseResult): Boolean {
    if (local.startAt == null) return true
    if (local.timeConfidence == TimeConfidence.Low) return true
    if (local.confidence < 0.66f) return true
    if (containsAmbiguousRelativeExpression(rawText)) return true
    return false
}
```

#### 기대 효과

- parsing 품질과 비용 사이의 trade-off를 코드에서 관리 가능
- 테스트 작성이 쉬워짐

---

### P2-4. local persistent cache 도입 검토

#### 문제

현재 runtime cache는 process death 후 사라진다. 앱 재시작 시 다시 API fetch가 필요하다.

#### 선택지

1. SharedPreferences snapshot 확장
2. lightweight JSON file cache
3. Room 재도입

현재 MVP에서는 Room 재도입보다 SharedPreferences 또는 JSON file cache가 더 단순하다.

추천 방향:

- schedules current week cache
- pending candidates cache
- generatedAt 저장
- stale 표시만 UI에 제공
- 쓰기는 여전히 API first

#### 기대 효과

- 앱 재시작 직후 빈 화면 방지
- 네트워크 실패 시 UX 안정성 증가
- 초기 Edge Function 호출을 사용자가 실제 refresh할 때까지 지연 가능

---

### P2-5. 서버 측 observability 추가

#### 문제

어떤 endpoint가 실제로 많이 호출되는지 클라이언트 추정만으로는 한계가 있다.

#### 변경

Edge Function에서 endpoint/method/userId 단위로 lightweight logging 또는 metrics를 남긴다.

기록 후보:

- method
- normalized path
- userId hash
- status code
- elapsedMs
- cold start 여부 추정값
- request source hint

주의:

- raw text, 일정 제목, 위치 등 개인정보성 payload는 기록하지 않는다.

#### 기대 효과

- 실제 호출량 상위 endpoint 식별
- P0/P1 적용 전후 호출량 비교 가능
- 비용 최적화 근거 확보

---

## 5. 적용 순서

추천 순서:

```text
P0-1 Basket lazy fetch
P0-2 Widget sync throttle
P0-3 Sharing duplicate fetch 제거
P0-4 Local parser 우선 정책
P1-1 Schedule TTL cache
P1-2 Schedule in-flight coalescing
P1-3 Pending candidates TTL/coalescing
P1-4 Candidate cache-first
P1-5 Write 이후 refresh 최소화
P2-1 Bootstrap endpoint
P2-2 Conditional fetch
P2-3 Parser policy 고도화
P2-4 Persistent cache
P2-5 Observability
```

---

## 6. 예상 효과

| 단계 | 주요 효과 | 위험도 |
|---|---|---|
| P0 | 명백한 중복/낭비 호출 즉시 감소 | 낮음 |
| P1 | 화면 전환, 앱 시작, 위젯 sync 중복 호출 구조적 감소 | 중간 |
| P2 | API contract 개선, 장기 비용 절감, 관측 가능성 확보 | 중간~높음 |

---

## 7. P0 적용 후 확인할 로그 포인트

### 바구니 탭

기대 상태:

```text
바구니 탭 진입만으로 GET /api/planner/schedules 호출 없음
저장 일정 섹션 펼칠 때만 GET /api/planner/schedules 호출
```

### 위젯

기대 상태:

```text
45초 이내 반복 widget sync에서 GET /api/planner/schedules 반복 없음
일정 저장/수정/삭제 후 snapshot 정상 갱신
```

### parser

기대 상태:

```text
명확한 문장: local parser로 candidate 생성
애매한 문장: LLM proxy fallback 가능
```

예시:

```text
내일 오후 3시 회의
다음 주 금요일 저녁 7시 강남에서 약속
5/28 14:00-16:00 세미나
```

위 문장은 local parser로 처리되는 것이 바람직하다.

### 공유 화면

기대 상태:

```text
공유 화면 첫 진입 시 shared schedules 중복 fetch 없음
includeDummy toggle 시에만 shared schedules 재조회
```

---

## 8. 권장 개발 방식

기존 작업 방식에 맞춰 각 변경은 작은 apply script로 나누는 것이 좋다.

권장 script 단위:

```text
apply_reduce_edge_calls_p0.py
apply_reduce_edge_calls_p1_schedules_cache.py
apply_reduce_edge_calls_p1_candidates_cache.py
apply_reduce_edge_calls_p1_write_refresh.py
apply_reduce_edge_calls_p2_bootstrap_api.py
```

각 script 원칙:

1. 기존 파일 수정 전 `.bak` 생성
2. 이미 적용된 변경은 `[OK] already ...`로 skip
3. 예상 snippet이 없으면 `[FAIL]`로 안전 중단
4. 한 번에 너무 많은 파일을 바꾸지 않기
5. Android compile 검증 명령 포함

---

## 9. 최종 판단

현재 가장 먼저 줄여야 할 호출은 로그인 관련 호출이 아니라 다음 순서다.

1. `GET /api/planner/schedules`
2. `POST /api/parser/appointment`
3. `GET /api/planner/candidates?status=pending`
4. 공유 화면의 profile/groups/shared schedules 호출
5. write 이후 follow-up refresh 호출

특히 `GET /api/planner/schedules`는 여러 화면과 위젯이 동시에 사용하므로, P0에서 화면별 낭비를 제거하고 P1에서 repository 레벨 coalescing을 넣는 것이 가장 효과가 크다.
