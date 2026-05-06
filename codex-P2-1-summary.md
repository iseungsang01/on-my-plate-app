Implemented `P2-1` only.

Changed:
- `app/build.gradle.kts`: release signing and Play credentials are no longer required during debug Gradle configuration.
- `.env.example`: separated debug-safe values from release signing and Play publish values.
- `README.md`: documented debug vs release/publish env requirements.
- `ISSUES.md`: checked off `P2-1` and added 처리 기록.

Verification:
- Empty release env / `.env` absent: `:app:assembleDebug` succeeded.
- Empty release env: `:app:assembleRelease` failed with the expected signing env message.
- Dummy signing env but no Play credential: `:app:publishAab` failed with the expected `PLAY_SERVICE_ACCOUNT_JSON_PATH` message.
- `git diff --check` passed, with only existing LF-to-CRLF warnings from Git.