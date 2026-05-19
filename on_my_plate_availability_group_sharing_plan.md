# On My Plate — Availability Group Sharing Plan

## Purpose

Build a group-based availability matching feature for On My Plate.

This is not a general “planner sharing” feature.  
The goal is to let app users join the same group, compare their occupied time blocks over selected dates, find time slots where the maximum number of people are available, and create shared schedule proposals.

---

## Core Product Definition

Availability Group Sharing allows users to:

1. Create a group for scheduling coordination.
2. Invite other app users through a share code.
3. Select one or more dates or a date range.
4. Overlay all members’ schedules as hidden busy blocks.
5. Calculate how many members are available for each time slot.
6. Suggest a meeting time even if not everyone is available.
7. Let members accept or reject the proposal.
8. Let the owner finalize the proposal.
9. Insert the finalized schedule only into the calendars of members who accepted.

---

## Fixed Product Decisions

| Item | Decision |
|---|---|
| Sharing target | App users only |
| Join method | Share code |
| Anonymous/public link | Not supported |
| Group creator | Automatically joins as owner and member |
| Default search time range | 08:00–24:00 |
| Default slot size | 1 hour |
| Slot size | User-configurable |
| Busy rule | Every existing schedule counts as busy |
| Dummy schedule | Exists per group |
| Default visibility | `busy_only` |
| Schedule title visibility | Hidden by default |
| Schedule description/location visibility | Hidden by default |
| Display style | Show only that someone has an occupied block; do not expose details |
| Proposal condition | Proposal is allowed even when not everyone is available |
| Proposal availability display | Show `n/m available`, e.g. `3/4 available` |
| Unavailable members | Still receive the proposal and can respond |
| Finalize permission | Owner only |
| Schedule creation after finalize | Only for members who accepted |
| Created schedule title | Use `proposal.title` unchanged |

---

## Visibility Rule

Default visibility mode is `busy_only`.

In `busy_only` mode:

- Do not expose schedule title.
- Do not expose description.
- Do not expose location.
- Do not expose whether the block is from a real schedule or dummy schedule.
- Do not expose the reason for unavailability.
- UI should only imply that the time is occupied.

The UI should avoid strong labels like “busy” where possible.  
A block, shaded cell, or occupancy indicator is enough.

Example display:

```text
10:00–11:00    4/4 available
11:00–12:00    3/4 available
12:00–13:00    2/4 available
```

---

# P0 — Core Availability Matching

## Goal

Allow users to create a coordination group, join it with a share code, and view overlapping availability across members.

P0 does not include proposals, finalize, dummy schedules, comments, or leader mode.

---

## P0-1. Create Availability Group

### Requirements

A logged-in user can create an availability group.

The group must include:

- Group title
- Date or date range
- Slot size
- Search start time
- Search end time
- Share code
- Owner

### Defaults

```text
slot_minutes = 60
search_start_time = 08:00
search_end_time = 24:00
visibility_mode = busy_only
suggestion_mode = everyone
status = active
```

### Slot Size Options

Minimum initial options:

- 30 minutes
- 60 minutes
- 120 minutes

Default:

```text
60 minutes
```

### Creator Behavior

When a user creates a group:

1. Create the group.
2. Generate a unique share code.
3. Insert the creator into `availability_group_members`.
4. Assign creator role as `owner`.

---

## P0-2. Join Group by Share Code

### Requirements

A logged-in user can join an availability group by entering a share code.

Rules:

- User must be authenticated.
- Share code must exist.
- Group must be active.
- User must not already be a member.
- Joined user receives role `member`.

No public or anonymous access is allowed.

---

## P0-3. Compute Busy Blocks

### Requirements

For each group member:

1. Load all schedules within the group date range.
2. Treat every schedule as busy.
3. Hide all schedule details from other users.
4. Return only occupancy information needed for availability calculation.

### Busy Rule

```text
Any existing schedule with a time range = busy
```

Do not distinguish between schedule categories in P0.

---

## P0-4. Calculate Availability Slots

### Requirements

The system divides each selected date into slots.

Default search range:

```text
08:00–24:00
```

Default slot size:

```text
60 minutes
```

For each slot:

1. Count total group members.
2. Count unavailable members.
3. Compute available members.

Formula:

```text
available_count = total_members - unavailable_count
```

The response must include:

- Slot start time
- Slot end time
- Available count
- Total count
- Unavailable count

### Example Output

```json
{
  "groupId": "uuid",
  "slotMinutes": 60,
  "totalMembers": 4,
  "slots": [
    {
      "startsAt": "2026-05-23T10:00:00+09:00",
      "endsAt": "2026-05-23T11:00:00+09:00",
      "availableCount": 4,
      "totalCount": 4,
      "unavailableCount": 0
    },
    {
      "startsAt": "2026-05-23T14:00:00+09:00",
      "endsAt": "2026-05-23T15:00:00+09:00",
      "availableCount": 3,
      "totalCount": 4,
      "unavailableCount": 1
    }
  ]
}
```

