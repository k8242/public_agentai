# Feature Plan: Fast Tagging, `Shift+Enter`, Tag Sorting/Colors, All Notes Filters, Multi-Select, Sub-Tag Tabs & Bidirectional TODO↔Tag Filtering

**No Room database migration is required** — `tags.color_token` and `entry_tags.updated_at_ms` already exist in schema v4, and `tagSortOrder` persists cleanly in DataStore.

---

## 1. (`0x4`) Faster Tagging: Context Menu at Cursor Position + Recent Tags Strip

### Problem
In [`MessageBubbleItem`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt), `DropdownMenu` was anchored inside the top-right `MoreVert` button `Box`, so right-clicking or long-pressing anywhere on a bubble always opened the menu in the upper-right corner, and applying a tag required opening `QuickTagPickerSheet`.

### Changes
- **Anchor menu at exact mouse/touch press coordinates** ([`ChatMessageComponents.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt)):
  - Wrap the bubble `Surface` in an anchor `Box` and track `menuAnchorPx: Offset?` + `bubbleSize: IntSize` via `.onSizeChanged`.
  - In `PointerEventPass.Initial` `pointerInput`: capture `event.changes.firstOrNull()?.position` on secondary right-click (and last primary press for long-press) and set `menuAnchorPx`. Clicking the top-right `MoreVert` button anchors at `(bubbleSize.width, 0)`.
  - Place a zero-size `Box(Modifier.offset { IntOffset(x, y) })` hosting the single `DropdownMenu` so Material 3 opens directly at the cursor position and auto-flips cleanly near viewport edges.
- **Recent tags query** ([`TagDao.kt`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/local/dao/TagDao.kt), [`DefaultTagRepository.kt`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultTagRepository.kt), [`DefaultEntryRepository.kt`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt)):
  - Add `TagDao.observeRecentTags(limit: Int = 6)` querying `tags` joined with `entry_tags` (`state IN ('USER', 'AUTO_PROMOTED')`) ordered by `MAX(et.updated_at_ms) DESC, t.full_path ASC`.
  - Record wall-clock `System.currentTimeMillis()` on `EntryTagEntity.updatedAtMs` when creating entries so backdated notes still surface their tags as "recently used".
- **1-tap recent tags row inside the context menu** ([`ChatViewModel.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatViewModel.kt), [`ChatMessageComponents.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt)):
  - Collect `recentTags` into `ChatUiState` and render a compact horizontally scrollable chip row directly inside the context menu (matching the emoji and priority strips).
  - Each chip shows its tag color, displays a checkmark/selected border when already attached to the message, and toggles attachment in 1 tap (plus a `+` / `"More"` chip to open `QuickTagPickerSheet`).

---

## 2. (`0x5`) Keyboard `Shift+Enter` to Send

- In [`ChatComposer`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt):
  - Hoist `canSend` and `sendEnabled = canSend && !uiState.isCompressingImage` above `OutlinedTextField`.
  - Add `.onPreviewKeyEvent` to `OutlinedTextField`:
    - When `(event.key == Key.Enter || event.key == Key.NumPadEnter) && event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed`:
      - On `KeyEventType.KeyDown`: if `sendEnabled`, call `onSendOrSave()`; return `true` to consume the event (preventing newline insertion).
      - On `KeyEventType.KeyUp`: return `true` to consume cleanly.
    - Plain `Enter` (without Shift) returns `false` and continues inserting a newline as usual.

---

## 3. (`0x7`) Tag Sorting & Coloring

### Tag Coloring
- Add a pure-Kotlin `TagColorPreset` enum in `:core:model` (`DEFAULT`, `CORAL`, `AMBER`, `EMERALD`, `TEAL`, `SKY`, `INDIGO`, `VIOLET`, `PINK`, `ROSE`, `SLATE`) with light & dark container, on-container, and swatch ARGB colors.
- Add `TagDao.updateColorToken(tagId, colorToken)` and `TagRepository.updateTagColor(tagId, colorToken)`.
- Fix two preservation edge cases:
  - Preserve custom `colorToken` in [`DefaultTagRepository.renameOrMoveTag`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultTagRepository.kt) instead of overwriting with the default category key.
  - Apply restored `sheetTag.colorToken` in [`DefaultBackupDataGateway.applyMergeRestore`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultBackupDataGateway.kt) so custom colors round-trip through CSV/XLSX/ZIP/Sheets backups.
- Use the shared color resolver everywhere tags appear: `TagPillChip` (chat bubbles), recent tags strip in context menu, `TagSuggestionList` (autocomplete), `QuickTagPickerSheet`, and `ThreadLogDrawer` avatars.
- **Color Picker UI**:
  - In [`ThreadLogDrawer`](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/ThreadLogDrawer.kt): long-pressing a tag row opens a compact **Tag Options** dialog with **color swatches** (1-tap apply) + **Delete tag** (keeping the trailing trash icon for direct delete).
  - In `QuickTagPickerSheet`: add a `"Colors"` / palette mode (alongside `"Delete tags"`) so any tag can be recolored right from the picker.

### Tag Sorting
- Add `enum class TagSortOrder { RECENT, NAME_ASC, MOST_USED, COLOR }` to [`DomainEnums.kt`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/DomainEnums.kt), persisted in `UserSettings` / `DataStoreSettingsRepository`.
- In [`DrawerViewModel`](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/DrawerViewModel.kt) & [`ThreadLogDrawer`](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/ThreadLogDrawer.kt): sort `filteredTagSummaries` by the active `TagSortOrder` (`Recent`, `A–Z`, `Most used`, `Color`) and add a compact sort dropdown button beside the search bar / category chips.
- Apply the same persisted `tagSortOrder` inside `QuickTagPickerSheet`.

---

## 4. (`0x8`) `"All Notes"` Toggles: `"Untagged only"` & `"Hide TODOs"`

- Convert `ChatFeedFilter.All` in [`DomainModels.kt`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/DomainModels.kt) from `data object` to:
  ```kotlin
  data class All(
      val untaggedOnly: Boolean = false,
      val hideTodos: Boolean = false,
  ) : ChatFeedFilter
  ```
- Update all 4 `*All` queries in [`EntryDao.kt`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/local/dao/EntryDao.kt) (`pagingSourceAll`, `countAheadAll`, `getNewestEntryForDateOnOrBeforeAll`, `observeTimelineDateBucketsAll`) + `DefaultEntryRepository.findEntryOffset`:
  - `untaggedOnly`: `NOT EXISTS (SELECT 1 FROM entry_tags et WHERE et.entry_id = e.id AND et.state IN ('USER', 'AUTO_PROMOTED'))`
  - `hideTodos`: `e.task_status = 'NONE'` (hides both open `TODO` and `DONE` tasks)
- Render a compact `AllNotesFilterStrip` directly under `CompactChatHeaderBar` when viewing `All Notes` with two toggle chips: **`Untagged only`** and **`Hide TODOs`**, plus tailored empty-state copy when active.

---

## 5. (`0x9`) Multi-Select Mode (Copy List + Batch Actions)

- **State & Entry** ([`ChatViewModel.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatViewModel.kt), [`ChatScreen.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)):
  - Add `selectedEntryIds: Set<String> = emptySet()` to `ChatUiState` (cleared on filter change; `BackHandler` exits selection mode).
  - Enter selection mode via **`"Select"`** in any message's right-click / `"..."` menu. While in selection mode:
    - Tapping any bubble toggles its selection (disabling swipe-to-reply and inner link/tag clicks so taps never mis-fire).
    - A clean selection checkbox/circle renders in the 20% left margin beside each 80% bubble, with a subtle primary tint on selected bubbles.
- **Contextual Top Bar + Bottom Batch Action Bar**:
  - **Top bar**: `Close (×)` · `N selected` · `Select visible` · **`Copy`** button.
  - **Bottom action bar**:
    - **Copy** (formats selected entries in **chronological order**, resolving any off-window IDs via `entryRepository.getEntry(id)`, copied via deprecation-free `ClipboardManager` + `"Copied N messages"` snackbar)
    - **Tags** (opens `QuickTagPickerSheet` in `BatchSelected` mode to add/remove tags across all selected messages)
    - **TODO / Priority** (popup menu: Mark TODO, Mark Done, Remove TODO, or High / Med / Low / None priority)
    - **Highlight** (toggles yellow highlight across selected messages)
    - **Archive / Unarchive** (with multi-entry **Undo** snackbar)
    - **Delete** (with multi-entry **Undo** snackbar)

---

## 6. (`0xB`) Sub-Tags Generate Tabs Under Tag View

- Extend `ChatFeedFilter.TagSubtree` in [`DomainModels.kt`](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/DomainModels.kt):
  ```kotlin
  data class TagSubtree(
      val tagId: String,
      val fullPath: String,
      val displayLabel: String,
      val selectedSubTagId: String? = null,
      val selectedSubTagPath: String? = null,
      val todoFilterMode: TodoFilterMode? = null,
      val priorityFilter: TaskPriority? = null,
      val todoSortOrder: TodoSortOrder = TodoSortOrder.NEWEST,
  ) : ChatFeedFilter {
      val effectiveTagId: String get() = selectedSubTagId ?: tagId
      val effectiveFullPath: String get() = selectedSubTagPath ?: fullPath
  }
  ```
- **Tab Bar Behavior** ([`ChatScreen.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)):
  - Compute direct child tags (`tag.parentId == filter.tagId`) from `uiState.allTags` (sorted by `tagSortOrder`).
  - If a tag has **no children**, show no tab bar.
  - If a tag **has children**, show a `SecondaryScrollableTabRow` directly under `CompactChatHeaderBar`: **`[All]` `[child1]` `[child2]` ...**.
  - Selecting a sub-tag tab filters the feed to `effectiveFullPath` and auto-attaches `effectiveTagId` in the composer while keeping the parent tag's tab bar anchored.
  - For 3+ levels of nesting (if the active sub-tag itself has children), show a compact secondary child chip row underneath.
  - Deleting only the active sub-tag cleanly falls back to `selectedSubTagId = null` (the parent's `[All]` tab).

---

## 7. (`0xC`) Bidirectional TODO ↔ Tag Cross-Filtering

- **Filter TODOs by Tag**:
  - Add optional `tagId: String? = null`, `tagFullPath: String? = null`, `tagDisplayLabel: String? = null` to `ChatFeedFilter.Todos`.
  - Add a `"Tag: All"` / colored `"#tag ×"` selector chip inside `TodoFilterStrip` ([`ChatScreen.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)) opening a quick tag picker dialog. When a tag is selected in TODOs view, the composer also pre-attaches that tag (alongside `TODO`).
- **Filter a Tag View by TODOs (Open / Done / All + Priority + Sort)**:
  - Below any sub-tag tabs in a Tag view, render a compact `TagTodoFilterStrip`:
    - Row 1: **`All notes`** · **`Open (N)`** · **`Done (N)`** · **`All TODOs`** (plus `Sort: Newest / Priority` when a TODO mode is active).
    - Row 2 (shown only when a TODO mode is active): priority chips **`All` · `High` · `Med` · `Low` · `None`**.
  - When a TODO mode is active inside a Tag view, the composer automatically pre-enables `composerIsTask = true` alongside the active tag.
- **Unified SQLite Queries** ([`EntryDao.kt`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/local/dao/EntryDao.kt)):
  - Add optional nullable `exactTagPath: String? = null, subtreeLikePattern: String? = null` to the 6 existing `*Todos*` queries so both `Todos(tag)` and `TagSubtree(todoFilterMode != null)` share the exact same query paths with zero duplication.
  - Add `EntryDao.observeTodoCounts(exactTagPath, subtreeLikePattern)` so Open/Done counts update accurately for the active tag/sub-tag.
