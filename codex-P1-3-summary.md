Implemented P1-3 using 선택 B.

Changed:
- [PlannerWidgetStore.java](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/src/main/java/com/lss/onmyplate/nativeplanner/widget/PlannerWidgetStore.java): removed unused `KEY_PENDING_ROUTE` and `savePendingRoute()`.
- [SummaryWidgetProvider.java](C:/Users/lss/Documents/GitHub/on-my-plate-app/app/src/main/java/com/lss/onmyplate/nativeplanner/widget/SummaryWidgetProvider.java): widget root click now opens `MainActivity` directly with no pending route, so it lands on default `Route.Planner`.
- [ISSUES.md](C:/Users/lss/Documents/GitHub/on-my-plate-app/ISSUES.md): updated only P1-3 처리 기록. I did not check off P1-3 because required verification failed.

Verification:
- `git diff --check` passed.
- `.\gradlew.bat :app:assembleDebug` failed because this repo has no `gradlew.bat`, and `gradle` is not available on `PATH`.