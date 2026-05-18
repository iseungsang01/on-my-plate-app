-- P1-3 recurrence schema reconciliation.
--
-- Purpose:
-- Android and planner-api support daily, weekly, and monthly recurrence.
-- Older SQL drafts only allowed weekly recurrence and omitted columns used by planner-api.
--
-- Run this in Supabase SQL editor before testing daily/monthly recurrence writes.

begin;

alter table if exists public.planner_schedule_recurrence_rules
  add column if not exists interval integer,
  add column if not exists day_of_month integer;

alter table if exists public.planner_personal_schedule_recurrence_rules
  add column if not exists interval integer,
  add column if not exists day_of_month integer;

update public.planner_schedule_recurrence_rules
set interval = coalesce(interval, interval_weeks, 1)
where interval is null;

update public.planner_personal_schedule_recurrence_rules
set interval = coalesce(interval, interval_weeks, 1)
where interval is null;

alter table if exists public.planner_schedule_recurrence_rules
  alter column interval set default 1,
  alter column interval set not null,
  alter column interval_weeks drop not null,
  alter column day_of_week drop not null;

alter table if exists public.planner_personal_schedule_recurrence_rules
  alter column interval set default 1,
  alter column interval set not null,
  alter column interval_weeks drop not null,
  alter column day_of_week drop not null;

alter table if exists public.planner_schedule_recurrence_rules
  drop constraint if exists planner_schedule_recurrence_rules_frequency_check,
  drop constraint if exists planner_schedule_recurrence_rules_interval_weeks_check,
  drop constraint if exists planner_schedule_recurrence_rules_day_of_week_check,
  drop constraint if exists planner_schedule_recurrence_rules_interval_check,
  drop constraint if exists planner_schedule_recurrence_rules_day_of_month_check,
  drop constraint if exists planner_schedule_recurrence_rules_anchor_check;

alter table if exists public.planner_personal_schedule_recurrence_rules
  drop constraint if exists planner_personal_schedule_recurrence_rules_frequency_check,
  drop constraint if exists planner_personal_schedule_recurrence_rules_interval_weeks_check,
  drop constraint if exists planner_personal_schedule_recurrence_rules_day_of_week_check,
  drop constraint if exists planner_personal_schedule_recurrence_rules_interval_check,
  drop constraint if exists planner_personal_schedule_recurrence_rules_day_of_month_check,
  drop constraint if exists planner_personal_schedule_recurrence_rules_anchor_check;

alter table if exists public.planner_schedule_recurrence_rules
  add constraint planner_schedule_recurrence_rules_frequency_check
    check (frequency in ('daily', 'weekly', 'monthly')),
  add constraint planner_schedule_recurrence_rules_interval_check
    check (interval >= 1),
  add constraint planner_schedule_recurrence_rules_interval_weeks_check
    check (interval_weeks is null or interval_weeks >= 1),
  add constraint planner_schedule_recurrence_rules_day_of_week_check
    check (day_of_week is null or day_of_week between 1 and 7),
  add constraint planner_schedule_recurrence_rules_day_of_month_check
    check (day_of_month is null or day_of_month between 1 and 31),
  add constraint planner_schedule_recurrence_rules_anchor_check
    check (
      (frequency = 'daily' and day_of_week is null and day_of_month is null)
      or (frequency = 'weekly' and day_of_week is not null and day_of_month is null)
      or (frequency = 'monthly' and day_of_week is null and day_of_month is not null)
    );

alter table if exists public.planner_personal_schedule_recurrence_rules
  add constraint planner_personal_schedule_recurrence_rules_frequency_check
    check (frequency in ('daily', 'weekly', 'monthly')),
  add constraint planner_personal_schedule_recurrence_rules_interval_check
    check (interval >= 1),
  add constraint planner_personal_schedule_recurrence_rules_interval_weeks_check
    check (interval_weeks is null or interval_weeks >= 1),
  add constraint planner_personal_schedule_recurrence_rules_day_of_week_check
    check (day_of_week is null or day_of_week between 1 and 7),
  add constraint planner_personal_schedule_recurrence_rules_day_of_month_check
    check (day_of_month is null or day_of_month between 1 and 31),
  add constraint planner_personal_schedule_recurrence_rules_anchor_check
    check (
      (frequency = 'daily' and day_of_week is null and day_of_month is null)
      or (frequency = 'weekly' and day_of_week is not null and day_of_month is null)
      or (frequency = 'monthly' and day_of_week is null and day_of_month is not null)
    );

commit;
