# On My Plate — Availability Group Sharing Entry & Stabilization Real Plan

## 0. Purpose

This document defines the implementation plan for making the existing Availability Group Sharing backend usable from the Android app.

The backend/API layer for Availability Group Sharing is already largely implemented. The remaining work is not to redesign the feature from scratch, but to:

1. Add a user-facing entry point and navigation flow.
2. Add missing client-side repository/API integration.
3. Stabilize small backend contract gaps before exposing the feature in UI.
4. Add smoke tests for the full group-sharing flow.

This plan intentionally excludes broad product redesign and general planner sharing. The feature remains a group-based availability matching system.

---

## 1. Current Baseline

### 1.1 Existing Backend Capabilities

The current backend already contains the main Availability Group Sharing API surface:

- Create availability group
- Join group by share code
- Fetch group metadata
- Calculate group availability
- Create/list/delete group-specific dummy schedules
- Create/list/respond/finalize proposals
- Create/list proposal comments
- Assign/unassign leader role
- Store proposal availability snapshots
- Finalize accepted responses into real personal schedules

### 1.2 Existing Database Capabilities

The current database migration already contains dedicated availability-sharing tables:

- `availability_groups`
- `availability_group_members`
- `availability_group_dummy_schedules`
- `availability_group_proposals`
- `availability_group_proposal_responses`
- `availability_group_proposal_comments`

It also includes RPCs for:

- Creating a group and owner membership together
- Finalizing a proposal into accepted members' personal schedules

### 1.3 Current Missing Layer

The Android app currently lacks a clear user-facing entry point for the feature.

This means the backend exists, but users cannot naturally access the feature from the app.

---

## 2. Scope

## 2.1 In Scope

This implementation plan covers:

- Navigation entry for Availability Group Sharing
- Minimal Android screens
- Repository/API client methods
- Data models for group sharing
- Backend contract fixes needed before UI exposure
- Smoke tests
- Release-readiness validation

## 2.2 Out of Scope

This implementation plan does not cover:

- Public anonymous link sharing
- Non-app-user sharing
- Full group chat
- Exposing schedule titles/details to other members
- General planner sharing
- Web dashboard implementation
- Complex ranking ML/recommendation
- Calendar provider sync

---

## 3. Design Principles

## 3.1 Privacy First

The default and primary visibility mode is `busy_only`.

The app must not expose another member's:

- Schedule title
- Memo
- Location
- Source text
- Source app
- Reason for unavailability
- Raw user id
- Raw member id to non-owners unless explicitly needed for safe management

Availability should be shown as aggregate counts:

```text
3/4 available
```

## 3.2 API as Source of Truth

The Android client should not recompute group availability from local schedules.

The client should call the backend availability endpoint and render returned slots.

## 3.3 Minimal First UI

The first UI should be functional, not decorative.

The minimum useful flow is:

```text
Entry point
→ Group list
→ Create group
→ Join group
→ Group detail
→ Availability slots
→ Proposal creation
→ Proposal response
→ Owner finalize
```

## 3.4 Keep Sharing Feature Isolated

Availability Group Sharing should be implemented as its own feature module/section where possible.

It should not be mixed into the existing weekly schedule screen except through final schedule creation after proposal finalization.

---

## 4. Required Backend Stabilization Before UI Exposure

## P1-A. Add Member List API or Owner-Safe Member Payload

### Problem

Leader assignment APIs require a `memberId`.

However, current group metadata returns only a member summary, not a selectable member list.

### Required Fix

Add one of the following:

### Option A — Dedicated endpoint

```http
GET /api/planner/availability-groups/:groupId/members
```

Response:

```json
{
  "members": [
    {
      "id": "member_uuid",
      "role": "owner",
      "isMe": true,
      "joinedAt": "2026-05-21T00:00:00Z"
    },
    {
      "id": "member_uuid",
      "role": "member",
      "isMe": false,
      "joinedAt": "2026-05-21T00:00:00Z"
    }
  ]
}
```

### Option B — Expand `GET /availability-groups/:groupId`

Return member list only in safe form.

Recommended first choice: **Option A**.

### Rules

- Do not expose raw `user_id`.
- Expose `member.id` because it is required for group management.
- For non-owner users, member ids may still be safe if no raw user id is included, but initial implementation can restrict full member list to owner if desired.
- `isMe` should always be available.

### Acceptance Criteria

- Owner can fetch member list.
- Owner can assign/unassign leader using returned member id.
- Raw `user_id` is not returned.
- Non-members receive 403.

---

## P1-B. Decide Proposal Audience Policy for Members Who Join Later

### Problem

When a proposal is created, pending response rows are created for current members.

