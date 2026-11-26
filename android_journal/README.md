# ThreadLog (`android_journal/`)

## Overview & Key Features

**ThreadLog** is an offline-first, WhatsApp-style native Android journaling app built with **Kotlin, Jetpack Compose, Material 3, Room (SQLite + Bundled FTS5), Paging 3, WorkManager, and Hilt**.

Key capabilities:
- **Chat-Stream Capture**: Reverse-anchored Paging 3 feed with write-time IANA timezone anchoring, 4-tier hierarchical date headings (Year / Month / Week / Day), floating sticky period pill, edit (preserving original timestamp), soft-delete tombstones with Undo, and swipe-to-quote replies.
- **Messaging Formatting, Links & Compact Images**: Lightweight messaging formatting (`*bold*`, `_italic_`, `~strike~`, `` `code` ``, `> quote`, fenced ```` ```code``` ````), automatic URL extraction into collapsible domain chips with async page `<title>` fetching (plus an offline-only toggle), and pasted/attached images auto-compressed to `<= 640 KB` (`655,360` bytes).
- **Hierarchical Tag Trees & Zero-Clutter Auto-Tags**: Multi-root tag trees (`people/*`, `topics/*`, `projects/*`, `auto/*`) rendered leaf-only (`Lex`) with automatic minimal-parent collision disambiguation (`Alex (work)`), inline `@` / `#` autocomplete, quick-tag picker, and an offline rule/alias/domain auto-tagger (plus pluggable `AutoTagProvider` SPI) whose suggestions remain hidden by default until surfaced via toggle, search, or the Auto-Tag Review Drawer (with Promote / Reject tombstone overrides).
- **Compact Weekly, Calendar & Two-Tier Hybrid Search**: Compact Weekly 1-line truncated view with ellipses and inline expand; monthly Calendar view with density dots and tag-subtree filter; FTS5 full-text + hidden auto-tag search split into Tier A (`<= 5 weeks` in-feed stepper) and Tier B (`> 5 weeks` compact result rows opening an interactive `±12h` context window).
- **Two-Way Backup & Restore**: 3-tab Google Sheets workbook (`Entries`, `TagTree`, `SyncMeta`) + companion `ThreadLog_Media/` Google Drive folder (`drive.file` scope) with mandatory 3-way Dry-Run Diff Preview & per-conflict merge, plus zero-cloud-setup offline CSV, XLSX, and JSON+Media `.zip` exports/imports via Android SAF.

## Documentation & Architecture Guide

- **[ThreadLog User & Architecture Manual](docs/ThreadLog_User_and_Architecture_Manual.md)**: Exhaustive manual detailing all user features, messaging formatting syntax, 10-module clean architecture, Room SQLite FTS5 database design, Paging 3 fixed placeholder stability, 3-way sync engine, and pending implementation analysis.
- **[Interactive Filterable System Glossary](docs/system_glossary.html)**: Standalone, searchable HTML tool to query formatting tokens, domain entities, architectural abstractions, and security guardrails.
- **[Planning Artifacts](docs/plans/README.md)**: Chronological feature, bug-fix, and batch plans.

## Module Architecture

The project is organized into 10 Gradle modules with **zero feature-to-feature dependencies**:

