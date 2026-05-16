create extension if not exists pgcrypto;

create table if not exists public.planner_users (
  id text primary key,
  password_hash text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint planner_users_id_format
    check (id ~ '^[A-Za-z0-9._-]{3,40}$')
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
  frequency text not null,
  interval_weeks integer default 1 check (interval_weeks >= 1),
  day_of_week integer check (day_of_week between 1 and 7),
  until_at timestamptz,
  count integer check (count is null or count > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  interval integer not null default 1,
  day_of_month integer,
  constraint planner_schedule_recurrence_rules_frequency_check
    check (frequency in ('daily', 'weekly', 'monthly')),
  constraint planner_schedule_recurrence_rules_interval_check
    check (interval >= 1),
  constraint planner_schedule_recurrence_rules_weekly_anchor_check
    check (frequency <> 'weekly' or day_of_week between 1 and 7),
  constraint planner_schedule_recurrence_rules_monthly_anchor_check
    check (frequency <> 'monthly' or day_of_month between 1 and 31)
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
  frequency text not null,
  interval_weeks integer default 1 check (interval_weeks >= 1),
  day_of_week integer check (day_of_week between 1 and 7),
  until_at timestamptz,
  count integer check (count is null or count > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  interval integer not null default 1,
  day_of_month integer,
  constraint planner_personal_schedule_recurrence_rules_frequency_check
    check (frequency in ('daily', 'weekly', 'monthly')),
  constraint planner_personal_schedule_recurrence_rules_interval_check
    check (interval >= 1),
  constraint planner_personal_schedule_recurrence_rules_weekly_anchor_check
    check (frequency <> 'weekly' or day_of_week between 1 and 7),
  constraint planner_personal_schedule_recurrence_rules_monthly_anchor_check
    check (frequency <> 'monthly' or day_of_month between 1 and 31)
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
  app_version_code integer not null check (app_version_code > 0),
  created_at timestamptz not null default now()
);

create table if not exists public.planner_candidates (
  id uuid primary key default gen_random_uuid(),
  local_candidate_id text,
  created_by text not null references public.planner_users(id) on delete cascade,
  raw_text text not null,
  source_app text,
  extracted_title text not null default '',
  extracted_start_at timestamptz,
  extracted_end_at timestamptz,
  extracted_location text,
  confidence double precision not null default 0,
  time_confidence text not null default '',
  status text not null default 'pending' check (status in ('pending', 'confirmed', 'discarded')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (created_by, local_candidate_id)
);

create index if not exists planner_profiles_public_id_idx on public.planner_profiles(public_id);
create index if not exists planner_group_members_user_id_idx on public.planner_group_members(user_id);
create index if not exists planner_schedules_group_start_idx on public.planner_schedules(group_id, start_at);
create index if not exists planner_personal_schedules_user_start_idx on public.planner_personal_schedules(created_by, start_at);
create index if not exists planner_schedule_recurrence_exceptions_schedule_idx on public.planner_schedule_recurrence_exceptions(schedule_id);
create index if not exists planner_personal_schedule_recurrence_exceptions_schedule_idx on public.planner_personal_schedule_recurrence_exceptions(schedule_id);
create index if not exists planner_dummy_schedules_group_start_idx on public.planner_dummy_schedules(group_id, start_at);
create index if not exists planner_feedback_created_at_idx on public.planner_feedback(created_at desc);
create index if not exists planner_feedback_user_id_idx on public.planner_feedback(user_id);
create index if not exists planner_candidates_user_status_created_idx on public.planner_candidates(created_by, status, created_at desc);

alter table public.planner_users enable row level security;
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
alter table public.planner_candidates enable row level security;

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

revoke all on public.planner_users from anon, authenticated;
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
revoke all on public.planner_candidates from anon, authenticated;

grant all on public.planner_users to service_role;
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
grant all on public.planner_candidates to service_role;
