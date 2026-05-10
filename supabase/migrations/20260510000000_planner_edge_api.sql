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
  updated_at timestamptz not null default now(),
  constraint planner_profiles_public_id_format
    check (public_id ~ '^(omp|pb)-[A-Z0-9]{6,16}$')
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

create index if not exists planner_profiles_public_id_idx on public.planner_profiles(public_id);
create index if not exists planner_group_members_user_id_idx on public.planner_group_members(user_id);
create index if not exists planner_schedules_group_start_idx on public.planner_schedules(group_id, start_at);
create index if not exists planner_personal_schedules_user_start_idx on public.planner_personal_schedules(created_by, start_at);
create index if not exists planner_dummy_schedules_group_start_idx on public.planner_dummy_schedules(group_id, start_at);

alter table public.planner_users enable row level security;
alter table public.planner_profiles enable row level security;
alter table public.planner_groups enable row level security;
alter table public.planner_group_members enable row level security;
alter table public.planner_schedules enable row level security;
alter table public.planner_personal_schedules enable row level security;
alter table public.planner_dummy_schedules enable row level security;

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
revoke all on public.planner_dummy_schedules from anon, authenticated;

grant all on public.planner_users to service_role;
grant all on public.planner_profiles to service_role;
grant all on public.planner_groups to service_role;
grant all on public.planner_group_members to service_role;
grant all on public.planner_schedules to service_role;
grant all on public.planner_personal_schedules to service_role;
grant all on public.planner_dummy_schedules to service_role;
