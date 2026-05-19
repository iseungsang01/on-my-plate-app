create table if not exists public.availability_groups (
  id uuid primary key default gen_random_uuid(),
  owner_id text not null references public.planner_users(id) on delete cascade,
  title text not null check (char_length(trim(title)) > 0),
  share_code text not null unique check (share_code ~ '^[A-Z2-9]{8}$'),
  scope_start timestamptz not null,
  scope_end timestamptz not null,
  slot_minutes integer not null default 60 check (slot_minutes in (30, 60, 120)),
  search_start_time time not null default time '08:00',
  search_end_time time not null default time '24:00',
  visibility_mode text not null default 'busy_only' check (visibility_mode in ('busy_only', 'expanded_limited')),
  visibility_settings jsonb not null default '{}'::jsonb,
  suggestion_mode text not null default 'everyone' check (suggestion_mode in ('everyone', 'owner_leader', 'owner_only')),
  status text not null default 'active' check (status in ('active', 'archived')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint availability_groups_scope_check check (scope_start < scope_end),
  constraint availability_groups_search_window_check check (search_start_time < search_end_time)
);

create table if not exists public.availability_group_members (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.availability_groups(id) on delete cascade,
  user_id text not null references public.planner_users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'leader', 'member')),
  joined_at timestamptz not null default now(),
  unique (group_id, user_id)
);

create index if not exists availability_groups_share_code_idx on public.availability_groups(share_code);
create index if not exists availability_groups_owner_idx on public.availability_groups(owner_id, created_at desc);
create index if not exists availability_group_members_user_idx on public.availability_group_members(user_id, joined_at desc);
create index if not exists availability_group_members_group_idx on public.availability_group_members(group_id, joined_at);

alter table public.availability_groups enable row level security;
alter table public.availability_group_members enable row level security;

revoke all on public.availability_groups from anon, authenticated;
revoke all on public.availability_group_members from anon, authenticated;
grant all on public.availability_groups to service_role;
grant all on public.availability_group_members to service_role;

create or replace function public.create_availability_group_with_owner(
  p_owner_id text,
  p_title text,
  p_share_code text,
  p_scope_start timestamptz,
  p_scope_end timestamptz,
  p_slot_minutes integer default 60,
  p_search_start_time time default time '08:00',
  p_search_end_time time default time '24:00',
  p_visibility_mode text default 'busy_only',
  p_visibility_settings jsonb default '{}'::jsonb,
  p_suggestion_mode text default 'everyone'
)
returns public.availability_groups
language plpgsql
set search_path = public
as $$
declare
  created_group public.availability_groups;
begin
  insert into public.availability_groups (
    owner_id,
    title,
    share_code,
    scope_start,
    scope_end,
    slot_minutes,
    search_start_time,
    search_end_time,
    visibility_mode,
    visibility_settings,
    suggestion_mode,
    status
  ) values (
    p_owner_id,
    p_title,
    p_share_code,
    p_scope_start,
    p_scope_end,
    p_slot_minutes,
    p_search_start_time,
    p_search_end_time,
    p_visibility_mode,
    coalesce(p_visibility_settings, '{}'::jsonb),
    p_suggestion_mode,
    'active'
  )
  returning * into created_group;

  insert into public.availability_group_members (group_id, user_id, role)
  values (created_group.id, p_owner_id, 'owner');

  return created_group;
end;
$$;

revoke all on function public.create_availability_group_with_owner(text, text, text, timestamptz, timestamptz, integer, time, time, text, jsonb, text) from anon, authenticated;
grant execute on function public.create_availability_group_with_owner(text, text, text, timestamptz, timestamptz, integer, time, time, text, jsonb, text) to service_role;


create table if not exists public.availability_group_dummy_schedules (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.availability_groups(id) on delete cascade,
  created_by text not null references public.planner_users(id) on delete cascade,
  start_at timestamptz not null,
  end_at timestamptz not null,
  private_note text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint availability_group_dummy_schedules_time_check check (start_at < end_at)
);

create table if not exists public.availability_group_proposals (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.availability_groups(id) on delete cascade,
  created_by text not null references public.planner_users(id) on delete cascade,
  title text not null check (char_length(trim(title)) > 0),
  start_at timestamptz not null,
  end_at timestamptz not null,
  snapshot_available_count integer not null check (snapshot_available_count >= 0),
  snapshot_unavailable_count integer not null check (snapshot_unavailable_count >= 0),
  snapshot_total_count integer not null check (snapshot_total_count >= 0),
  snapshot_visibility_mode text not null default 'busy_only' check (snapshot_visibility_mode in ('busy_only', 'expanded_limited')),
  status text not null default 'pending' check (status in ('pending', 'finalized', 'cancelled')),
  finalized_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint availability_group_proposals_time_check check (start_at < end_at),
  constraint availability_group_proposals_snapshot_check check (snapshot_available_count + snapshot_unavailable_count = snapshot_total_count)
);

