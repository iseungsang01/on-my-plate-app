# On My Plate 현재 코드 문제 정리

작성일: 2026-05-07 KST  
기준 커밋: `93531f8 widget 옮김`  
상태: `:app:assembleDebug` 빌드 성공, 테스트 없음

## 사용 방법

컨텍스트를 초기화하면서 하나씩 고칠 때는 아래 순서대로 진행한다.

1. 이 문서에서 하나의 이슈만 선택한다.
2. 해당 이슈의 `목표`, `관련 파일`, `검증`만 새 컨텍스트에 붙여넣는다.
3. 수정 후 체크박스를 갱신한다.
4. 빌드/테스트 결과를 `처리 기록`에 남긴다.

## Codex 자동 실행 규칙

Codex가 이 문서를 기준으로 작업할 때는 아래 규칙을 따른다.

1. 한 번의 실행에서는 하나의 이슈 ID만 처리한다.
2. 지정되지 않은 이슈는 수정하지 않는다.
3. `전체 우선순위`의 체크박스가 `[x]`인 이슈는 건드리지 않는다.
4. 먼저 관련 파일을 읽고 현재 구현을 파악한 뒤 수정한다.
5. 관련 파일 외 변경은 빌드/테스트를 위해 필요한 경우에만 최소화한다.
6. 수정 후 가능한 가장 좁은 검증 명령을 먼저 실행한다.
7. 최종적으로 `:app:assembleDebug`를 실행한다.
8. 결과를 해당 이슈의 `처리 기록`에 남긴다.
9. 실패한 검증이 있으면 실패 원인과 남은 작업을 `처리 기록`에 남긴다.
10. 기존 경고/실패와 이번 변경으로 생긴 경고/실패를 구분한다.

---

## 전체 우선순위

- [x] P0-1. 한글 문자열/정규식 인코딩 깨짐 복구
- [x] P1-1. 한국어 파서 테스트 추가
- [ ] P1-2. 공유 출처 `sourceApp` 저장 누락 수정
- [ ] P1-3. 위젯 클릭 라우팅 정리
- [ ] P2-1. release/play 환경변수 요구 시점 완화
- [ ] P2-2. Gradle Wrapper 추가
- [ ] P2-3. 위젯 snapshot 구조 정리
- [ ] P3-1. 전반 테스트 보강
---

## P0. 한글 문자열/정규식 인코딩 깨짐 복구

### 증상

여러 파일에서 한글이 mojibake 형태로 깨져 있다.

예시:

- `?쎌냽`
- `?뺤젙`
- `誘몄젙`
- `?쎌냽 ?꾨낫瑜?李얠븯?듬땲??`
- 파서의 요일/시간/장소 키워드 문자열 대부분

### 영향

- 사용자에게 보이는 UI/알림/Toast 문구가 깨져 보인다.
- 한국어 룰 기반 파서가 정상 동작하지 않을 가능성이 높다.
- README와 widget JS 설명/라벨도 일부 깨져 있다.

### 관련 파일

