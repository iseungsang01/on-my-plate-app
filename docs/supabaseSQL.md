create extension if not exists pgcrypto;

-- Supabase Auth is intentionally not used. The Supabase Edge Function
-- `planner-api` owns app login and uses service_role credentials internally;
-- Android never receives service_role credentials.
create table if not exists public.planner_users (
  id text primary key,
  password_hash text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint planner_users_id_format
    check (id ~ '^[A-Za-z0-9._-]{3,40}$')
);

create table if not exists public.planner_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id text not null references public.planner_users(id) on delete cascade,
  token_hash text not null unique,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  revoked_at timestamptz
);

create table if not exists public.planner_profiles (
  user_id text primary key,
  public_id text not null unique,
  display_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
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

create table if not exists public.planner_personal_schedules (
  id uuid primary key default gen_random_uuid(),
  local_schedule_id text,
  created_by text not null references public.planner_users(id) on delete cascade,
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
  unique (created_by, local_schedule_id)
);

create table if not exists public.planner_schedule_recurrence_rules (
  schedule_id uuid primary key references public.planner_schedules(id) on delete cascade,
  frequency text not null check (frequency in ('daily', 'weekly', 'monthly')),
  interval integer not null default 1 check (interval >= 1),
  interval_weeks integer check (interval_weeks is null or interval_weeks >= 1),
  day_of_week integer check (day_of_week is null or day_of_week between 1 and 7),
  day_of_month integer check (day_of_month is null or day_of_month between 1 and 31),
  until_at timestamptz,
  count integer check (count is null or count > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint planner_schedule_recurrence_rules_anchor_check check (
    (frequency = 'daily' and day_of_week is null and day_of_month is null)
    or (frequency = 'weekly' and day_of_week is not null and day_of_month is null)
    or (frequency = 'monthly' and day_of_week is null and day_of_month is not null)
  )
);

create table if not exists public.planner_schedule_recurrence_exceptions (
  schedule_id uuid not null references public.planner_schedules(id) on delete cascade,
  occurrence_start_at timestamptz not null,
  action text not null check (action in ('skip')),
  created_at timestamptz not null default now(),
  primary key (schedule_id, occurrence_start_at)
);

create table if not exists public.planner_personal_schedule_recurrence_rules (
  schedule_id uuid primary key references public.planner_personal_schedules(id) on delete cascade,
  frequency text not null check (frequency in ('daily', 'weekly', 'monthly')),
  interval integer not null default 1 check (interval >= 1),
  interval_weeks integer check (interval_weeks is null or interval_weeks >= 1),
  day_of_week integer check (day_of_week is null or day_of_week between 1 and 7),
  day_of_month integer check (day_of_month is null or day_of_month between 1 and 31),
  until_at timestamptz,
  count integer check (count is null or count > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint planner_personal_schedule_recurrence_rules_anchor_check check (
    (frequency = 'daily' and day_of_week is null and day_of_month is null)
    or (frequency = 'weekly' and day_of_week is not null and day_of_month is null)
    or (frequency = 'monthly' and day_of_week is null and day_of_month is not null)
  )
);

create table if not exists public.planner_personal_schedule_recurrence_exceptions (
  schedule_id uuid not null references public.planner_personal_schedules(id) on delete cascade,
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

create table if not exists public.planner_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id text references public.planner_users(id) on delete set null,
  message text not null,
  source_screen text not null default 'settings',
  app_version_name text not null,
  app_version_code integer not null,
  created_at timestamptz not null default now()
);

create index if not exists planner_sessions_user_id_idx on public.planner_sessions(user_id);
create index if not exists planner_sessions_token_hash_idx on public.planner_sessions(token_hash);
create index if not exists planner_sessions_active_idx
  on public.planner_sessions(token_hash, expires_at)
  where revoked_at is null;
create index if not exists planner_profiles_public_id_idx on public.planner_profiles(public_id);
create index if not exists planner_group_members_user_id_idx on public.planner_group_members(user_id);
create index if not exists planner_schedules_group_start_idx on public.planner_schedules(group_id, start_at);
create index if not exists planner_personal_schedules_user_start_idx on public.planner_personal_schedules(created_by, start_at);
create index if not exists planner_schedule_recurrence_exceptions_schedule_idx on public.planner_schedule_recurrence_exceptions(schedule_id);
create index if not exists planner_personal_schedule_recurrence_exceptions_schedule_idx on public.planner_personal_schedule_recurrence_exceptions(schedule_id);
create index if not exists planner_dummy_schedules_group_start_idx on public.planner_dummy_schedules(group_id, start_at);
create index if not exists planner_feedback_user_created_idx
  on public.planner_feedback(user_id, created_at desc);

alter table public.planner_users enable row level security;
alter table public.planner_sessions enable row level security;
alter table public.planner_profiles enable row level security;
alter table public.planner_groups enable row level security;
alter table public.planner_group_members enable row level security;
alter table public.planner_schedules enable row level security;
alter table public.planner_personal_schedules enable row level security;
alter table public.planner_schedule_recurrence_rules enable row level security;
alter table public.planner_schedule_recurrence_exceptions enable row level security;
alter table public.planner_personal_schedule_recurrence_rules enable row level security;
alter table public.planner_personal_schedule_recurrence_exceptions enable row level security;
alter table public.planner_dummy_schedules enable row level security;
alter table public.planner_feedback enable row level security;

-- Remove policies from the previous anonymous Supabase Auth draft, if present.
drop policy if exists "profiles are lookupable by authenticated users" on public.planner_profiles;
drop policy if exists "users create their own profile" on public.planner_profiles;
drop policy if exists "users update their own profile" on public.planner_profiles;
drop policy if exists "members can read their groups" on public.planner_groups;
drop policy if exists "authenticated users create groups" on public.planner_groups;
drop policy if exists "creators update groups" on public.planner_groups;
drop policy if exists "members can read own membership rows" on public.planner_group_members;
drop policy if exists "users and group creators add members" on public.planner_group_members;
drop policy if exists "users remove own membership" on public.planner_group_members;
drop policy if exists "members read shared schedules" on public.planner_schedules;
drop policy if exists "members insert shared schedules" on public.planner_schedules;
drop policy if exists "creators update shared schedules" on public.planner_schedules;
drop policy if exists "creators delete shared schedules" on public.planner_schedules;
drop policy if exists "members read dummy schedules" on public.planner_dummy_schedules;
drop policy if exists "members insert dummy schedules" on public.planner_dummy_schedules;
drop policy if exists "creators update dummy schedules" on public.planner_dummy_schedules;
drop policy if exists "creators delete dummy schedules" on public.planner_dummy_schedules;

-- No anon/authenticated policies are created. The Edge Function must enforce
-- login, membership, and ownership checks before using its server-only
-- service_role credentials.
revoke all on public.planner_users from anon, authenticated;
revoke all on public.planner_sessions from anon, authenticated;
revoke all on public.planner_profiles from anon, authenticated;
revoke all on public.planner_groups from anon, authenticated;
revoke all on public.planner_group_members from anon, authenticated;
revoke all on public.planner_schedules from anon, authenticated;
revoke all on public.planner_personal_schedules from anon, authenticated;
revoke all on public.planner_schedule_recurrence_rules from anon, authenticated;
revoke all on public.planner_schedule_recurrence_exceptions from anon, authenticated;
revoke all on public.planner_personal_schedule_recurrence_rules from anon, authenticated;
revoke all on public.planner_personal_schedule_recurrence_exceptions from anon, authenticated;
revoke all on public.planner_dummy_schedules from anon, authenticated;
revoke all on public.planner_feedback from anon, authenticated;

grant all on public.planner_users to service_role;
grant all on public.planner_sessions to service_role;
grant all on public.planner_profiles to service_role;
grant all on public.planner_groups to service_role;
grant all on public.planner_group_members to service_role;
grant all on public.planner_schedules to service_role;
grant all on public.planner_personal_schedules to service_role;
grant all on public.planner_schedule_recurrence_rules to service_role;
grant all on public.planner_schedule_recurrence_exceptions to service_role;
grant all on public.planner_personal_schedule_recurrence_rules to service_role;
grant all on public.planner_personal_schedule_recurrence_exceptions to service_role;
grant all on public.planner_dummy_schedules to service_role;
grant all on public.planner_feedback to service_role;

-- Optional seed data for backend/API smoke tests.
insert into public.planner_profiles (user_id, public_id, display_name)
values
  ('demo-user-a', 'omp-DEMO0001', 'Demo A'),
  ('demo-user-b', 'omp-DEMO0002', 'Demo B')
on conflict (user_id) do update
set public_id = excluded.public_id,
    display_name = excluded.display_name,
    updated_at = now();

insert into public.planner_groups (id, name, created_by)
values ('10000000-0000-0000-0000-000000000001', 'Demo shared group', 'demo-user-a')
on conflict (id) do update set name = excluded.name;

insert into public.planner_group_members (group_id, user_id, role)
values
  ('10000000-0000-0000-0000-000000000001', 'demo-user-a', 'owner'),
  ('10000000-0000-0000-0000-000000000001', 'demo-user-b', 'member')
on conflict (group_id, user_id) do update set role = excluded.role;

insert into public.planner_schedules (group_id, local_schedule_id, created_by, title, start_at, end_at, location, status, source_app)
values (
  '10000000-0000-0000-0000-000000000001',
  'local-demo-1',
  'demo-user-a',
  '공유 회의 예시',
  now() + interval '1 day',
  now() + interval '1 day 1 hour',
  '회의실',
  'confirmed',
  'seed'
)
on conflict (group_id, created_by, local_schedule_id) do update
set title = excluded.title,
    start_at = excluded.start_at,
    end_at = excluded.end_at,
    location = excluded.location,
    status = excluded.status,
    updated_at = now();

insert into public.planner_dummy_schedules (group_id, created_by, title, start_at, end_at, location, status, memo)
values (
  '10000000-0000-0000-0000-000000000001',
  'demo-user-b',
  '더미 일정 예시',
  now() + interval '2 days',
  now() + interval '2 days 30 minutes',
  '카페',
  'planned',
  '이 일정은 Room에 저장되지 않는 공유 화면 전용 더미 일정입니다'
);