If a new member joins after proposal creation, that member may not have a proposal response row.

### Recommended Policy

Use policy 2:

```text
When a user joins an availability group, create pending response rows for that user for all currently pending proposals in the group.
```

### Required Fix

Update `joinAvailabilityGroup`:

1. Insert the new membership.
2. Fetch all pending proposals for the group.
3. Insert pending `availability_group_proposal_responses` rows for the new member.
4. Ignore conflicts safely if rows already exist.

### Acceptance Criteria

- New member can respond to pending proposals created before joining.
- New member does not receive responses for finalized or cancelled proposals.
- Duplicate rows are not created.
- Existing join behavior remains idempotent with duplicate membership rejection.

---

## P1-C. Split Availability Sorting Policy

### Problem

Current availability slots are sorted by `rankScore`, not chronological order.

This is good for recommendations, but not ideal for a calendar/table UI.

### Required Fix

Support explicit sort mode.

Recommended API:

```http
GET /api/planner/availability-groups/:groupId/availability?sort=time
GET /api/planner/availability-groups/:groupId/availability?sort=rank
```

Default should be:

```text
sort=time
```

### Rules

- `sort=time`: chronological order by `startsAt`
- `sort=rank`: best slots first using `rankScore`
- Invalid sort value returns 400

### Acceptance Criteria

- Default availability response is chronological.
- Recommendation UI can request rank order.
- Existing `rankScore` remains available.

---

## P1-D. Align Naming Contract in Documentation

### Problem

The plan and implementation differ slightly in terminology.

Current implementation uses:

```text
proposal.status = pending / finalized / cancelled
suggestion_mode = everyone / owner_leader / owner_only
visibility_mode = busy_only / expanded_limited
```

The older plan text uses slightly different names.

### Required Fix

Update documentation to treat the current implementation terms as canonical.

### Canonical Terms

```text
Proposal status:
- pending
- finalized
- cancelled

Suggestion mode:
- everyone
- owner_leader
- owner_only

Visibility mode:
- busy_only
- expanded_limited
```

### Acceptance Criteria

- Docs, tests, Android models, and API serializers use the same names.
- No Android enum expects `proposed`, `leader_only`, `title_visible`, or `detail_visible`.

---

## 5. Android Client Implementation Plan

## P2-A. Add Data Models

Create Android/Kotlin models for the API responses.

Recommended file:

```text
app/src/main/java/com/lss/onmyplate/nativeplanner/data/model/AvailabilityGroupModels.kt
```

Models:

```kotlin
data class AvailabilityGroupDto(...)
data class AvailabilityGroupMemberDto(...)
data class AvailabilitySlotDto(...)
data class AvailabilityGroupDummyScheduleDto(...)
data class AvailabilityGroupProposalDto(...)
data class AvailabilityGroupProposalResponseDto(...)
data class AvailabilityGroupProposalCommentDto(...)
```

Required enum-like values:

```kotlin
GroupRole.Owner / Leader / Member
ProposalStatus.Pending / Finalized / Cancelled
ProposalResponse.Pending / Accepted / Rejected
SuggestionMode.Everyone / OwnerLeader / OwnerOnly
VisibilityMode.BusyOnly / ExpandedLimited
```

### Acceptance Criteria

- JSON field names match backend camelCase response.
- Unknown enum value fails gracefully or maps to `Unknown`.
- Models do not contain other members' raw user ids.

---

## P2-B. Add Repository/API Methods

Extend the existing planner repository or add a dedicated availability group repository.

Recommended if the current repository is already large:

```text
AvailabilityGroupRepository.kt
```

Required methods:

```kotlin
suspend fun createAvailabilityGroup(...)
suspend fun joinAvailabilityGroup(shareCode: String)
suspend fun getAvailabilityGroup(groupId: String)
suspend fun listAvailabilityGroupMembers(groupId: String)
suspend fun getAvailability(groupId: String, sort: AvailabilitySort = Time)
suspend fun createDummySchedule(groupId: String, ...)
suspend fun deleteDummySchedule(groupId: String, dummyScheduleId: String)
suspend fun listDummySchedules(groupId: String)
suspend fun createProposal(groupId: String, ...)
suspend fun listProposals(groupId: String)
suspend fun respondToProposal(groupId: String, proposalId: String, response: ProposalResponse)
suspend fun finalizeProposal(groupId: String, proposalId: String)
suspend fun listProposalComments(groupId: String, proposalId: String)
suspend fun createProposalComment(groupId: String, proposalId: String, body: String)
suspend fun assignLeader(groupId: String, memberId: String)
suspend fun unassignLeader(groupId: String, memberId: String)
```

