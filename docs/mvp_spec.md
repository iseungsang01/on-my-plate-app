# MVP Spec

## Core Problem

Users receive appointment details across chat, SMS, notes, browsers, and other apps, then have to reread and reorganize those messages before they become usable calendar plans.

## Core Value

The MVP turns shared or typed appointment text into appointment candidates, keeps those candidates in a basket, and lets the user confirm them later into a weekly schedule.

## Included Scope

- Receive external Android `ACTION_SEND` text shares.
- Accept internal text entry for appointment messages.
- Parse shared or typed text into appointment candidates.
- Keep unconfirmed candidates in the basket.
- Show native notifications for newly received candidates.
- Let users edit candidate title, time, location, status, and save or discard.
- Detect conflicts before saving and allow explicit resolution.
- Show saved appointments in the weekly schedule view.
- Let users review recently saved appointments from the basket.

## Hidden / Deferred Scope

- Collaboration and sharing tab workflows.
- Widget improvements beyond current schedule snapshot behavior.
- Advanced recurrence UX or recurrence API expansion.
- Supabase server schema expansion.
- Broad redesign work outside the candidate-to-weekly-schedule flow.

## Success Criteria

- A user can share appointment text from another Android app and get a candidate plus notification.
- A user can paste or type appointment text in the app and get the same candidate review flow.
- A user can edit ambiguous candidate details before saving.
- A saved candidate appears in the weekly schedule.
- The main app surface stays focused on `일정`, `바구니`, and `설정`.
