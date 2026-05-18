# planner-api Module Boundaries

This Edge Function is being modularized incrementally.

## Current module map

- `index.ts`
  - request routing
  - CORS preflight
  - global error boundary
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

3. `auth.ts` ✅ route/session extracted in P1-5d
   - signup/login/change-password
   - session validation
   - password hashing/session issuing helpers