create table if not exists public.availability_group_proposal_responses (
  id uuid primary key default gen_random_uuid(),
  proposal_id uuid not null references public.availability_group_proposals(id) on delete cascade,
  member_id uuid not null references public.availability_group_members(id) on delete cascade,
  user_id text not null references public.planner_users(id) on delete cascade,
  response text not null default 'pending' check (response in ('pending', 'accepted', 'rejected')),
  responded_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (proposal_id, member_id),
  unique (proposal_id, user_id)
);

create index if not exists availability_group_dummy_schedules_group_time_idx on public.availability_group_dummy_schedules(group_id, start_at, end_at);
create index if not exists availability_group_dummy_schedules_user_idx on public.availability_group_dummy_schedules(created_by, start_at);
create index if not exists availability_group_proposals_group_time_idx on public.availability_group_proposals(group_id, start_at, end_at);
create index if not exists availability_group_proposal_responses_proposal_idx on public.availability_group_proposal_responses(proposal_id);
create index if not exists availability_group_proposal_responses_user_idx on public.availability_group_proposal_responses(user_id, updated_at desc);

create table if not exists public.availability_group_proposal_comments (
  id uuid primary key default gen_random_uuid(),
  proposal_id uuid not null references public.availability_group_proposals(id) on delete cascade,
  group_id uuid not null references public.availability_groups(id) on delete cascade,
  created_by text not null references public.planner_users(id) on delete cascade,
  member_id uuid not null references public.availability_group_members(id) on delete cascade,
  body text not null check (char_length(trim(body)) > 0 and char_length(body) <= 1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists availability_group_proposal_comments_proposal_idx on public.availability_group_proposal_comments(proposal_id, created_at);
create index if not exists availability_group_proposal_comments_member_idx on public.availability_group_proposal_comments(member_id, created_at desc);

alter table public.availability_group_dummy_schedules enable row level security;
alter table public.availability_group_proposals enable row level security;
alter table public.availability_group_proposal_responses enable row level security;
alter table public.availability_group_proposal_comments enable row level security;

revoke all on public.availability_group_dummy_schedules from anon, authenticated;
revoke all on public.availability_group_proposals from anon, authenticated;
revoke all on public.availability_group_proposal_responses from anon, authenticated;
revoke all on public.availability_group_proposal_comments from anon, authenticated;
grant all on public.availability_group_dummy_schedules to service_role;
grant all on public.availability_group_proposals to service_role;
grant all on public.availability_group_proposal_responses to service_role;
grant all on public.availability_group_proposal_comments to service_role;

create or replace function public.finalize_availability_group_proposal(
  p_owner_id text,
  p_group_id uuid,
  p_proposal_id uuid
)
returns integer
language plpgsql
set search_path = public
as $$
declare
  target_proposal public.availability_group_proposals;
  created_count integer := 0;
begin
  select * into target_proposal
  from public.availability_group_proposals
  where id = p_proposal_id
    and group_id = p_group_id
  for update;

  if not found then
    raise exception 'Proposal was not found.' using errcode = 'P0002';
  end if;

  if not exists (
    select 1
    from public.availability_groups
    where id = p_group_id
      and owner_id = p_owner_id
      and status = 'active'
  ) then
    raise exception 'Only the active group owner can finalize proposals.' using errcode = '42501';
  end if;

  if target_proposal.status <> 'pending' then
    raise exception 'Proposal is not pending.' using errcode = '23505';
  end if;

  insert into public.planner_personal_schedules (
    created_by,
    title,
    start_at,
    end_at,
    status,
    source_app,
    updated_at
  )
  select
    response.user_id,
    target_proposal.title,
    target_proposal.start_at,
    target_proposal.end_at,
    'confirmed',
    'availability_group_proposal',
    now()
  from public.availability_group_proposal_responses response
  where response.proposal_id = p_proposal_id
    and response.response = 'accepted';

  get diagnostics created_count = row_count;

  update public.availability_group_proposals
  set status = 'finalized',
      finalized_at = now(),
      updated_at = now()
  where id = p_proposal_id;

  return created_count;
end;
$$;

revoke all on function public.finalize_availability_group_proposal(text, uuid, uuid) from anon, authenticated;
grant execute on function public.finalize_availability_group_proposal(text, uuid, uuid) to service_role;


notify pgrst, 'reload schema';
