# Supabase Migration Policy

This repository uses **Supabase CLI-managed migrations** as the source of truth for remote database schema changes.

## Rule

Do not rely on Supabase SQL Editor as the primary migration path.

Every database schema change must be represented as a timestamped SQL file under:

```text
supabase/migrations/
```

Example:

```text
supabase/migrations/20260518013800_reconcile_recurrence_rules.sql
```

## Apply migrations

After creating or reviewing a migration file, apply it with Supabase CLI:

```sh
supabase db push
```

If the local repository is not linked to a Supabase project yet:

```sh
supabase link --project-ref <project-ref>
supabase db push
```

## Documentation SQL files

SQL files under `docs/` are explanatory references only.

They may be useful for reading, review, or smoke-test notes, but they are not the canonical migration source. If a SQL change is intended to affect the remote database, the same change must exist under `supabase/migrations/`.

## Why this policy exists

Using CLI-managed migration files gives the project:

- reviewable schema changes,
- reproducible remote DB updates,
- clear commit history,
- less risk of manual SQL Editor drift,
- a single source of truth for Android/API/database compatibility.

## Checklist for future DB changes

1. Create a timestamped file under `supabase/migrations/`.
2. Keep any related `docs/` SQL as reference only.
3. Run local Android verification when relevant.
4. Run `supabase db push`.
5. Commit the migration file with the app/API changes that require it.
