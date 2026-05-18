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
  - domain DB helper functions still awaiting extraction

- `parser.ts`
  - `/api/parser/appointment`
  - Gemini proxy call
  - parser prompt
  - parser response normalization
  - parser-local JSON/error helpers

- `auth.ts`
  - session token validation extracted in P1-5d-1
  - signup/login/change-password/password hashing still awaiting extraction

## Target module map

Future phases should extract modules in this order:

1. `http.ts` ✅ extracted in P1-5b
   - `corsHeaders`
   - `jsonResponse`
   - `errorResponse`
   - `apiError`
   - `toApiError`
   - `readJson`

2. `db.ts` ✅ extracted in P1-5c
   - Supabase client construction
   - env validation

3. `auth.ts` ⏳ partially extracted in P1-5d-1
   - session validation ✅ extracted
   - signup/login/change-password still in `index.ts`
   - password hashing still in `index.ts`

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
