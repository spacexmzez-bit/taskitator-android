# Taskitator Android — Phase 2 status

**Branch:** `phase2`  
**Application ID:** `com.mazendev.taskitator`  
**Version:** `2.0.0`

## Phase 2 implementation

Phase 2 builds on the Phase 1 native app and adds Worker-backed AI, Pro, phone-device, and Universe functionality.

- Added an authenticated Worker API client for AI generation, Pro status, provider selection, BYOK preference, phone devices, proof reviews, and Universe endpoints.
- Pro entitlement and BYOK mode are refreshed from the Worker. API keys remain in the encrypted Worker vault; the app directs key management to the verified website flow rather than storing provider secrets locally.
- Added phone-device registration, one-time token display, device listing, and individual revocation. MacroDroid instructions now use the Worker status endpoint with an Authorization bearer header.
- Connected AI criteria validation to the Worker and aligned the native criteria editor's save threshold with the requested 8/10.
- Replaced the proof-audit mock path with real evidence selection, upload, hosted quote acceptance, personal-key review, batch review, and server-side confirmation. The app enforces 8 MiB per photo, 30 MiB per PDF, 5 files, and 40 MiB combined.
- Connected Copilot to the authenticated AI gateway with a current task snapshot. Copilot is explicitly prevented from claiming it performed protected actions.
- Added a native Universe workspace with group creation/joining, membership and roles, invitations, projects, task assignment, all five audit modes, server criteria validation, due dates, proof/reference uploads and downloads, discussions, activity, AI review, lock consent, assignment eligibility, and shorter-notice acceptance.
- Added native project editing, member/reviewer selection, archive/color options and empty-project deletion; task editing, subtasks, assignee/reviewer selection, all/count/specific completion requirements, shield/bonus controls, opening/due/late-cutoff dates, optional late submission, and confirmed close/delete.
- Added a native tag/priority catalog editor and task label selection, discussion message editing/moderation, submission disputes and reopening.
- Added a Worker-authoritative task-only JSON export for Universe owners. This excludes attachments and submission history and is **not** a full backup.
- Added confirmation before deleting or leaving a Universe, owner-verified ownership transfer, group detail editing, pending-invitation cancellation, and retained existing screen data when individual refresh requests fail.
- Added a pull-and-three-way-merge refresh after server-side AI completion so a stale local snapshot is not pushed over the Worker's authoritative completion.
- Updated the Android package ID and version for the Phase 2 build.

## Build verification

GitHub Actions runs:

- `testDebugUnitTest`
- `assembleDebug`
- Source ZIP and debug APK packaging

Successful runs publish the `taskitator-phase2-build` artifact containing the APK and source ZIP. Each new commit triggers its own run; check the latest commit’s run before testing its APK.

## Known gaps — this is not yet full website parity

- Universe task hierarchy reordering and certain advanced administrative controls are not yet exposed natively.
- Full Universe backup (including attachments/history), import/overwrite, and some administrative lifecycle controls still require the website. Android currently exports task-only JSON manifests.
- AI key creation/replacement and account verification still use the website's verified flow; the app does not collect or store raw provider keys.
- Native Clerk sign-in and recovery are not implemented in this phase; the app continues to use its existing authenticated-token flow.
- MacroDroid and phone-lock enforcement have not been end-to-end tested on a physical device. Universe task and catalog features depend on the deployed Worker and its required database migrations (including Phase 6–9 where applicable).
- This is a debug APK for testing, not a Google Play signed release.

Use a disposable test account for first-run testing. Do not use irreplaceable production tasks until device sign-in, sync, evidence review, and phone-lock behavior have been tested end to end.