---

## P0 Acceptance Criteria

P0 is complete when:

1. User A can create an availability group.
2. A share code is generated.
3. User A is automatically registered as owner/member.
4. Users B and C can join using the share code.
5. The system reads all members’ schedules inside the selected date range.
6. Every existing schedule is treated as busy.
7. The group availability view shows `n/m available` per slot.
8. Default search range is 08:00–24:00.
9. Default slot size is 1 hour.
10. No schedule title, description, or location is exposed.

---

# P1 — Suggestion and Dummy Schedule

## Goal

Allow users to adjust their availability per group, suggest a time slot, respond to proposals, and let the owner finalize accepted schedules.

---

## P1-1. Group-Specific Dummy Schedule

### Requirements

A user can create dummy schedule blocks for a specific availability group.

A dummy schedule:

- Belongs to one group.
- Belongs to one user.
- Does not appear in the user’s normal planner.
- Counts as busy only inside that group.
- Can have a private note visible only to the creator.
- Is indistinguishable from a real busy block to other users.

### Example

```text
Group A:
User blocks Saturday 10:00–12:00.

Group B:
Same user remains available Saturday 10:00–12:00.
```

### Rules

```text
dummy_schedule.group_id is required
dummy_schedule.user_id is required
dummy_schedule affects only that group
dummy_schedule.note is private
```

---

## P1-2. Create Proposal

### Requirements

A group member can create a proposal from a selected time slot.

A proposal must include:

- Group ID
- Proposer ID
- Title
- Start time
- End time
- Available count at creation time
- Total count at creation time
- Status

Proposal is allowed even if not everyone is available.

Example:

```text
Title: Project Meeting
Time: 2026-05-23 14:00–15:00
Availability: 3/4 available
```

Unavailable members still receive the proposal and can respond.

---

## P1-3. Respond to Proposal

### Requirements

Each group member can respond to a proposal.

Response states:

| State | Meaning |
|---|---|
| `pending` | No response yet |
| `accepted` | User accepts the proposed schedule |
| `rejected` | User rejects the proposed schedule |

Rules:

- A user can only update their own response.
- Other users’ responses cannot be modified.
- The proposal remains valid even if some users reject it.

---

## P1-4. Owner Finalize

### Requirements

Only the group owner can finalize a proposal.

When finalized:

1. Find all members whose response is `accepted`.
2. Create a real schedule only for those accepted members.
3. Do not create schedules for `rejected` or `pending` members.
4. Set proposal status to `finalized`.
5. Set `finalized_at`.

### Schedule Creation Rule

The created schedule title must use the proposal title unchanged.

```text
schedule.title = proposal.title
```

### Example

```text
Proposal:
Title: Project Meeting
Time: May 23, 14:00–15:00

Responses:
A: accepted
B: accepted
C: rejected
D: pending

Owner finalizes.

Result:
A gets schedule "Project Meeting"
B gets schedule "Project Meeting"
C gets no schedule
D gets no schedule
```

---

## P1 Acceptance Criteria

P1 is complete when:

1. A user can create a group-specific dummy schedule.
2. Dummy schedules affect only the selected group.
3. Dummy schedules are included in availability calculation.
4. Dummy schedule notes are visible only to the creator.
5. A user can propose a time slot even if only some members are available.
6. Proposal cards show `n/m available`.
7. Members can accept or reject proposals.
8. Owner only can finalize proposals.
9. Finalization creates real schedules only for accepted members.
10. Created schedule title equals `proposal.title`.

---

# P2 — Team Control and Collaboration Layer

## Goal

Improve group control, permissions, visibility options, and collaboration after P0 and P1 are stable.

P2 is not required for the first functional MVP.

---

## P2-1. Leader Role

### Requirements

Owner can assign a member as leader.

Roles:

| Role | Permissions |
|---|---|
| `owner` | Manage group, manage members, finalize proposals |
| `leader` | Create proposals depending on suggestion mode |
| `member` | View availability and respond to proposals |

Finalize permission remains owner-only unless explicitly changed later.

---

## P2-2. Suggestion Mode

### Requirements

Group can control who may create proposals.

Modes:

| Mode | Meaning |
|---|---|
| `everyone` | Every member can create proposals |
| `owner_only` | Only owner can create proposals |
| `leader_only` | Owner and leaders can create proposals |

Default:

```text
suggestion_mode = everyone
```

---

## P2-3. Visibility Settings

### Requirements

The group can support different visibility modes later.

Modes:

| Mode | Visible Data |
|---|---|
| `busy_only` | Only occupied time blocks |
| `title_visible` | Occupied blocks plus schedule titles |
| `detail_visible` | Title, description, and location |

Default must remain:

```text
visibility_mode = busy_only
```

---

## P2-4. Proposal Comments

### Requirements

Add lightweight comments on proposals before implementing full group chat.

