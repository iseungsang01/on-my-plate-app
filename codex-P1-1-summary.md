Implemented `P1-1` only.

Changed:
- Added JVM parser tests in [KoreanAppointmentParserTest.kt](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/src/test/java/com/lss/onmyplate/nativeplanner/domain/parser/KoreanAppointmentParserTest.kt)
- Added `junit:junit:4.13.2` in [app/build.gradle.kts](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/build.gradle.kts)
- Narrow parser fixes in [KoreanAppointmentParser.kt](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/src/main/java/com/lss/onmyplate/nativeplanner/domain/parser/KoreanAppointmentParser.kt):
  - explicit `이번 주/다음 주 + 요일` now anchors to week start
  - location extraction no longer includes preceding date/time text
- Updated only `P1-1` checkbox and `처리 기록` in [ISSUES.md](C:/Users/lss/Documents/GitHub/on-my-plate-app/ISSUES.md)

Verification:
- `:app:testDebugUnitTest` passed

Observed existing warnings:
- `PlannerRepository.kt` `sourceApp` unused warning
- Room kapt option warning
- Gradle 9.0 deprecation warning

Unrelated pre-existing dirty file left untouched: `scripts/run-issues.ps1`.