create table if not exists public.planner_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id text references public.planner_users(id) on delete set null,
  message text not null,
  source_screen text not null default 'settings',
  app_version_name text not null,
  app_version_code integer not null check (app_version_code > 0),
  created_at timestamptz not null default now()
);

create index if not exists planner_feedback_created_at_idx on public.planner_feedback(created_at desc);
create index if not exists planner_feedback_user_id_idx on public.planner_feedback(user_id);

alter table public.planner_feedback enable row level security;

revoke all on public.planner_feedback from anon, authenticated;
grant all on public.planner_feedback to service_role;
