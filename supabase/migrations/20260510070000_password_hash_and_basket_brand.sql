alter table public.planner_profiles
  drop constraint if exists planner_profiles_public_id_format;

alter table public.planner_profiles
  add constraint planner_profiles_public_id_format
  check (public_id ~ '^(omp|pb)-[A-Z0-9]{6,16}$');
