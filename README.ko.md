# On My Plate Native Planner

공유된 텍스트에서 일정 후보를 만들고, 사용자가 제목을 입력해 확정하는 네이티브 Android Kotlin MVP입니다.

## 제품 로직

앱은 공유 텍스트를 일정 후보로 변환하지만, 일정 제목은 자동으로 결정하지 않습니다.

1. 사용자가 카카오톡, SMS, 메모 앱, 브라우저 등 Android 공유 기능을 지원하는 앱에서 `text/plain` 내용을 이 앱으로 공유합니다.
2. `ShareReceiverActivity`가 텍스트를 받고, 필요한 경우 알림 권한을 요청합니다.
3. `PlannerRepository.createCandidate`가 공유 텍스트를 파싱해 일정 메타데이터를 만듭니다.
   - 시작 시간
   - 선택적 종료 시간
   - 선택적 장소
   - 전체 신뢰도와 시간 신뢰도
4. 파서는 의도적으로 일정 제목을 비워 둡니다. 제목은 반드시 사용자가 직접 입력해야 합니다.
5. `AppointmentNotificationManager.showCandidate`가 파싱된 시간/장소를 보여 주고 제목 입력을 요청하는 네이티브 알림을 표시합니다.
6. 사용자는 알림에서 다음 작업을 할 수 있습니다.
   - 제목을 입력하고 확정 일정으로 저장
   - 제목을 입력하고 미정 일정으로 저장
   - 앱을 열어 제목, 시작 시간, 종료 시간, 장소를 수정한 뒤 저장
7. `PlannerRepository.saveFromCandidate`는 제목이 비어 있으면 일정을 만들지 않습니다.
8. 후보 일정이 기존 일정과 겹치면, 사용자가 강제로 추가하기 전까지 충돌 알림/화면을 먼저 보여 줍니다.
9. 저장된 일정은 Room에 로컬 저장되며 네이티브 홈 화면 위젯 스냅샷으로 동기화됩니다.

파일 단위 구현 지도는 `SPECIFICATION.ko.md`를 참고하세요. 영문 원문은 `README.md`와 `SPECIFICATION.md`에 있습니다.

## 실행

Android Studio에서 이 폴더를 열고 Gradle Sync가 끝나면 기기나 에뮬레이터에서 `app` 구성을 실행합니다.

디버그 빌드는 Android 릴리스 서명이나 Google Play 게시 변수가 필요하지 않습니다. 로컬 디버그 빌드에는 `ANDROID_APPLICATION_ID`, 버전 필드, 필요 시 Gemini 설정 같은 앱 실행 값만 제공하면 됩니다.

Gradle Wrapper로 디버그 APK를 빌드합니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

Unix 계열 셸에서는 다음을 사용합니다.

```sh
./gradlew :app:assembleDebug
```

`gradlew`와 `gradlew.bat`는 Gradle Wrapper의 표준 루트 실행 스크립트입니다. 실제 wrapper 바이너리와 설정은 `gradle/wrapper/` 폴더에 정리되어 있으며, 스크립트를 다른 폴더로 옮기면 Android Studio와 배포 명령이 깨질 수 있습니다.

앱은 Android `ACTION_SEND` 대상에 `text/plain`으로 등록됩니다. 카카오톡, SMS, 메모 앱, 브라우저 등에서 텍스트를 `On My Plate Planner`로 공유하면 공유 수신기가 시간/장소 정보를 파싱하고, 제목이 비어 있는 일정 후보를 만든 뒤, 인라인 제목 입력과 액션이 있는 네이티브 알림을 표시합니다.

## MVP 범위

- 네이티브 Android Kotlin, Jetpack Compose, Room, coroutines.
- 한국어 규칙 기반 파서와 선택적 Gemini LLM 파싱.
- 일정 제목은 공유 텍스트에서 파싱하지 않고 사용자가 직접 입력합니다.
- `RemoteInput` 기반 알림 액션: `확정 저장`, `미정 저장`, `앱에서 수정`.
- Room 일정 기반 로컬 충돌 감지.
- 후보 편집 화면과 충돌 해결 화면.

카카오톡 스크래핑, 로그인, 백그라운드 채팅 감시, 웹 앱, 클라우드 동기화는 포함하지 않습니다.

## 네이티브 위젯 스냅샷 범위

네이티브 Android 위젯은 이 MVP에 포함되며, 네이티브 플래너가 저장한 Room 기반 일정만 렌더링합니다.

- `PlannerWidgetSync`는 `native-room-schedules-v1` 요약 스냅샷을 씁니다.
- 스냅샷에는 `Asia/Seoul` 로컬 날짜별로 묶인 `manualEventsByDate`가 포함됩니다.
- `autoPlans`, 카테고리 열, 생성된 `days`는 의도적으로 쓰지 않습니다. 네이티브 위젯은 `manualEventsByDate`에서 표시할 주간 범위를 계산합니다.
- 재사용 가능한 `widget/` 번들은 수동 일간 일정과 자동/카테고리 계획을 이미 가진 호스트 앱을 위한 더 넓은 스냅샷 모델을 제공합니다. 계약은 `widget/README.md`를 참고하세요.

## AAB 자동 업데이트 흐름

- 앱은 실행 시와 포그라운드 복귀 시 Google Play In-App Updates를 확인합니다.
- Play가 더 새 AAB 버전을 보고하고 즉시 업데이트를 허용하면 Play 업데이트 UI가 자동으로 열립니다.
- 즉시 업데이트가 중단된 경우 `onResume()`에서 다시 이어 갑니다.
- 새 AAB를 게시하기 전에는 `.env` 또는 CI 환경 변수의 `ANDROID_VERSION_CODE`를 올리고, 필요 시 `ANDROID_VERSION_NAME`도 갱신합니다.
- 서명된 릴리스 빌드에는 `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`가 필요합니다.
- Play 게시에는 `PLAY_SERVICE_ACCOUNT_JSON_PATH`도 필요합니다. `PLAY_TRACK` 기본값은 `internal`, `PLAY_RELEASE_STATUS` 기본값은 `DRAFT`입니다.
- 업로드는 `publishAab`로 수행합니다. 사용자가 볼 수 있는 업데이트로 배포하려면 Play 트랙에 초안이 아닌 릴리스 상태로 게시해야 합니다.

## Supabase Edge Function planner API

The Android app uses a single Supabase Edge Function, `planner-api`, for app login, schedule sync, and sharing. Configure `PLANNER_API_BASE_URL` in `.env` or CI as `https://<project-ref>.supabase.co/functions/v1/planner-api`. Supabase Auth is not used; `planner-api` uses the app's own `planner_users` rows, returns the user id as the app session token, and then writes to Supabase with server-only service-role credentials.

Android never stores service-role credentials and no longer depends on any PC-local backend. Shared-screen-only dummy schedules are read from `planner_dummy_schedules` through the API and are never stored in Room or the widget. See `supabaseSQL.md` for the server-side schema and RLS posture.
