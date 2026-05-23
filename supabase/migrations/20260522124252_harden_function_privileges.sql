alter function public.delete_personal_schedule(text, uuid)
  set search_path = public;

revoke all on function public.backfill_availability_group_member_pending_responses() from public;
revoke all on function public.backfill_availability_group_member_pending_responses() from anon, authenticated;
grant execute on function public.backfill_availability_group_member_pending_responses() to service_role;

revoke all on function public.create_availability_group_proposal_pending_responses() from public;
revoke all on function public.create_availability_group_proposal_pending_responses() from anon, authenticated;
grant execute on function public.create_availability_group_proposal_pending_responses() to service_role;

notify pgrst, 'reload schema';