### Acceptance Criteria

- All methods include auth token.
- 401 clears or invalidates auth state consistently with existing app behavior.
- API errors surface as user-readable runtime errors.
- No method requires UI to know raw endpoint string directly.

---

## P2-C. Add Navigation Route

Add a route to the app navigation.

Suggested route names:

```text
availabilityGroups
availabilityGroupDetail/{groupId}
availabilityGroupCreate
availabilityGroupJoin
availabilityProposalDetail/{groupId}/{proposalId}
```

Suggested user-facing labels:

```text
공유 가능 시간
그룹 가능 시간
약속 조율
```

Recommended first entry label:

```text
약속 조율
```

### Placement Options

### Option A — Main tab / main screen entry

Best if the feature is a first-class product feature.

### Option B — Schedule screen action button

Best if the feature should feel like an extension of scheduling.

### Option C — Settings/experimental entry

Best if feature is not ready for all users.

Recommended first implementation: **Option B**, because the feature is schedule-related but not yet a core tab.

### Acceptance Criteria

- User can enter the group-sharing flow from the app without deep link/manual route.
- Back navigation works.
- If unauthenticated, user is routed to login or shown an auth-required state.

---

## 6. Minimal Screen Plan

## P3-A. Availability Group List Screen

Purpose:

```text
Show groups the current user belongs to.
```

Required UI:

- Empty state
- Create group button
- Join group button
- Group cards
- Status badge
- Date range
- Member count summary

Required backend support:

Current backend may not yet have a list endpoint for availability groups. Add if missing:

```http
GET /api/planner/availability-groups
```

Response:

```json
{
  "groups": [...]
}
```

Acceptance criteria:

- Shows joined groups.
- Pull-to-refresh or refresh button works.
- Tapping a group opens detail screen.

---

## P3-B. Create Group Screen

Fields:

- Title
- Scope start date/time
- Scope end date/time
- Slot size: 30 / 60 / 120
- Search start time
- Search end time

Initial defaults:

```text
slotMinutes = 60
searchStartTime = 08:00
searchEndTime = 24:00
visibilityMode = busy_only
suggestionMode = everyone
```

Acceptance criteria:

- Successful create returns share code.
- Creator sees share code.
- Created group opens detail screen.

---

## P3-C. Join Group Screen

Fields:

- Share code

Rules:

- Uppercase normalization
- Trim whitespace
- Show clear error for invalid/archived/duplicate group

Acceptance criteria:

- User can join by share code.
- Duplicate join shows controlled error.
- Successful join opens detail screen.

---

## P3-D. Group Detail Screen

Sections:

1. Group metadata
2. Share code, owner only or creator-safe display
3. Member summary
4. Availability slots
5. Dummy schedules
6. Proposals

Required actions:

- Refresh availability
- Create dummy schedule
- Create proposal from selected slot
- Open proposals
- Owner finalize proposal
- Respond to proposal

Acceptance criteria:

- Availability displays `n/m available`.
- No personal schedule detail is shown.
- Dummy schedules affect availability after refresh.
- Proposal creation uses selected slot time.

---

## P3-E. Proposal Detail Screen

Shows:

- Proposal title
- Time range
- Availability snapshot
- Response summary
- My response
- Comments
- Finalize button for owner

Actions:

- Accept
- Reject
- Add comment
- Finalize, owner only

Acceptance criteria:

- User can only update own response.
- Owner can finalize pending proposal.
- After finalize, accepted users get real personal schedules.
- Finalized proposal cannot be responded to again.

---

## 7. Backend API Additions Summary

## Required

```http
GET /api/planner/availability-groups
GET /api/planner/availability-groups/:groupId/members
GET /api/planner/availability-groups/:groupId/availability?sort=time|rank
```

## Recommended

```http
POST /api/planner/availability-groups/:groupId/proposals/:proposalId/cancel
```

Cancellation can be deferred if not needed for MVP.

---

## 8. Implementation Order

## Step 1 — Backend Contract Patch

Implement:

1. List availability groups
2. List members
3. Join creates response rows for pending proposals
4. Availability sort mode
5. Docs naming alignment

Validation:

```cmd
supabase db push
supabase functions serve planner-api --env-file .env.local
python scripts/check_availability_group_contracts.py
```

---

## Step 2 — API Smoke Test Script

Add:

```text
scripts/smoke_availability_group_api.py
```

Test flow:

1. Sign up/login user A
2. Sign up/login user B
3. A creates group
4. B joins by share code
5. A creates personal schedule
6. B creates dummy schedule
7. A fetches availability
8. A creates proposal
9. B accepts proposal
10. A finalizes proposal
11. B's personal schedule list includes finalized schedule

