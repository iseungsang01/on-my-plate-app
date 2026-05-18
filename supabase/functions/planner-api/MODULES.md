# planner-api Module Boundaries

This Edge Function is being modularized incrementally.

## Current module map

- `index.ts`
  - request routing
  - CORS preflight
  - global error boundary
  - auth routes
  - personal schedule routes
  - candidate routes
  - sharing routes
  - feedback route
  - shared DB helpers still awaiting extraction

- `parser.ts`
  - `/api/parser/appointment`
  - Gemini proxy call
  - parser prompt
  - parser response normalization
  - parser-local JSON/error helpers

## Target module map

Future phases should extract modules in this order:

1. `http.ts` ✅ extracted in P1-5b
   - `corsHeaders`
   - `jsonResponse`
   - `errorResponse`
   - `apiError`
   - `toApiError`
   - `readJson`

2. `db.ts`
   - Supabase client construction
   - env validation

3. `auth.ts`
   - signup/login/change-password
   - session validation
   - password hashing

4. `recurrence.ts`
   - recurrence payload parsing
   - recurrence persistence
   - recurrence response mapping

5. `schedules.ts`
   - personal schedule CRUD
   - shared schedule upload/list

6. `candidates.ts`
   - candidate CRUD

7. `sharing.ts`
   - profile/group/member routes

8. `feedback.ts`
   - feedback submission

## Rule

Do not extract multiple high-risk modules in one patch unless there are route-level smoke tests covering the affected paths.