- `README.md`
- `widget/README.md`
- `widget/src/plannerWidgetBridge.js`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/domain/parser/KoreanAppointmentParser.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/domain/parser/GeminiAppointmentParser.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/notification/AppointmentNotificationManager.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/share/ShareReceiverActivity.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/ui/PlannerScreen.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt`

### 목표

- 모든 사용자 표시 문자열을 자연스러운 한국어로 복구한다.
- `KoreanAppointmentParser`의 날짜/시간/장소/제목 추출 정규식을 정상 한국어 기준으로 복구한다.
- 소스 파일 인코딩을 UTF-8로 유지한다.

### 검증

- `:app:assembleDebug` 성공
- 대표 입력 파싱 확인:
  - `오늘 저녁 7시 강남에서 회의`
  - `내일 오후 2시 카페에서 만나`
  - `다음 주 월요일 오전 10시 병원`
  - `5월 20일 18:30 홍대 약속`
- 알림/Toast/Planner 화면 문자열 육안 확인

### 처리 기록

- 2026-05-07: 대상 파일의 한글 사용자 표시 문자열 및 `KoreanAppointmentParser` 날짜/시간/장소/제목 정규식을 UTF-8 한국어 문자열 기준으로 복구. `HH:mm` 형식(`18:30`)과 장소 보조 추출(`홍대 약속`, `병원`)을 P0 대표 입력에 맞게 보강.
- 2026-05-07: `:app:assembleDebug` 검증 성공. 경고: Gradle 9.0 호환성 deprecation 경고는 기존 P2 범위로 유지.

---

## P1. 한국어 파서 테스트 추가

### 증상

현재 `app/src/test`, `app/src/androidTest` 테스트 파일이 없다.

### 영향

- 한글 파서 수정 후 회귀 검증이 어렵다.
- 날짜/시간 상대 표현의 안정성을 보장하기 어렵다.

### 관련 파일

- `app/src/main/java/com/lss/onmyplate/nativeplanner/domain/parser/KoreanAppointmentParser.kt`
- 신규: `app/src/test/java/.../KoreanAppointmentParserTest.kt`
- 필요 시 `app/build.gradle.kts`

### 목표

- JVM unit test로 파서 핵심 케이스를 검증한다.
- 고정된 `receivedAt` 기준으로 상대 날짜를 검증한다.

### 검증

- `:app:testDebugUnitTest` 또는 가능한 unit test task 성공
- 최소 케이스:
  - 오늘/내일/모레
  - 이번 주/다음 주 + 요일
  - 오전/오후/저녁/점심/아침
  - `HH:mm`, `N시 반`
  - 장소 추출
  - 제목 fallback

### 처리 기록

- 2026-05-07: `KoreanAppointmentParserTest` JVM unit test 추가. 고정 `receivedAt` 기준 오늘/내일/모레, 이번 주/다음 주+요일, 오전/오후/아침/점심/저녁/밤, `HH:mm`, `N시 반`, 장소 추출, 제목 fallback 검증.
- 2026-05-07: 명시적 `이번 주/다음 주` 요일 계산을 주 시작일 기준으로 보정하고, `에서/로` 장소 추출 전 날짜/시간 표현을 제거해 장소 앞에 날짜/시간이 붙지 않도록 수정.
- 2026-05-07: `:app:testDebugUnitTest` 검증 성공. 기존 범위 경고: `PlannerRepository.kt`의 `sourceApp` 미사용 경고, Room kapt 옵션 인식 경고, Gradle 9.0 호환성 deprecation 경고.

---

## P1. 공유 출처 `sourceApp` 저장 누락 수정

### 증상

`PlannerRepository.createCandidate(rawText, sourceApp, receivedAt)`는 `sourceApp`을 받지만 사용하지 않는다.  
일정 저장 시에도 `sourceApp = null`로 고정되어 있다.

### 영향

- 어떤 앱에서 공유됐는지 추적할 수 없다.
- 빌드 경고 발생:
  - `Parameter 'sourceApp' is never used`

### 관련 파일

- `app/src/main/java/com/lss/onmyplate/nativeplanner/data/entity/AppointmentCandidateEntity.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/data/entity/ScheduleEntity.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/data/db/AppDatabase.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/share/ShareReceiverActivity.kt`

### 목표

- 후보 또는 일정에 `sourceApp`을 제대로 저장한다.
- 이미 Room schema version 1이 배포 전인지 확인한다.
  - 배포 전이면 schema 변경을 직접 반영한다.
  - 배포 후라면 migration을 추가한다.

### 검증

- `:app:assembleDebug` 성공
- `sourceApp` 미사용 경고 제거
- 공유로 생성한 후보/일정에 source app 값이 전달되는지 확인

### 처리 기록

- 미처리

---

## P1. 위젯 클릭 라우팅 정리

### 증상

`SummaryWidgetProvider`는 위젯 클릭 시 pending route `/summary`를 저장하지만, native `MainActivity`는 이 값을 소비하지 않는다.

현재 route:

- `Planner`
- `Candidate`
- `Conflict`

### 영향

- 위젯 클릭 시 앱은 열리지만 summary 화면으로 이동하지 않는다.
- `PlannerWidgetStore.savePendingRoute()`가 native 앱에서는 사실상 미사용이다.

### 관련 파일

- `app/src/main/java/com/lss/onmyplate/nativeplanner/widget/PlannerWidgetStore.java`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/widget/SummaryWidgetProvider.java`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/ui/MainActivity.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/ui/PlannerScreen.kt`

### 목표 선택지

둘 중 하나로 결정한다.

#### 선택 A: summary route 구현

- `Route.Summary` 추가
- 요약 화면 추가
- pending route 소비 API 추가
- 위젯 클릭 시 summary 화면으로 이동

#### 선택 B: 현재 MVP에 맞게 제거/단순화

- pending route 저장 제거
- 위젯 클릭은 `Planner` 화면만 열도록 명확화
- 불필요한 `KEY_PENDING_ROUTE` 제거

### 검증

- `:app:assembleDebug` 성공
- 위젯 클릭 시 의도한 화면으로 이동
- 불필요한 dead code 없음

### 처리 기록

- 미처리

---

## P2. release/play 환경변수 요구 시점 완화

### 증상

`app/build.gradle.kts`에서 release signing/play 설정이 `.env` 필수값에 의존한다.

필수값:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `PLAY_SERVICE_ACCOUNT_JSON_PATH`

### 영향

- 새 환경에서 debug 빌드만 하려 해도 Gradle configuration 단계에서 실패할 수 있다.
- 개발/CI 진입장벽이 높다.

### 관련 파일

- `app/build.gradle.kts`
- `.env.example`
- `README.md`

### 목표

- debug 빌드는 signing/play env 없이도 가능하게 한다.
- release/publish task 실행 시에만 필수값을 요구한다.
- README에 debug 빌드와 publish 빌드 요구사항을 분리해서 적는다.

### 검증

- 빈 release env 상태에서 `:app:assembleDebug` 성공
- release/publish task에서만 명확한 오류 메시지 발생

### 처리 기록

- 미처리

---

## P2. Gradle Wrapper 추가

### 증상

루트에 `gradlew`, `gradlew.bat`, `gradle/wrapper/*`가 없다.

### 영향

- 로컬 Gradle 설치 또는 사용자 PC cache 경로에 의존한다.
- 재현 가능한 빌드가 어렵다.

### 관련 파일

- 신규: `gradlew`
- 신규: `gradlew.bat`
- 신규: `gradle/wrapper/gradle-wrapper.properties`
- 신규: `gradle/wrapper/gradle-wrapper.jar`

### 목표

- Gradle Wrapper를 추가한다.
- README의 빌드 명령을 wrapper 기준으로 갱신한다.

### 검증

- Windows: `./gradlew.bat :app:assembleDebug` 성공
- 가능하면 Unix: `./gradlew :app:assembleDebug` 사용 가능 구조 확인

### 처리 기록

- 미처리

---

## P2. 위젯 snapshot 구조 정리

### 증상

native `PlannerWidgetSync`는 Room `schedules`만 snapshot에 넣고 `autoPlans`는 빈 배열로 저장한다.  
반면 `widget/src/plannerWidgetBridge.js`는 manual/auto/category 기반의 더 넓은 데이터 구조를 사용한다.

### 영향

- native 앱 위젯과 추출된 widget bundle의 데이터 모델이 완전히 일치하지 않는다.
- 자동 계획/카테고리 일정은 표시되지 않는다.

### 관련 파일

- `app/src/main/java/com/lss/onmyplate/nativeplanner/widget/PlannerWidgetSync.kt`
- `app/src/main/java/com/lss/onmyplate/nativeplanner/widget/SummaryWidgetProvider.java`
- `widget/src/plannerWidgetBridge.js`
- `widget/android/app/src/main/java/com/lss/onmyplatemobile/*`

### 목표

- native MVP에서 필요한 snapshot 범위를 결정한다.
- 사용하지 않을 필드는 제거하거나 주석/README로 의도를 명확히 한다.
- 재사용용 widget bundle과 native 앱 위젯의 차이를 문서화한다.

### 검증

- `:app:assembleDebug` 성공
- 위젯에 현재 주간 Room 일정이 정상 표시
- README에 native/widget bundle 차이 설명

### 처리 기록

- 미처리

---

## P3. 전반 테스트 보강

### 증상

현재 자동화 테스트가 없다.

### 영향

- 후보 저장, 충돌 감지, 알림 액션, 위젯 snapshot 회귀 위험이 높다.

### 관련 파일

- `app/src/test/java/...`
- `app/src/androidTest/java/...`
- 필요 시 `app/build.gradle.kts`

### 목표

- 최소 unit test 추가:
  - `ConflictDetector`
  - `PlannerRepository` 후보 저장 흐름
  - `PlannerWidgetSync` snapshot 생성 로직
- Android instrumented test는 필요할 때 분리한다.

### 검증

- unit test task 성공
- 빌드 성공

### 처리 기록

- 미처리

---

## 현재 검증 기록

### 2026-05-07

명령:

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat" --no-daemon :app:assembleDebug
```

결과:

```text
BUILD SUCCESSFUL
```

경고:

```text
PlannerRepository.kt:26:50 Parameter 'sourceApp' is never used
Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
```

### 2026-05-07 P0 처리

명령:

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat" --no-daemon :app:assembleDebug
```

결과:

```text
BUILD SUCCESSFUL in 1m 10s
40 actionable tasks: 6 executed, 34 up-to-date
```

경고:

```text
Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
```

