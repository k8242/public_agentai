# ThreadLog Feature Plan: Composer Backdating & WhatsApp Export Import

- **Date:** 2026-11-22
- **Type:** Feature
- **Status:** Implemented

This plan adds two complementary ways to add historical entries with exact dates, times, and timezones:

1. **Quick Composer Backdating** — pick any past date, time, and timezone directly in the chat composer when typing/reviewing notes manually.
2. **Native WhatsApp Export Importer (Paste Text or `.txt` File)** — parse WhatsApp chat exports with a specifiable timezone and an interactive review screen before importing.

---

## 1. Quick Backdating in the Chat Composer (`:feature:chat`)

### Data Layer
[`EntryRepository.createEntry`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/contract/RepositoryContracts.kt) already accepts `nowMs: Long` and `zoneId: String?`, computing `localDate`, `isoWeek`, UUIDv7 timestamp ordering, and per-day `dailyOrdinal` from the supplied timestamp and timezone.

### UI & ViewModel Changes
- **[ChatViewModel.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatViewModel.kt)**:
  - Extend `ChatUiState` with `composerCustomTimestampMs`, `composerCustomZoneId`, and `keepBackdateAfterSend`.
  - Add `setComposerBackdate(epochMs, zoneId, keepAfterSend)` and `clearComposerBackdate()`.
  - Pass custom `nowMs` and `zoneId` into `entryRepository.createEntry(...)` on send; if `keepBackdateAfterSend` is enabled, advance the timestamp by `+1 minute` for consecutive entries, otherwise reset to `Now`.
- **[BackdatePickerDialog.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/BackdatePickerDialog.kt)**, **[ChatComposerComponents.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt)** & **[ChatScreen.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)**:
  - Add a compact **Schedule/Backdate** button in the composer action bar opening a tabbed `Date` / `Time` / `Timezone` dialog with a *"Keep date for next note (+1 min each)"* checkbox and *"Reset to Now"* action.
  - Display a removable backdate chip in the composer strip whenever a custom timestamp is active.

---

## 2. WhatsApp Export Parser (`:core:parser`)

Add pure Kotlin parser [WhatsAppExportParser.kt](../../../core/parser/src/main/kotlin/io/github/k8242/threadlog/core/parser/WhatsAppExportParser.kt):
- Supports Android (`8/13/22, 2:11\u202FPM - Yutong: ...`, 12h/24h, narrow no-break space `\u202F`, invisible directional marks) and iOS (`[8/13/22, 2:11:05 PM] Yutong: ...`) formats.
- Reassembles multi-line messages onto the preceding timestamped header.
- Sequential `+1000ms` tie-breaking when consecutive messages share the same minute (no seconds).
- Configurable options:
  - **Specifiable IANA `zoneId`** (defaults to device timezone)
  - **Date order**: US `M/D/YY` (`MDY`) default + `D/M/YY` (`DMY`) toggle with mismatch hint detection
  - **Sender prefix toggle**: strip or keep `Sender: ` prefix live
  - **`<Media omitted>` toggle**: kept by default, with live skip toggle
- Detects system notices (unchecked by default) and validates against [`SensitiveCredentialGuard`](../../../core/parser/src/main/kotlin/io/github/k8242/threadlog/core/parser/SensitiveCredentialGuard.kt).

---

## 3. WhatsApp Import & Interactive Review UI (`:feature:backup`)

Add [WhatsAppImportModels.kt](../../../feature/backup/src/main/kotlin/io/github/k8242/threadlog/feature/backup/ui/WhatsAppImportModels.kt) and [WhatsAppImportComponents.kt](../../../feature/backup/src/main/kotlin/io/github/k8242/threadlog/feature/backup/ui/WhatsAppImportComponents.kt) wired into [BackupViewModel.kt](../../../feature/backup/src/main/kotlin/io/github/k8242/threadlog/feature/backup/ui/BackupViewModel.kt) and [BackupScreen.kt](../../../feature/backup/src/main/kotlin/io/github/k8242/threadlog/feature/backup/ui/BackupScreen.kt):
- Entry points: **Paste Text** dialog and **Import `.txt`** document picker.
- Interactive review card with searchable IANA timezone picker, `US (M/D/YY)` / `Intl (D/M/YY)` chips, `Sender: Stripped / Kept` toggle, `<Media omitted>: Kept / Skipped` toggle, per-row selection, inline edit dialog, per-row TODO toggle, duplicate detection against existing journal entries, and 1-tap `Mask xxxx` / `Mask all` for credential-flagged notes.
- Imports selected notes chronologically via `EntryRepository.createEntry(...)` and schedules auto-tagging.

---

## 4. Verification Plan

1. `WhatsAppExportParserTest` (`:core:parser:test`)
2. `ChatViewModelTest` (`:feature:chat:testDebugUnitTest`)
3. `WhatsAppImportViewModelTest` (`:feature:backup:testDebugUnitTest`)
4. `./gradlew checkLicenseHeaders :core:model:test :core:parser:test testDebugUnitTest :app:lintDebug :app:assembleDebug`
