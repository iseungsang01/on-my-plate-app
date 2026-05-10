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

create table if not exists public.planner_personal_schedule_recurrence_rules (
  schedule_id uuid primary key references public.planner_personal_schedules(id) on delete cascade,
  frequency text not null check (frequency in ('weekly')),
  interval_weeks integer not null default 1 check (interval_weeks >= 1),
  day_of_week integer not null check (day_of_week between 1 and 7),
  until_at timestamptz,
  count integer check (count is null or count > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.planner_personal_schedule_recurrence_exceptions (
  schedule_id uuid not null references public.planner_personal_schedules(id) on delete cascade,
  occurrence_start_at timestamptz not null,
  action text not null check (action in ('skip')),
  created_at timestamptz not null default now(),
  primary key (schedule_id, occurrence_start_at)
);

create index if not exists planner_schedule_recurrence_exceptions_schedule_idx
  on public.planner_schedule_recurrence_exceptions(schedule_id);
create index if not exists planner_personal_schedule_recurrence_exceptions_schedule_idx
  on public.planner_personal_schedule_recurrence_exceptions(schedule_id);

alter table public.planner_schedule_recurrence_rules enable row level security;
alter table public.planner_schedule_recurrence_exceptions enable row level security;
alter table public.planner_personal_schedule_recurrence_rules enable row level security;
alter table public.planner_personal_schedule_recurrence_exceptions enable row level security;

revoke all on public.planner_schedule_recurrence_rules from anon, authenticated;
revoke all on public.planner_schedule_recurrence_exceptions from anon, authenticated;
revoke all on public.planner_personal_schedule_recurrence_rules from anon, authenticated;
revoke all on public.planner_personal_schedule_recurrence_exceptions from anon, authenticated;

grant all on public.planner_schedule_recurrence_rules to service_role;
grant all on public.planner_schedule_recurrence_exceptions to service_role;
grant all on public.planner_personal_schedule_recurrence_rules to service_role;
grant all on public.planner_personal_schedule_recurrence_exceptions to service_role;
