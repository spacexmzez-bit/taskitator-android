# Taskitator Android — Phase 1 source package

**Target:** `spacexmzez-bit/taskitator-android` GitHub Actions workflow, which expects a ZIP named `taskitator-source.zip` containing `settings.gradle.kts` and `app/build.gradle.kts` at its root.

## Changes included

- Connected existing native Calendar, Statistics, and Trash screens to the drawer.
- Replaced tag-only "Projects" display with project records from the synced `projects` collection, including create, rename, move-to-Inbox delete, and per-task project selection.
- Added native task drafts stored in synced `settings` keys (`task_draft:<id>` and `edit_draft:<id>`), a drafts list, and a Save Draft action in the task editor.
- Added a Scratchpad screen with local notes and conversion to tasks.
- Fixed Today's Focus filtering to exclude unscheduled backlog tasks.
- Added priority, alphabetical, and required-first sorting.
- Added Worker-backed break controls (`GET/POST /breaks`) for Scheduled, Flexible, Reserved, and basic Strict settings. All state is refreshed from the server; no local break schedule is treated as authoritative.
- Added Worker-backed Sleep Lock (`GET/POST /sleep-lock`), including server revision and 30/60-minute delay actions.
- Emergency Bypass now uses the Worker's `/emergency` endpoint; disabled local bypass and schedule activation shortcuts.
- Added fullscreen landscape Pomodoro with keep-screen-on behavior while the fullscreen dialog is open.
- Added Arabic RTL navigation and a device-only language toggle; added a partial native Nature theme preview.
- Improved local sync safety: dirty-state persistence, pull-only when no edits, and merge of projects and per-key settings (including drafts) when rebasing after a 409.
- Prevented a protected parent from silently completing unaudited AI-locked descendants.
- Removed the hardcoded nonexistent debug keystore; GitHub Actions can use AGP's default debug signing.
- Added pure Kotlin contract tests for project defaults, sorting, draft keys, and break action names.

## Not yet complete — do not treat as full website parity

- Study Plan UI is **preview-only**. The Worker requires a `/study-plan` validation/authorization grant and exact server-generated recurrence tasks; this build intentionally does **not** submit unauthorized generated plans.
- Native recurring task authoring, Smart Study distribution and dynamic-priority editing are not complete. Existing opaque recurrence metadata is preserved in snapshots.
- Break UI supports one timed Scheduled interval and one Reserved window per save; the website supports up to three timed and two reserved intervals, plus more detailed weekday-specific Strict settings.
- Arabic localization is partial (navigation/RTL), not a translation of every screen. Nature styling is a preview, not pixel-perfect parity with the website's full themes.
- Native Clerk login is not implemented: the existing bearer-token sign-in remains. Full mobile auth and account recovery belong to the remaining integration work.
- Phone-bridge setup still relies on the existing Settings screen; the website's complete MacroDroid installation guide is not reproduced.
- Local scratchpad notes still expire after 48 hours and are not synced. No permanent notes archive was added.
- The existing AI/Pro features are outside Phase 1 and remain subject to the next build.

## Testing and risk

- Verified ZIP root layout and XML parsing.
- Compiled the pure Kotlin task models and sorting utility and ran a passing smoke test.
- **Did not run Gradle, Android unit tests, APK assembly, or device sign-in/sync**, because Android SDK/Gradle dependencies are not available in the current environment.
- **No Cloudflare Worker, D1, Keyo, or GitHub repository changes were made.** This ZIP only modifies the Android source.
- Use a disposable test account for the first cloud build and sync tests. Do not test with irreplaceable production tasks until GitHub Actions and end-to-end sync pass.

## GitHub Actions

1. Upload this ZIP as `taskitator-source.zip` at the root of `spacexmzez-bit/taskitator-android` and commit.
2. GitHub Actions runs `testDebugUnitTest assembleDebug` using the existing workflow.
3. If green, download the `taskitator-debug-apk` artifact and extract `app-debug.apk`.
4. If red, inspect the first failure under **Run unit tests and build debug APK**.