Proposal comments are preferred over full chat because they keep discussion tied to a specific scheduling decision.

Example comments:

```text
“I can join 30 minutes late.”
“Can we move this to 15:00?”
“This time looks best.”
```

Priority:

```text
proposal comments first
group chat later
```

---

## P2-5. Ranking Improvements

### Requirements

Initial ranking can sort by available count.

Later ranking may consider:

- Available count
- Whether owner is available
- Whether leaders are available
- Preferred hours
- Repeatedly rejected time slots

Example ranking:

```text
1. 5/5 available
2. 4/5 available and owner available
3. 4/5 available but owner unavailable
```

---

## P2 Acceptance Criteria

P2 is complete when:

1. Owner can assign leaders.
2. Group supports suggestion modes.
3. Visibility setting exists but defaults to `busy_only`.
4. Proposal comments work.
5. Availability suggestions have improved ranking beyond simple available count.

---

# Minimal Data Model

## P0 Tables

### `availability_groups`

Required fields:

```text
id
owner_id
title
share_code
scope_start
scope_end
slot_minutes
search_start_time
search_end_time
visibility_mode
suggestion_mode
status
created_at
updated_at
```

Important defaults:

```text
slot_minutes = 60
search_start_time = 08:00
search_end_time = 24:00
visibility_mode = busy_only
suggestion_mode = everyone
status = active
```

---

### `availability_group_members`

Required fields:

```text
id
group_id
user_id
role
joined_at
```

Allowed roles:

```text
owner
leader
member
```

P0 only needs:

```text
owner
member
```

---

## P1 Tables

### `availability_dummy_schedules`

Required fields:

```text
id
group_id
user_id
starts_at
ends_at
note
created_at
```

Rules:

```text
starts_at < ends_at
note is private to creator
```

---

### `availability_proposals`

Required fields:

```text
id
group_id
proposer_id
title
starts_at
ends_at
available_count
total_count
status
created_at
finalized_at
```

Allowed statuses:

```text
proposed
finalized
cancelled
```

---

### `availability_proposal_responses`

Required fields:

```text
id
proposal_id
user_id
response
created_at
updated_at
```

Allowed responses:

```text
pending
accepted
rejected
```

---

# Minimal API Surface

## P0 APIs

### `POST /availability-groups`

Create an availability group.

Must:

- Create group.
- Generate share code.
- Insert creator as owner/member.

---

### `POST /availability-groups/join`

Join a group by share code.

Must:

- Require authentication.
- Reject invalid share code.
- Reject inactive group.
- Prevent duplicate membership.

---

### `GET /availability-groups/:groupId`

Fetch group metadata.

Must:

- Require group membership.
- Return group information and member list.
- Not expose schedule details.

---

### `GET /availability-groups/:groupId/availability`

Fetch calculated availability.

Must:

- Require group membership.
- Use group date range.
- Use group search time range.
- Use group slot size.
- Count all member schedules as busy.
- Return `n/m available` slot data.
- Hide schedule title, description, and location.

---

## P1 APIs

### `POST /availability-groups/:groupId/dummy-schedules`

Create dummy schedule for current user in group.

Must:

- Require group membership.
- Create dummy block only for this group.
- Not create normal planner schedule.

---

### `DELETE /availability-groups/:groupId/dummy-schedules/:dummyId`

Delete dummy schedule.

Must:

- Allow only creator to delete their own dummy schedule.

---

### `POST /availability-groups/:groupId/proposals`

Create a proposal.

Must:

- Require group membership.
- Allow proposal even if not all members are available.
- Store available count and total count at creation time.
- Create pending responses for group members.

---

### `POST /availability-proposals/:proposalId/respond`

Respond to proposal.

Must:

- Require proposal group membership.
- Allow current user to update only their own response.
- Accept only `accepted` or `rejected`.

---

### `POST /availability-proposals/:proposalId/finalize`

Finalize proposal.

Must:

- Allow owner only.
- Create real schedules only for accepted members.
- Use `proposal.title` as schedule title.
- Mark proposal as finalized.

---

# Implementation Priority

## First: P0

Implement:

```text
availability_groups
availability_group_members
group creation
share code join
availability calculation
busy_only output
```

P0 success means the app can answer:

```text
“Among this group, who is available at what time?”
```

---

## Second: P1

Implement:

```text
group-specific dummy schedules
proposal creation
proposal responses
owner finalize
accepted-member schedule creation
```

P1 success means the app can answer:

```text
“Can we propose and confirm a shared schedule from the availability result?”
```

---

## Third: P2

Implement:

```text
leader role
suggestion mode
visibility settings
proposal comments
ranking improvements
```

P2 success means the app can answer:

```text
“Can this group be managed like a team?”
```

---

# Final Summary

P0 makes shared availability visible.

P1 makes shared scheduling actionable.

P2 makes the group controllable and collaborative.

This feature should be built as an availability matching system, not as a general planner sharing system.
