# Taskitator Android

Android-only development repository for **Taskitator**. The website and Cloudflare Worker are not deployed from this repository.

## Build an APK entirely from your phone

The Android source is provided as a ZIP in ChatGPT. Upload that ZIP once, then GitHub Actions builds the installable debug APK for you.

1. Download the **latest Taskitator Android source ZIP** from the ChatGPT conversation to your phone.
2. In this GitHub repository, tap **Add file → Upload files**. On mobile, enable **Desktop site** in your browser if the upload option is hidden.
3. Rename the downloaded ZIP to **`taskitator-source.zip`** and upload it to the **root of this repository** (not inside a folder). Commit to `main`.
4. GitHub Actions starts the **Build Android Debug APK** workflow automatically.
5. Go to **Actions → Build Android Debug APK → latest run**. Wait for a green checkmark.
6. Under **Artifacts**, download **`taskitator-debug-apk`**. This downloads a ZIP containing **`app-debug.apk`**.
7. Extract the artifact ZIP on your Android phone and open **`app-debug.apk`** using the Files app. Approve installation from that app if prompted.

Direct link: https://github.com/spacexmzez-bit/taskitator-android/actions/workflows/android-debug.yml

The workflow runs Java 21, Android SDK 36, Gradle 9.8.2, unit tests, and APK compilation. It publishes an APK **only if the build succeeds**.

## Source ZIP format

The source ZIP must contain these paths at its root (no extra containing folder):

```text
taskitator-source.zip
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
└── app/
    ├── build.gradle.kts
    └── src/
```

## Status and safety

**Milestone 1 is not yet accepted.** The latest recovery source has not passed compilation, device sign-in, or end-to-end synchronization tests. The first cloud build may expose errors that need fixing.

- Use a **disposable test account**. Do not test sync with important production tasks.
- Native Clerk authentication may need a separately reviewed Worker JWT authorized-party adjustment; this repository does not modify the Worker.
- If a workflow run fails, open the failed step **Run unit tests and build debug APK** and share its error log.
- Never upload private keys, API secrets, or session tokens to this public repository.
- Debug APK is for testing. Google Play release later requires a signed AAB.

