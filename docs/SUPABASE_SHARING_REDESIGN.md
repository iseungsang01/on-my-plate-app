# Supabase 공유 기능 재설계 문서: 기존 DB 로그인 + 검증 API 방식

## 결론

현재 구현된 공유 기능 초안은 Supabase Auth의 `auth.uid()`와 anonymous sign-in을 기준으로 설계되어 있다. 하지만 앱의 로그인 체계가 Supabase Auth가 아니라 **Supabase DB에 저장된 자체 사용자/세션 정보**를 사용하는 구조라면, 공유 기능도 그 로그인 체계를 따라야 한다.

따라서 다음 컨텍스트에서는 현재 `auth.uid()` 기반 설계를 폐기하고, **기존 로그인 검증 API가 인증/권한을 확인한 뒤 Supabase DB를 조작하는 서버 중계 방식**으로 다시 구현한다.

---

## 왜 `auth.uid()`를 쓰면 안 되는가

Supabase RLS의 `auth.uid()`는 Supabase Auth로 발급된 JWT 안의 사용자 ID를 반환한다.

하지만 현재 앱 로그인은 Supabase Auth 계정이 아니라 자체 DB 로그인이라면:

- 앱 사용자 ID와 `auth.uid()`가 연결되지 않는다.
- anonymous sign-in으로 생성된 Supabase Auth user는 실제 앱 로그인 사용자와 별개다.
- 공유 그룹 권한이 실제 로그인 사용자 기준이 아니라 anonymous auth user 기준으로 잡힌다.
- 나중에 실제 계정/기기 변경/로그아웃/세션 만료 처리가 꼬인다.

즉, 현재 초안의 핵심 문제는 다음과 같다.

```text
실제 앱 사용자 != Supabase Auth anonymous user
```

---

## 새 설계 방향

### 인증 책임

앱은 공유 기능 호출 시 기존 로그인 세션/token을 검증 API에 전달한다.

```text
Android app
  -> 기존 로그인 세션/token 전달
  -> Backend/API 검증
  -> Supabase DB 작업
```

API 서버는 다음을 수행한다.

1. 앱 로그인 세션/token 검증
2. 현재 로그인 사용자 ID 확인
3. 요청한 공유 작업이 해당 사용자에게 허용되는지 확인
4. Supabase service role 또는 서버 전용 DB connection으로 DB 작업
5. 결과만 앱에 반환

중요:

- `SUPABASE_SERVICE_ROLE_KEY`는 앱에 절대 넣지 않는다.
- 앱은 Supabase 공유 테이블에 직접 write하지 않는다.
- 공유 권한 검사는 API 서버에서 기존 로그인 사용자 ID 기준으로 처리한다.

---

## 사용자 ID 기준

기존 로그인 사용자 테이블의 PK를 기준으로 한다.

예시:

```text
users.id
app_users.id
planner_users.id
```

아직 정확한 테이블명이 확정되지 않았으므로 다음 컨텍스트에서 먼저 확인해야 한다.

필수 확인 사항:

```text
1. 기존 로그인 사용자 테이블명
2. 사용자 PK 컬럼명
3. 앱이 보관하는 로그인 세션/token 형식
4. 로그인 검증 API의 현재 endpoint 또는 새로 만들 위치
```

공유 테이블은 이 사용자 PK를 FK처럼 사용한다.

```text
planner_profiles.user_id
planner_group_members.user_id
planner_schedules.created_by
planner_dummy_schedules.created_by
```

---

## 권장 API 설계

### 1. 내 공유 프로필 조회/생성

```http
POST /api/planner/share/profile
Authorization: Bearer <existing-app-session-token>
```

동작:

- 로그인 검증
- 현재 사용자 ID 조회
- `planner_profiles`에 row가 없으면 `public_id` 생성
- 내 `public_id` 반환

응답 예:

```json
{
  "userId": "...",
  "publicId": "omp-A1B2C3D4"
}
```

### 2. 상대 public_id로 공유 그룹 생성

```http
POST /api/planner/share/groups
Authorization: Bearer <existing-app-session-token>
Content-Type: application/json

{
  "partnerPublicId": "omp-Z9Y8X7W6"
}
```

동작:

- 로그인 검증
- 내 user_id 확인
- 상대 public_id로 상대 user_id 조회
- 자기 자신인지 검사
- 기존 그룹 재사용 또는 새 그룹 생성
- `planner_group_members`에 두 사용자 추가

응답 예:

```json
{
  "groupId": "...",
  "name": "공유 그룹"
}
```

### 3. 내 공유 그룹 목록

```http
GET /api/planner/share/groups
Authorization: Bearer <existing-app-session-token>
```

동작:

- 로그인 검증
- 내가 멤버인 그룹만 반환

### 4. 로컬 일정 공유 업로드

```http
POST /api/planner/share/groups/{groupId}/schedules
Authorization: Bearer <existing-app-session-token>
Content-Type: application/json

{
  "localScheduleId": "...",
  "title": "회의",
  "startAt": "2026-05-10T09:00:00Z",
  "endAt": "2026-05-10T10:00:00Z",
  "location": "온라인",
  "memo": null,
  "status": "confirmed",
  "sourceText": "...",
  "sourceApp": "internal",
  "recurrence": {
    "frequency": "weekly",
    "intervalWeeks": 1,
    "dayOfWeek": 2,
    "untilAt": "2026-08-31T14:59:59Z",
    "count": null
  },
  "recurrenceExceptions": [
    {
      "occurrenceStartAt": "2026-05-12T01:00:00Z",
      "action": "skip"
    }
  ]
}
```

동작:

- 로그인 검증
- 내가 해당 groupId의 멤버인지 확인
- `planner_schedules`에 upsert
- `recurrence`가 있으면 `planner_schedule_recurrence_rules`에 upsert
- `recurrenceExceptions`는 해당 일정 기준으로 replace
- `recurrence`가 없거나 null이면 해당 일정의 반복 규칙/예외 삭제

### 5. 공유 일정 조회

```http
GET /api/planner/share/groups/{groupId}/schedules?includeDummy=true
Authorization: Bearer <existing-app-session-token>
```

동작:

- 로그인 검증
- 내가 groupId 멤버인지 확인
- `planner_schedules` 조회
- 공유 일정에는 `recurrence`, `recurrenceExceptions` 포함
- `includeDummy=true`이면 `planner_dummy_schedules`도 함께 조회
- 더미 일정에는 `isDummy: true`, `recurrence: null`, `recurrenceExceptions: []` 표시

---

## 새 SQL 방향

`auth.uid()`와 Supabase Auth 전용 정책은 제거한다.

### 테이블 초안

```sql
create table if not exists public.planner_profiles (
  user_id text primary key,
  public_id text not null unique,
  display_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint planner_profiles_public_id_format
    check (public_id ~ '^omp-[A-Z0-9]{6,16}$')
);

create table if not exists public.planner_groups (
  id uuid primary key default gen_random_uuid(),
  name text not null default '공유 그룹',
  created_by text not null,
  created_at timestamptz not null default now()
);

create table if not exists public.planner_group_members (
  group_id uuid not null references public.planner_groups(id) on delete cascade,
  user_id text not null,
  role text not null default 'member' check (role in ('owner', 'member')),
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);

create table if not exists public.planner_schedules (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.planner_groups(id) on delete cascade,
  local_schedule_id text,
  created_by text not null,
  title text not null,
  start_at timestamptz not null,
  end_at timestamptz,
  location text,
  memo text,
  status text not null default 'planned' check (status in ('confirmed', 'planned', 'uncertain')),
  source_text text,
  source_app text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (group_id, created_by, local_schedule_id)
);

create table if not exists public.planner_schedule_recurrence_rules (
  schedule_id uuid primary key references public.planner_schedules(id) on delete cascade,
  frequency text not null check (frequency in ('weekly')),
  interval_weeks integer not null default 1 check (interval_weeks >= 1),
  day_of_week integer not null check (day_of_week between 1 and 7),
  until_at timestamptz,
  count integer check (count is null or count > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.planner_schedule_recurrence_exceptions (
  schedule_id uuid not null references public.planner_schedules(id) on delete cascade,
  occurrence_start_at timestamptz not null,
  action text not null check (action in ('skip')),
  created_at timestamptz not null default now(),
  primary key (schedule_id, occurrence_start_at)
);

create table if not exists public.planner_dummy_schedules (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.planner_groups(id) on delete cascade,
  created_by text not null,
  title text not null,
  start_at timestamptz not null,
  end_at timestamptz,
  location text,
  memo text,
  status text not null default 'planned' check (status in ('confirmed', 'planned', 'uncertain')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
```

