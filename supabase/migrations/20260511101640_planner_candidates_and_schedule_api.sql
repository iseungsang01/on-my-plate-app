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

create index if not exists planner_candidates_user_status_created_idx
  on public.planner_candidates(created_by, status, created_at desc);

alter table public.planner_candidates enable row level security;

revoke all on public.planner_candidates from anon, authenticated;
grant all on public.planner_candidates to service_role;
