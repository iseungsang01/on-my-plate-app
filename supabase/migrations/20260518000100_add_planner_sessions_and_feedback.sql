-- Add opaque planner sessions and feedback storage.
-- This migration supports:
-- 1. Auth hardening: sessionToken != userId
-- 2. Feedback API persistence

create table if not exists public.planner_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id text not null references public.planner_users(id) on delete cascade,
  token_hash text not null unique,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  revoked_at timestamptz
);

create index if not exists planner_sessions_user_id_idx
  on public.planner_sessions(user_id);

create index if not exists planner_sessions_token_hash_idx
  on public.planner_sessions(token_hash);

create index if not exists planner_sessions_active_idx
  on public.planner_sessions(token_hash, expires_at)
  where revoked_at is null;

alter table public.planner_sessions enable row level security;

revoke all on public.planner_sessions from anon, authenticated;
grant all on public.planner_sessions to service_role;


create table if not exists public.planner_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id text references public.planner_users(id) on delete set null,
  message text not null,
  source_screen text not null default 'settings',
  app_version_name text not null,
  app_version_code integer not null,
  created_at timestamptz not null default now()
);

create index if not exists planner_feedback_user_created_idx
  on public.planner_feedback(user_id, created_at desc);

alter table public.planner_feedback enable row level security;

revoke all on public.planner_feedback from anon, authenticated;
grant all on public.planner_feedback to service_role;

notify pgrst, 'reload schema';