# On My Plate Native Planner

Native Android Kotlin MVP for shared-text appointment ingestion.

## Run

Open this folder in Android Studio, let Gradle sync, then run the `app` configuration on a device or emulator.

The app registers an Android `ACTION_SEND` target for `text/plain`. Share text from KakaoTalk, SMS, memo apps, browsers, or any Android app into `On My Plate Planner`; the share receiver parses the text, creates an appointment candidate, and shows a native notification with inline title input and actions.

## MVP Scope

- Native Android Kotlin, Jetpack Compose, Room, coroutines.
- Korean rule-based parser for date/time/location/title extraction.
- Notification actions with `RemoteInput`: `확정`, `예정`, `미정`.
- Local conflict detection using Room schedules.
- Candidate edit and conflict resolution screens.

No KakaoTalk scraping, login, background chat monitoring, web app, cloud sync, or LLM API is included.
