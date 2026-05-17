create or replace function public.delete_personal_schedule(
  p_user_id text,
  p_schedule_id uuid
)
returns boolean
language plpgsql
as $$
declare
  deleted_count integer;
begin
  if not exists (
    select 1
    from public.planner_personal_schedules
    where id = p_schedule_id
      and created_by = p_user_id
  ) then
    return false;
  end if;

  delete from public.planner_personal_schedule_recurrence_exceptions
  where schedule_id = p_schedule_id;

  delete from public.planner_personal_schedule_recurrence_rules
  where schedule_id = p_schedule_id;

  delete from public.planner_personal_schedules
  where id = p_schedule_id
    and created_by = p_user_id;

  get diagnostics deleted_count = row_count;
  return deleted_count = 1;
end;
$$;

revoke all on function public.delete_personal_schedule(text, uuid) from public;
revoke all on function public.delete_personal_schedule(text, uuid) from anon, authenticated;
grant execute on function public.delete_personal_schedule(text, uuid) to service_role;
