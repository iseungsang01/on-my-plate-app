from pathlib import Path

ROOT = Path(".")
SQL_DOC = ROOT / "docs/supabaseSQL.md"
INDEX = ROOT / "supabase/functions/planner-api/index.ts"

def read(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Missing file: {path}")
    return path.read_text(encoding="utf-8-sig")

def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")

def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Could not find expected block for {label}. File may have changed.")
    return text.replace(old, new, 1)

def patch_feedback_sql() -> None:
    text = read(SQL_DOC)

    if "create table if not exists public.planner_feedback" not in text:
        anchor = """create table if not exists public.planner_dummy_schedules (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.planner_groups(id) on delete cascade,
  created_by text not null,
  title text not null,
  start_at timestamptz not null,
  end_at timestamptz,
  location text,
  memo text,
  status text not null default 'planned' check (status in ('confirmed', 'planned', 'uncertain')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

"""
        feedback_table = anchor + """create table if not exists public.planner_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id text references public.planner_users(id) on delete set null,
  message text not null,
  source_screen text not null default 'settings',
  app_version_name text not null,
  app_version_code integer not null,
  created_at timestamptz not null default now()
);

"""
        text = replace_once(text, anchor, feedback_table, "planner_feedback table")

    if "planner_feedback_user_created_idx" not in text:
        marker = "create index if not exists planner_dummy_schedules_group_start_idx on public.planner_dummy_schedules(group_id, start_at);\n"
        addition = marker + """create index if not exists planner_feedback_user_created_idx
  on public.planner_feedback(user_id, created_at desc);
"""
        text = replace_once(text, marker, addition, "planner_feedback index")

    if "alter table public.planner_feedback enable row level security;" not in text:
        marker = "alter table public.planner_dummy_schedules enable row level security;\n"
        text = replace_once(
            text,
            marker,
            marker + "alter table public.planner_feedback enable row level security;\n",
            "planner_feedback RLS",
        )

    if "revoke all on public.planner_feedback from anon, authenticated;" not in text:
        marker = "revoke all on public.planner_dummy_schedules from anon, authenticated;\n"
        text = replace_once(
            text,
            marker,
            marker + "revoke all on public.planner_feedback from anon, authenticated;\n",
            "planner_feedback revoke",
        )

    if "grant all on public.planner_feedback to service_role;" not in text:
        marker = "grant all on public.planner_dummy_schedules to service_role;\n"
        text = replace_once(
            text,
            marker,
            marker + "grant all on public.planner_feedback to service_role;\n",
            "planner_feedback grant",
        )

    write(SQL_DOC, text)
    print(f"Patched feedback SQL: {SQL_DOC}")

def patch_broken_korean_messages() -> None:
    text = read(INDEX)

    replacements = {
        '"?쇱젙???李얠쓣 ???놁뒿?덈떎."': '"일정을 찾을 수 없습니다."',
        '"?쎌냽???李얠쓣 ???놁뒿?덈떎."': '"약속 후보를 찾을 수 없습니다."',
        '"?쎌냽 ?띿뒪?몄? ?꾩슂?⑸땲??"': '"약속 텍스트가 필요합니다."',
    }

    changed = False
    for old, new in replacements.items():
        if old in text:
            text = text.replace(old, new)
            changed = True

    if changed:
        write(INDEX, text)
        print(f"Patched broken Korean messages: {INDEX}")
    else:
        print("No known broken Korean messages found, or already patched.")

def main() -> None:
    if not (ROOT / "settings.gradle.kts").exists():
        raise RuntimeError("Run this script from the repository root.")

    patch_feedback_sql()
    patch_broken_korean_messages()

    print()
    print("Done.")
    print("Next:")
    print("  .\\gradlew.bat :app:assembleDebug")
    print("  supabase functions deploy planner-api --use-api --no-verify-jwt --project-ref gznuqhjenzeucpmonesl")
    print()
    print("DB step:")
    print("  Apply the new planner_feedback SQL from docs/supabaseSQL.md to Supabase SQL Editor or your migration flow.")

if __name__ == "__main__":
    main()
