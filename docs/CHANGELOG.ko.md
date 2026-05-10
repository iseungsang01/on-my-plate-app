# 변경 내역

## Supabase 공유 기능 재설계

이번 변경은 기존 Android 앱이 Supabase Auth anonymous 로그인과 Supabase PostgREST 직접 호출로 공유 테이블을 조작하던 초안을 폐기하고, 외부 공유 API가 인증과 권한 검사를 담당하는 서버 중계 방식으로 전환한 내용입니다.

### 핵심 변경

- Android 앱은 더 이상 anonymous Supabase Auth 세션을 만들지 않습니다.
- Android 앱은 더 이상 Supabase access token, refresh token을 저장하지 않습니다.
- Android 앱은 더 이상 Supabase PostgREST 공유 테이블에 직접 write하지 않습니다.
- Android 앱은 기존 앱 로그인 세션 토큰을 SharedPreferences에서 읽어 공유 API에 `Authorization: Bearer <token>`으로 전달합니다.
- 공유 API base URL은 `PLANNER_API_BASE_URL`로 설정합니다.
- 기본 세션 토큰 저장 위치는 다음과 같습니다.
  - SharedPreferences 이름: `planner_auth`
  - token key: `session_token`

### Android 변경

- `SharingRepository.kt`
  - Supabase Auth/PostgREST client를 제거하고 외부 공유 API client로 교체했습니다.
  - 다음 API contract를 호출합니다.
    - `POST /api/planner/share/profile`
    - `POST /api/planner/share/groups`
    - `GET /api/planner/share/groups`
    - `POST /api/planner/share/groups/{groupId}/schedules`
    - `GET /api/planner/share/groups/{groupId}/schedules?includeDummy=true`
  - 공유 API 설정 또는 로그인 토큰이 없으면 명확한 오류를 반환합니다.

- `SharingScreen.kt`
  - Supabase 직접 설정 안내 대신 공유 API 설정 안내를 표시하도록 문구를 바꿨습니다.
  - 기존 UI 흐름은 유지합니다.

- `app/build.gradle.kts`, `.env.example`
  - Android 공유 API 설정값을 추가했습니다.
  - `PLANNER_API_BASE_URL`
  - `PLANNER_SESSION_PREFS_NAME`
  - `PLANNER_SESSION_TOKEN_KEY`

### Supabase SQL 변경

- `supabaseSQL.md`를 서버 중계 방식 기준으로 다시 작성했습니다.
- `planner_profiles.auth_user_id uuid` 대신 `planner_profiles.user_id text`를 사용합니다.
- 모든 공유 테이블은 RLS를 활성화합니다.
- anon/authenticated client 정책은 만들지 않습니다.
- Android 앱에는 `SUPABASE_SERVICE_ROLE_KEY`를 넣지 않고, 서버에서만 사용해야 합니다.

### 문서 변경

- `README.md`, `README.ko.md`
  - 공유 기능 설명을 외부 공유 API 방식으로 수정했습니다.

- `SPECIFICATION.md`, `SPECIFICATION.ko.md`
  - 공유 아키텍처를 서버 중계 방식으로 갱신했습니다.

- `spec_func.md`
  - `SharingRepository`와 `SharingScreen` 동작 명세를 새 방식에 맞게 업데이트했습니다.

### 검증 결과

다음 명령으로 검증했습니다.

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

두 명령 모두 성공했습니다.

### 남은 전제

- 실제 공유 API 서버는 이 Android 리포지토리 밖에 있다고 가정합니다.
- 서버는 기존 로그인 세션 토큰을 검증하고 앱 사용자 ID를 확인해야 합니다.
- 서버는 그룹 멤버십과 일정 권한을 검사한 뒤 Supabase service role 또는 private DB connection으로 DB 작업을 수행해야 합니다.
