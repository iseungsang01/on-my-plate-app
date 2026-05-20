create or replace function public.backfill_availability_group_member_pending_responses()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  perform pg_advisory_xact_lock(hashtextextended(new.group_id::text, 0));

  insert into public.availability_group_proposal_responses (proposal_id, member_id, user_id, response)
  select proposal.id, new.id, new.user_id, 'pending'
  from (
    select id
    from public.availability_group_proposals
    where group_id = new.group_id
      and status = 'pending'
    for update
  ) proposal
  on conflict (proposal_id, member_id) do nothing;

  return new;
end;
$$;

drop trigger if exists availability_group_member_pending_response_backfill
  on public.availability_group_members;

create trigger availability_group_member_pending_response_backfill
after insert on public.availability_group_members
for each row
execute function public.backfill_availability_group_member_pending_responses();

create or replace function public.create_availability_group_proposal_pending_responses()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if new.status <> 'pending' then
    return new;
  end if;

  perform pg_advisory_xact_lock(hashtextextended(new.group_id::text, 0));

  insert into public.availability_group_proposal_responses (proposal_id, member_id, user_id, response)
  select new.id, member.id, member.user_id, 'pending'
  from public.availability_group_members member
  where member.group_id = new.group_id
  on conflict (proposal_id, member_id) do nothing;

  return new;
end;
$$;

drop trigger if exists availability_group_proposal_pending_responses
  on public.availability_group_proposals;

create trigger availability_group_proposal_pending_responses
after insert on public.availability_group_proposals
for each row
execute function public.create_availability_group_proposal_pending_responses();

revoke all on function public.backfill_availability_group_member_pending_responses() from anon, authenticated;
grant execute on function public.backfill_availability_group_member_pending_responses() to service_role;

revoke all on function public.create_availability_group_proposal_pending_responses() from anon, authenticated;
grant execute on function public.create_availability_group_proposal_pending_responses() to service_role;
