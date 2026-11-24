# Batch Plan: History Navigation Fixes, Priorities, Archive, Yellow Highlight, Media Editing & Chat Polish

- **Date:** 2026-11-24
- **Type:** Batch (4 Bug Fixes + 10 Features)
- **Status:** Implemented

This plan covers **4 history/navigation bugs** and **10 features** across `:core:model`, `:core:parser`, `:core:data`, `:feature:chat`, `:feature:views`, `:feature:search`, `:feature:backup`, and `:app`.

---

## Part A — 4 Bugs Dealing with Going Back in Time

### Shared Root Architecture (Fixes Bugs #1, #2, #3 & Enables Feature #8)
- Upgraded [`DefaultEntryRepository.observePagedEntries`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt) to use `PagingConfig(pageSize = 30, enablePlaceholders = true, jumpThreshold = 60, maxSize = 300)` backed by a batch-hydrating delegating `PagingSource<Int, EntryWithDetails>` (calling `hydrator.hydrateEntries(page.data)` once per page instead of per-row).
- Updated [ChatScreen.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt) to render fixed-height 72dp `MessageBubblePlaceholder()` slots for unloaded Paging 3 items and index `lazyPagingItems.itemSnapshotList` directly.
- Added exact SQLite offset lookup queries (`findEntryOffset`, `findDateOffset`) and `observeTimelineDateBuckets` in [EntryDao.kt](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/local/dao/EntryDao.kt) / `EntryRepository`.

### Bug #1: Toggling TODO Status in History Scrolls Away
- With `enablePlaceholders = true`, DB row `k` remains permanently at `LazyColumn` index `k` across Room `LimitOffsetPagingSource` invalidations — eliminating index collapse and `PREPEND` scroll-drift cascades in `reverseLayout = true`.

### Bug #2: `"Open in Chat"` Doesn't Always Go to the Entry
- Added monotonic `jumpNonce` in [ThreadLogRoute.kt](../../../core/model/src/main/kotlin/io/github/k8242/threadlog/core/model/navigation/ThreadLogRoute.kt) and [ThreadLogApp.kt](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/ThreadLogApp.kt) so repeated jumps always trigger.
- Resolved exact DB offset via `EntryRepository.findEntryOffset` (falling back to `findDateOffset`), waited for filter/count readiness, jumped directly via `scrollToItem` / `animateScrollToItem`, and briefly highlighted the target bubble (~1.5s).

### Bug #3: Clicking Embedded Replied-To Message Doesn't Navigate to It
- Made [`QuotedReplyBar`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt) clickable (when `!replyPreview.isDeleted`) and wired `ChatViewModel.jumpToReplyParent`, resolving the parent entry's offset in the current filter first and automatically switching to `All Notes` if the parent lives outside the active filter.

### Bug #4: Weekly & Calendar Views Should Show TODO Status (and Priority / Highlight)
- Added `taskStatus`, `taskPriority`, and `isHighlighted` to `CompactEntrySummary` and `openTodoCount` / `completedTodoCount` to `CalendarDayCount`.
- Added shared `TaskStatusBadge` / `CompactTaskIndicator` / `priorityTrimColor` / `highlightBubbleColor` helpers in [ViewsUiComponents.kt](../../../feature/views/src/main/kotlin/io/github/k8242/threadlog/feature/views/common/ViewsUiComponents.kt) and rendered TODO/DONE indicators, priority color trim, strikethrough, and yellow highlight tints across [CompactWeeklyScreen.kt](../../../feature/views/src/main/kotlin/io/github/k8242/threadlog/feature/views/weekly/CompactWeeklyScreen.kt) and [CalendarScreen.kt](../../../feature/views/src/main/kotlin/io/github/k8242/threadlog/feature/views/calendar/CalendarScreen.kt).

---

## Part B — 10 Features

### Feature #1: Import/Remove Images While Editing *(+ Image Storage Architecture)*
- **Storage architecture**: Images are EXIF-rotated upright, downscaled (`≤ 2048px`), compressed to WebP lossy (`≤ 640 KiB`) via [`AndroidMediaCompressor`](../../../core/media/src/main/kotlin/io/github/k8242/threadlog/core/media/AndroidMediaCompressor.kt), SHA-256 content-deduplicated, and atomically written (`tmp` + `fsync` + move) to app-private `context.filesDir/media/<uuid>.webp` via [`FileMediaStorage`](../../../core/media/src/main/kotlin/io/github/k8242/threadlog/core/media/FileMediaStorage.kt). Metadata lives in Room's `attachments` table (`FK ON DELETE CASCADE`, with `drive_file_id` for Drive sync and `.zip` archival).
- **Edit support**: Enabled Attach Photo & paste in edit mode, added `AttachmentDao.deleteByIds`, extended `EntryRepository.editEntry` with `addedAttachments` & `removedAttachmentIds` (preserving `id` & `driveFileId` for kept attachments), and displayed kept + newly added thumbnails with `×` removal in [ChatComposerComponents.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt).

