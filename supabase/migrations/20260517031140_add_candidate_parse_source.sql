alter table if exists public.planner_candidates
  add column if not exists parse_source text not null default 'unknown';

alter table if exists public.planner_candidates
  drop constraint if exists planner_candidates_parse_source_check;

alter table if exists public.planner_candidates
  add constraint planner_candidates_parse_source_check
  check (parse_source in ('llm_success', 'llm_with_local_supplement', 'local_fallback', 'parser_error', 'local_only', 'unknown'));