| Module | Type | Responsibility |
| :--- | :--- | :--- |
| `:core:model` | Pure JVM (`java-library`) | Frozen domain models, enums, parser AST, service/repository contracts, and `@Serializable ThreadLogRoute` |
| `:core:parser` | Pure JVM | WhatsApp single-pass formatter, `body_plain` stripper, URL extractor, `@`/`#` trigger detector, `LeafDisambiguator`, UUID v7, hashing, date/week calculator |
| `:core:media` | Android Library | `<= 640 KB` EXIF-aware image downscaler/compressor and atomic SHA-256 local media storage |
| `:core:data` | Android Library | Room `2.8.5` + `BundledSQLiteDriver` (real SQLite FTS5), DAOs, Paging 3, repositories, `BackupDataGateway`, DataStore settings |
| `:feature:autotag` | Android Library | `OfflineRuleAutoTagger`, `@HiltWorker AutoTagWorker`, `AutoTagScheduler`, and Auto-Tag Review Drawer |
| `:feature:chat` | Android Library | Reverse-anchored Chat Stream, 4-tier date dividers, sticky pill, composer (`ReceiveContent` image paste, Photo Picker, `@`/`#` autocomplete, quick-tag bar/sheet), bookmarks + `BookmarkTitleWorker` |
| `:feature:views` | Android Library | Compact Weekly view and Calendar view with density dots and tag-subtree filter |
| `:feature:search` | Android Library | Two-tier Hybrid Search (Tier A `<= 5w` stepper + Tier B `> 5w` rows) and interactive `±12h` Context Window |
| `:feature:backup` | Android Library | Pure-OkHttp Google Sheets v4 & Drive v3 clients, `SyncDiffEngine`, CSV/XLSX/ZIP codecs, `ScheduledBackupWorker`, Backup & Dry-Run Diff UI |
| `:app` | Android Application | `ThreadLogApplication` (`HiltWorkerFactory`), `MainActivity`, Material 3 theme, 5-tab navigation host, Settings, and `BuildConfig` wiring |

## Prerequisites & Local Build / Test Commands

- **JDK**: OpenJDK `17`
- **Android SDK**: `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35` (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`)

Run from `android_journal/`:

```bash
# Run all unit tests (pure-JVM + Android Debug)
./gradlew :core:model:test :core:parser:test testDebugUnitTest

# Run Android Lint
./gradlew :app:lintDebug

# Build installable Debug APK (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug
```

## Google Cloud OAuth Setup (Optional — for Google Sheets & Drive Backup)

> **Offline backup works immediately with zero cloud setup.** Exporting and restoring via CSV, XLSX, or JSON+Media `.zip` through Android's Storage Access Framework (SAF) requires no Google Cloud project or OAuth configuration.

To enable optional two-way Google Sheets + `ThreadLog_Media/` Drive backup:

1. Create or select a project in the [Google Cloud Console](https://console.cloud.google.com/) and enable both the **Google Sheets API** and **Google Drive API**.
2. Configure the **OAuth consent screen** and request only the minimal, non-restricted scope:
   `https://www.googleapis.com/auth/drive.file`
3. Create an **Android OAuth 2.0 Client ID** matching package name `io.github.k8242.threadlog` and your debug/release signing keystore's SHA-1 fingerprint (inspectable locally via `./gradlew :app:signingReport`), plus a **Web Application OAuth 2.0 Client ID** if requesting offline tokens.
4. Provide your OAuth Web Client ID locally via **gitignored** `android_journal/local.properties`:
   ```properties
   GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
   ```
   or via the `GOOGLE_WEB_CLIENT_ID` environment variable. Gradle injects this value into `BuildConfig.GOOGLE_WEB_CLIENT_ID` and defaults safely to `""` on fresh clones and CI.

## Public-Repository Security Notice

`k8242/public_agentai` is a **public repository**. **NEVER** commit `local.properties`, `google-services.json`, `client_secret*.json`, `.keystore`/`.jks`/`.p12`/`.pem` files, signing properties, or personal OAuth client IDs/secrets. All of these patterns are excluded by `android_journal/.gitignore`.

## CI / Automated Debug APK Builds

GitHub Actions workflow [`.github/workflows/android-journal-ci.yml`](../.github/workflows/android-journal-ci.yml) is path-filtered to `android_journal/**` and runs on pushes/PRs to `main` (and via manual `workflow_dispatch`). Each run executes all pure-JVM and Android Debug unit tests, runs `:app:lintDebug`, builds `app-debug.apk`, and uploads the installable `threadlog-debug-apk` artifact (plus test/lint reports) on the workflow run summary page.

## License, Attribution & Trademarks

- **Code License**: MIT — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). All source and binary redistributions must retain the copyright notice and MIT permission notice.
- **Brand & Trademarks**: The **ThreadLog** name, app icon, and `io.github.k8242.threadlog` package identity are reserved under [`TRADEMARKS.md`](TRADEMARKS.md). Forks and redistributed builds must rebrand while preserving MIT attribution.