### Feature #2: Task Priorities (Color Trim, Sorting & Filtering)
- Room v3 → v4 (`MIGRATION_3_4`) adds `task_priority` (`HIGH` orange `#EF6C00`/`#FFA726`, `MEDIUM` dark blue `#1565C0`/`#64B5F6`, `LOW` light blue `#29B6F6`/`#81D4FA`, `NONE` gray) with backward-compatible conditional hashing and CSV/XLSX/Sheets/ZIP sync support.
- Task-only priority rule: setting a priority on a regular note auto-promotes it to `TODO`; removing `TODO` resets priority to `NONE`.
- Added priority filter chips (`All` · `High` · `Med` · `Low` · `None`) and `Sort: Newest / Priority` toggle in `TodoFilterStrip`, quick priority row in `"..."` menu, and composer priority chip.

### Feature #3: Replies Copy Referenced Message's Tags
- `ChatViewModel.startReply` copies `entry.visibleTags` into `selectedTagIds` while tracking newly added IDs in `autoAttachedReplyTagIds` so canceling/switching/deleting a reply cleanly removes only reply-copied tags.

### Feature #4: Mouse Right-Click Context Menu
- Added a `PointerEventPass.Initial` `pointerInput` handler on `MessageBubbleItem`'s `Surface` that opens the `"..."` `DropdownMenu` on `PointerEventType.Press` with `event.buttons.isSecondaryPressed` without conflicting with child clicks, long-press, or swipe-to-reply.

### Feature #5: Hidden Placeholders Toggle in `All Notes`
- Added [`HiddenPlaceholderMatcher`](../../../core/parser/src/main/kotlin/io/github/k8242/threadlog/core/parser/HiddenPlaceholderMatcher.kt) and SQL filtering for `<Media omitted>` and `"You deleted this message"` (only when zero attachments are present), persisted via `UserSettings.showHiddenPlaceholderMessages = false`.
- Hidden everywhere except `All Notes` when the user enables the `"Show hidden"` toggle (in Chat header, Drawer, and Settings).

### Feature #6: Inline `+`/`@`/`#` Tag Stripping & Backslash Escaping (`\@`, `\#`, `\+`)
- Autocomplete selection/creation removes `[trigger.startIndex, trigger.endIndex)` from `composerText` (attaching immediately when editing).
- Sending strips unescaped inline `@`/`#`/`+` tags into `USER` tags (ignoring code blocks/URLs and preserving pre-existing literal tokens during edit) and unescapes `\@`/`\#`/`\+` to literal `@`/`#`/`+`.

### Feature #7: Extra Message Details Sheet (View & Edit Metadata Including Timezone)
- Added [`MessageDetailsSheet.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/MessageDetailsSheet.kt) (from `"..."` → `"Details"`) and `EntryRepository.updateEntryMetadata`, allowing editing Date, Time, searchable IANA Timezone, Task Status, Priority, Highlight, and Archive. Time/timezone, highlight, and archive updates preserve `updatedAtMs` (`Edited` status untouched).

### Feature #8: Google Photos-Style Dated Side Scrollbar + Beginning/End Jump
- Added [`ChatTimelineIndex.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatTimelineIndex.kt) and [`ChatTimelineScrubber.kt`](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatTimelineScrubber.kt) on the right edge of `ChatScreen` with a floating date pill during drag, auto-fade when idle, Jump-to-Beginning (`VerticalAlignTop` in header), and Jump-to-End (`KeyboardArrowDown` FAB).

### Feature #9: Archive & Filter Out Archived Messages
- Room v4 adds `archived_at_ms` (+ index) with sync/backup preservation.
- Excluded archived entries from default feeds, Weekly, Calendar, Search (with an `"Include archived"` chip), Auto-Tag Review, and Drawer counts; added `"Archive"` / `"Unarchive"` menu actions (with Undo snackbar) and a pinned `"Archived"` bucket in `ThreadLogDrawer`.

### Feature #10: Yellow Highlighting for Notes
- Room v4 adds `is_highlighted` with sync/backup preservation.
- Added `"Highlight"` / `"Remove highlight"` toggle (preserving `Edited` status) rendering a warm soft yellow fill (`#FFF8D6` light / `#3D3518` dark) across Chat, Weekly, and Calendar views.
