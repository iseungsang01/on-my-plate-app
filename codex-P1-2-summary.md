Implemented P1-2 only.

Changed:
- Added `sourceApp` to candidate storage: [AppointmentCandidateEntity.kt](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/src/main/java/com/lss/onmyplate/nativeplanner/data/entity/AppointmentCandidateEntity.kt:10)
- Stored incoming `sourceApp` during candidate creation and copied it into saved schedules: [PlannerRepository.kt](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/src/main/java/com/lss/onmyplate/nativeplanner/data/repository/PlannerRepository.kt:31)
- Updated Room v1 exported schema directly, with no migration, treating version 1 as pre-deploy per the issue.
- Updated only the P1-2 checkbox and 처리 기록 in [ISSUES.md](C:/Users/lss/Documents/GitHub/on-my-plate-app/ISSUES.md:37)

Verification:
- `:app:assembleDebug` succeeded.
- `Parameter 'sourceApp' is never used` warning is gone.
- Remaining warning is the existing Gradle 9.0 deprecation warning.