Acceptance criteria:

- Script exits 0.
- Created schedule count matches accepted members.
- No rejected/pending member receives schedule.

---

## Step 3 — Android Models and Repository

Implement models and repository methods.

Validation:

```cmd
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

---

## Step 4 — Navigation and Minimal Screens

Implement:

1. Availability group list screen
2. Create group screen
3. Join group screen
4. Group detail screen
5. Proposal detail screen

Validation:

```cmd
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

Manual QA:

```text
Create group
Join group
View availability
Create dummy block
Create proposal
Accept/reject proposal
Finalize proposal
Confirm accepted user's schedule appears
```

---

## Step 5 — Polish and Error Handling

Add:

- Loading states
- Empty states
- Retry buttons
- Conflict messages
- Finalized proposal state
- Archived group state
- Share code copy button

---

## 9. Release Checklist

## Backend

- [ ] Migration applies cleanly.
- [ ] Edge Function serves locally.
- [ ] Static contract check passes.
- [ ] API smoke test passes.
- [ ] Availability response does not leak schedule details.
- [ ] Proposal finalize creates schedules only for accepted members.

## Android

- [ ] Route entry exists.
- [ ] Group list loads.
- [ ] Create group works.
- [ ] Join group works.
- [ ] Availability slots render.
- [ ] Dummy schedule changes availability.
- [ ] Proposal creation works.
- [ ] Accept/reject works.
- [ ] Owner finalize works.
- [ ] Accepted user sees created schedule.
- [ ] Rejected/pending users do not get created schedule.

## Privacy

- [ ] No other member's schedule title is shown.
- [ ] No other member's memo is shown.
- [ ] No other member's location is shown.
- [ ] No raw user id is shown.
- [ ] Dummy schedule private note is visible only to creator.

---

## 10. Suggested Work Packages

## P1-AG-1 — Backend Contract Completion

Purpose:

```text
Complete missing API contracts before exposing availability groups in Android UI.
```

Includes:

- list groups
- list members
- join pending proposal response backfill
- availability sort mode
- docs naming alignment

---

## P1-AG-2 — API Smoke Test

Purpose:

```text
Prove the full create/join/availability/proposal/finalize flow works.
```

Includes:

- create test users
- create group
- join group
- create busy data
- create proposal
- respond
- finalize
- assert personal schedule creation

---

## P1-AG-3 — Android Repository Integration

Purpose:

```text
Expose availability group APIs to the Android app through typed repository methods.
```

Includes:

- DTOs
- request models
- response parsing
- runtime error handling
- auth token reuse

---

## P1-AG-4 — Navigation Entry and Minimal Screens

Purpose:

```text
Make the feature accessible from the app.
```

Includes:

- route definitions
- schedule screen entry button
- group list
- create group
- join group
- group detail
- proposal detail

---

## P1-AG-5 — UX Stabilization

Purpose:

```text
Make the MVP usable enough for internal testing.
```

Includes:

- loading states
- empty states
- retry
- copy share code
- clear error messages
- finalized proposal state

---

## 11. Risk Register

| Risk | Severity | Mitigation |
|---|---:|---|
| API naming mismatch between plan and code | Medium | Treat current backend terms as canonical |
| Member list missing for leader assignment | Medium | Add safe member list endpoint |
| New joiner cannot respond to existing proposal | Medium | Backfill pending response rows on join |
| Availability sorted by rank but UI expects time order | Medium | Add `sort=time|rank` |
| Backend exists but Android repository does not | High | Implement typed repository layer before UI |
| Privacy leak through member/user ids | High | Return member id only where needed, never raw user id |
| Finalize creates duplicate personal schedules if retried | Medium | RPC locks pending proposal and changes status before retry can succeed |
| UI becomes too large in one patch | Medium | Split into small apply-script work packages |

---

## 12. Final Target Behavior

A successful MVP should support this full user story:

```text
User A creates an availability group for Saturday afternoon.
The app generates a share code.
User B joins with the share code.
Both users' existing personal schedules are counted as occupied blocks.
User B adds a dummy unavailable block for this group only.
User A sees time slots such as 2/2 available or 1/2 available.
User A proposes a meeting time.
User B accepts.
User A finalizes.
The meeting is inserted as a confirmed personal schedule only for users who accepted.
No member ever sees another member's schedule title, memo, location, or reason for unavailability.
```

---

## 13. Immediate Next Action

Start with:

```text
P1-AG-1 Backend Contract Completion
```

Do not start Android screens until the backend API contract is finalized, because the screens depend on stable list/members/sort behavior.
