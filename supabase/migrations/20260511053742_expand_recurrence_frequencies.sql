alter table public.planner_schedule_recurrence_rules
  drop constraint if exists planner_schedule_recurrence_rules_frequency_check,
  alter column interval_weeks drop not null,
  alter column day_of_week drop not null,
  add column if not exists interval integer not null default 1,
  add column if not exists day_of_month integer,
  add constraint planner_schedule_recurrence_rules_frequency_check
    check (frequency in ('daily', 'weekly', 'monthly')),
  add constraint planner_schedule_recurrence_rules_interval_check
    check (interval >= 1),
  add constraint planner_schedule_recurrence_rules_weekly_anchor_check
    check (frequency <> 'weekly' or day_of_week between 1 and 7),
  add constraint planner_schedule_recurrence_rules_monthly_anchor_check
    check (frequency <> 'monthly' or day_of_month between 1 and 31);

alter table public.planner_personal_schedule_recurrence_rules
  drop constraint if exists planner_personal_schedule_recurrence_rules_frequency_check,
  alter column interval_weeks drop not null,
  alter column day_of_week drop not null,
  add column if not exists interval integer not null default 1,
  add column if not exists day_of_month integer,
  add constraint planner_personal_schedule_recurrence_rules_frequency_check
    check (frequency in ('daily', 'weekly', 'monthly')),
  add constraint planner_personal_schedule_recurrence_rules_interval_check
    check (interval >= 1),
  add constraint planner_personal_schedule_recurrence_rules_weekly_anchor_check
    check (frequency <> 'weekly' or day_of_week between 1 and 7),
  add constraint planner_personal_schedule_recurrence_rules_monthly_anchor_check
    check (frequency <> 'monthly' or day_of_month between 1 and 31);

update public.planner_schedule_recurrence_rules
set interval = coalesce(interval_weeks, interval, 1)
where interval is distinct from coalesce(interval_weeks, interval, 1);

update public.planner_personal_schedule_recurrence_rules
set interval = coalesce(interval_weeks, interval, 1)
where interval is distinct from coalesce(interval_weeks, interval, 1);