### RLS 선택지

#### 선택지 A: API 서버만 DB 접근

권장 방식이다.

- 앱은 공유 테이블에 직접 접근하지 않는다.
- API 서버는 service role 또는 서버 DB connection으로 접근한다.
- public 클라이언트 접근을 막기 위해 RLS는 enable하고 client role 정책은 만들지 않는다.

```sql
alter table public.planner_profiles enable row level security;
alter table public.planner_groups enable row level security;
alter table public.planner_group_members enable row level security;
alter table public.planner_schedules enable row level security;
alter table public.planner_dummy_schedules enable row level security;
```

정책을 만들지 않으면 anon/authenticated direct access는 막힌다. 서버 service role은 RLS를 우회할 수 있으므로 서버 코드에서 권한 검사를 반드시 수행한다.

#### 선택지 B: Custom JWT로 RLS 유지

나중에 고도화할 수 있는 방식이다.

- 기존 로그인 API가 Supabase JWT secret으로 custom JWT 발급
- JWT claim에 앱 사용자 ID를 넣음
- RLS에서 `auth.jwt() ->> 'app_user_id'` 같은 claim을 기준으로 검사

단, 구현 복잡도가 올라간다. 지금은 선택지 A가 더 단순하고 안전하다.

---

## Android 변경 방향

현재 추가된 `SharingRepository`의 다음 부분은 제거/교체 대상이다.

제거:

- `signInAnonymously()`
- Supabase Auth `/auth/v1/signup` 호출
- Supabase PostgREST direct write
- access token/refresh token 저장
- `auth_user_id` 기반 profile upsert

교체:

```text
SharingRepository
  -> 기존 로그인 token/session을 가져옴
  -> 자체 공유 API 호출
  -> API 응답을 UI에 표시
```

앱이 계속 해야 하는 것:

- 로컬 Room 일정은 그대로 유지
- 사용자가 선택한 일정만 공유 API로 전송
- 공유 화면에서 API가 반환한 실제 일정 + 더미 일정 표시
- 더미 일정은 Room/위젯에 저장하지 않음

---

## 다음 컨텍스트 시작 작업 순서

1. 기존 로그인 구조 확인
   - 사용자 테이블명
   - 사용자 PK 컬럼
   - 세션/token 저장 위치
   - 로그인 검증 API가 이미 있는지 확인

2. 현재 초안 중 `auth.uid()` 기반 코드 제거
   - `SharingRepository.kt`의 anonymous Supabase Auth 제거
   - Supabase direct REST client 제거 또는 API client로 변경
   - 문서의 anonymous Auth 설명 제거

3. 새 API contract 구현
   - profile
   - groups create/list
   - schedule upload
   - schedule list with dummy

4. SQL 재작성
   - `auth_user_id uuid` -> 기존 `user_id` 타입
   - `auth.uid()` 정책 제거
   - RLS enabled but no direct client policy 또는 custom JWT 방식 중 선택

5. Android 빌드/테스트
   - `./gradlew :app:assembleDebug`
   - `./gradlew :app:testDebugUnitTest`

---

## 현재 파일 상태에서 주의할 점

이미 추가된 `supabaseSQL.md`와 `SharingRepository.kt`는 현재 Supabase Auth 전제이므로 그대로 적용하면 안 된다.

다음 컨텍스트에서는 이 문서를 기준으로 다음 파일들을 수정해야 한다.

```text
supabaseSQL.md
README.md
README.ko.md
SPECIFICATION.md
SPECIFICATION.ko.md
spec_func.md
app/src/main/java/com/lss/onmyplate/nativeplanner/data/supabase/SharingRepository.kt
app/src/main/java/com/lss/onmyplate/nativeplanner/ui/SharingScreen.kt
```

---

## 한 줄 요약

기존 로그인도 Supabase DB 기반이라면 공유 기능은 `auth.uid()`가 아니라 **기존 로그인 검증 API가 확인한 앱 사용자 ID**를 기준으로 설계해야 한다. 앱은 공유 API만 호출하고, Supabase DB 쓰기는 서버에서 처리하는 방식이 맞다.
