# ThreadLog Feature Plan: Emoji Reactions, Hex-Ordinal Timestamps & `+project` Tagging

- **Date:** 2026-11-22
- **Type:** Feature
- **Status:** Implemented (`ab0e206`)

This plan covers emoji reaction support, upper-left timestamps with per-day 0-based hexadecimal ordinals, and `+project` inline tagging across `:core:model`, `:core:parser`, `:core:data`, `:feature:chat`, `:feature:backup`, and `:app`.

---

## 1. Emoji Reaction Support on Notes/Messages (Req #1)

### Data Model & Room Migration (`:core:model`, `:core:data`, `:feature:backup`)
- **Domain model**: Add `val reactions: List<String> = emptyList()` to [`Entry`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/DomainModels.kt) (ordered unique emoji strings — since ThreadLog is a personal journal, tapping an emoji toggles it on/off on that note, and multiple distinct emojis can coexist on a note).
- **Database (v2 -> v3)**:
  - Add `@ColumnInfo(name = "reactions_json", defaultValue = "[]") val reactionsJson: String = "[]"` to [`EntryEntity`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/local/entity/Entities.kt).
  - Bump [`ThreadLogDatabase`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/local/ThreadLogDatabase.kt) to `version = 3` with non-destructive `MIGRATION_2_3` (`ALTER TABLE entries ADD COLUMN reactions_json TEXT NOT NULL DEFAULT '[]'`), registered in [DataDiModule.kt](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/di/DataDiModule.kt), and export `schemas/.../3.json`.
- **Repository**: Add `toggleReaction(entryId: String, emoji: String, nowMs: Long = System.currentTimeMillis())` to [`EntryRepository`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/contract/RepositoryContracts.kt) and [`DefaultEntryRepository`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt).
- **Backup & Sync**: Add backward-compatible `reactions` column (`List<String> = emptyList()`) to [`SheetEntryRow`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/SyncModels.kt), [`WorkbookMapper`](../../../feature/backup/src/main/kotlin/io/github/k8242/threadlog/feature/backup/sync/WorkbookMapper.kt), [`DefaultBackupDataGateway`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultBackupDataGateway.kt), and `EntryContentHasher` (only included in the hash when non-empty so existing hashes remain compatible).

### Chat UI (`:feature:chat`)
- **Quick Reaction Picker**:
  - Long-pressing a note or opening its overflow menu exposes a WhatsApp-style quick-emoji strip (`👍 ❤️ 😂 😮 😢 🙏 🔥 ⭐ ✅ 🎯 💡 📌`) plus a `+` button that opens a compact custom-emoji input dialog so any emoji from the Android keyboard can be added.
  - Active emojis on the note are highlighted in the quick strip for 1-tap removal.
- **Reaction Pills on Bubble**:
  - Render active reactions as compact rounded pills along the bottom-start of [`MessageBubbleItem`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt), plus a subtle `+` add-reaction pill when reactions exist. Tapping any reaction pill toggles it off.

---

## 2. Upper-Left Timestamps with 0-Based Hexadecimal Ordinal (Req #2)

### Layout & Visual Design (`:feature:chat`)
- Move the timestamp (and optional `Edited` indicator) out of the bottom-right footer of [`MessageBubbleItem`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt) into the **upper-left header row**, opposite the `MoreVert` overflow icon on the upper right.
- Prefix the timestamp with a monospace 0-based hexadecimal ordinal, e.g.:
  - `0x0 · 8:15 AM`, progressing `0x0, 0x1, ..., 0x9, 0xA, ..., 0xF, 0x10, ...` in chronological order per day (first note of each day = `0x0`).
  - If the note is also a `TODO`/`DONE`, place the compact `TODO`/`DONE` pill neatly beside the upper-left header metadata.

### Ordinal Computation (`:core:model`, `:core:data`)
- Add `val dailyOrdinal: Int = 0` to [`EntryWithDetails`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/DomainModels.kt) (hydrated inside [`EntryHydrator`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/EntryHydrator.kt) so it works consistently with Paging 3 and `reverseLayout = true` without shifting backwards).
- **Selected scope**: **Per-day 0-based ordinal** resetting to `0x0` each local day.

---

## 3. Projects Tagging Symbol (`+project`) & Inline Tagging (Req #3)

### Prior Behavior (Why Projects had no dedicated symbol)
Previously [`DefaultMessageParser.detectTagTrigger`](../../../core/parser/src/main/kotlin/io/github/k8242/threadlog/core/parser/DefaultMessageParser.kt) only detected two inline triggers:
- `@` -> filters autocomplete to **People** (`people/*`).
- `#` -> searches **all** existing tags (including `topics/*` and `projects/*`).

There was no dedicated symbol for Projects, and users had to first create a `projects/<name>` tag in the Quick Tag Picker sheet before `#` autocomplete would suggest it.

### Planned Addition: `+` Symbol for Projects (and Inline Tag Auto-Create)
- **Add `+` as the dedicated Projects symbol** (following standard `@person`, `#topic`, `+project` conventions; avoids all WhatsApp formatting markers `*`, `_`, `~`, `` ` ``, `>`):
  - Add `TagTriggerKind.PROJECT_PLUS` to [`ParserModels.kt`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/parser/ParserModels.kt) and detect `+` at valid word boundaries in [`DefaultMessageParser`](../../../core/parser/src/main/kotlin/io/github/k8242/threadlog/core/parser/DefaultMessageParser.kt) (so arithmetic like `2+2` never triggers).
  - In [`ChatViewModel`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatViewModel.kt), typing `+` filters live autocomplete suggestions to `TagRootCategory.PROJECTS`.
  - **Inline tag creation on send & autocomplete**:
    - Show a quick **"Create `+name`"** (or `@name` / `#name`) action row in the autocomplete overlay when typing a new tag.
    - When sending a note containing inline `+project`, `@person`, or `#topic` tokens outside code blocks/URLs, automatically resolve or create (`projects/<name>`, `people/<name>`, `topics/<name>`) and attach them as `USER` tags so they immediately appear in the left sidebar.
  - **Consistent `+` display**: Render `PROJECTS` tags with the `+` prefix in [`ThreadLogDrawer`](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/ThreadLogDrawer.kt) and [`CompactChatHeaderBar`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt) (e.g., `+journal-app`), and update the composer placeholder to `"Write a note... (@people, #topics, +projects)"`.

---

## Verification Plan
1. **Parser tests (`:core:parser`)**: `+project` trigger detection, boundary rules (`2+2` ignored, `(+proj` and ` +proj` detected), and inline `@`/`#`/`+` token extraction ignoring code blocks & URLs.
2. **Database & Repository tests (`:core:data`)**: `MIGRATION_2_3`, `toggleReaction` add/remove & hash update, and per-day 0-based chronological ordinal hydration across multiple entries/days.
3. **ViewModel & Backup tests (`:feature:chat`, `:feature:backup`)**: `+` autocomplete filtering, inline tag auto-attach on send, reaction toggling, and CSV/XLSX `reactions` round-trip + v1/v2 backward-compatible import.
4. **Full CI verification**: `checkLicenseHeaders`, unit tests across all 10 modules, `:app:lintDebug`, and `:app:assembleDebug`.
