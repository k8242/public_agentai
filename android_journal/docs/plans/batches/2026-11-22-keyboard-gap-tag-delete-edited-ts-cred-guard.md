# Bug Fix & Feature Plan: Keyboard Gap, Tag Deletion, Edited Timestamps & Password/PIN Guard

- **Date:** 2026-11-22
- **Type:** Batch (Bug Fix + Features)
- **Status:** Implemented (`b75d613`)

This plan covers four requested items:

1. **Fix the large gap above the keyboard** (regular screens & unfolded foldables)
2. **Add UI to delete tags** (while keeping all journal notes intact)
3. **Show the edited timestamp next to the `Edited` label** (including TODO toggles, **excluding** emoji reactions)
4. **Reject passwords / `pw` / PINs aside from `xxxx`** *(excluding Unix `pwd`)*

---

## 1. Fix Large Gap Above Keyboard (Regular & Unfolded Screens)

### Root Cause Analysis
Two compounding issues caused a keyboard-sized white gap above the IME and pushed `CompactChatHeaderBar` off the top of the screen:
- **Double IME shift (`adjustPan` + Compose `imePadding`)**: `MainActivity` in [AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml) did not declare `android:windowSoftInputMode="adjustResize"`. With `enableEdgeToEdge()`, Android defaulted to `adjustPan` (always on regular screens, intermittently on unfolded foldables), panning the entire window upward by the keyboard height while `Scaffold` in [ChatScreen.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt) simultaneously applied `.imePadding()`.
- **Extra nav-bar + IME stacking**: `ChatScreen` applied `.imePadding()` on `Scaffold` while [ChatComposer](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt) also applied `.navigationBarsPadding()` on its inner `Column`. On edge-to-edge Android, `WindowInsets.ime` already overlaps `WindowInsets.navigationBars`, so applying both without `union` added an extra ~24–48dp strip above the keyboard.

### Planned Changes
- **[AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml)**:
  - Add `android:windowSoftInputMode="adjustResize"` to `.MainActivity`.
- **[ChatScreen.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)**:
  - Remove `.imePadding()` from the outer `Scaffold` (keeping `contentWindowInsets = WindowInsets(0, 0, 0, 0)` so `CompactChatHeaderBar` stays pinned directly beneath the status bar and never pans off-screen).
- **[ChatComposerComponents.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt)**:
  - Replace `.navigationBarsPadding()` on `ChatComposer`'s inner `Column` with `Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))`.
- **[ThreadLogDrawer.kt](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/ThreadLogDrawer.kt)**:
  - Update the drawer's bottom inset from `.navigationBarsPadding()` to `.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))` so typing in `DrawerSearchField` behaves cleanly too.

---

## 2. Delete Tags

### Existing Data Layer
`TagRepository.deleteTag(tagId)` in [DefaultTagRepository.kt](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultTagRepository.kt) already deletes the target tag and any descendant sub-tags deepest-first, cascades removal from `entry_tags` **without deleting any journal notes**, recomputes affected entries' `contentHash`, and reindexes FTS5.

> [!NOTE]
> Deleting a tag removes it from the tag tree and detaches it from existing notes, while leaving note body text untouched. If a user later edits a note whose raw text still contains `#deletedtag` / `@person` / `+project`, saving that edit will re-create the tag unless removed from the text.

### Planned UI & ViewModel Changes
- **Hidable Left Panel** ([DrawerViewModel.kt](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/DrawerViewModel.kt), [ThreadLogDrawer.kt](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/drawer/ThreadLogDrawer.kt), [ThreadLogApp.kt](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/ThreadLogApp.kt)):
  - Inject `TagRepository` into `DrawerViewModel` and add `deleteTag(tagId: String)`.
  - Support **long-press** on any tag/topic/project/person row in `ThreadLogDrawerSheet` (using `combinedClickable`) as well as a subtle trailing delete button, opening a confirmation `AlertDialog`.
  - Confirmation dialog displays the tag display name, full path (e.g., `projects/apollo`), affected note count, child sub-tag warning if any, and explicit reassurance: *"Your journal notes will not be deleted."*
  - In [ThreadLogApp.kt](../../../app/src/main/kotlin/io/github/k8242/threadlog/app/ui/ThreadLogApp.kt): if the deleted tag (or an ancestor of the currently active `ChatFeedFilter.TagSubtree`) is currently open, automatically reset `activeChatFilter` to `ChatFeedFilter.All`.
- **Quick Tag Picker Sheet** ([ChatComposerComponents.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt), [ChatViewModel.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatViewModel.kt), [ChatScreen.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)):
  - Add `deleteTag(tagId: String)` to `ChatViewModel` (deletes via `tagRepository.deleteTag`, prunes deleted IDs from `selectedTagIds`, and resets `activeFilter` to `All` if the active filter tag was deleted).
  - Add a **"Delete tags"** toggle button in the `QuickTagPickerSheet` header: when enabled, each tag chip displays a trailing delete icon that opens the confirmation dialog before calling `onDeleteTag`.

---

## 3. Edited Timestamp Next to `Edited` Label (Including TODO Toggles; Excluding Emojis)

### Timestamp Semantics
- **Updates `updatedAtMs` (shows `Edited <timestamp>`)**:
  - Text edits via [`editEntry`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt)
  - TODO toggles (mark TODO, unmark TODO, toggle DONE) via [`setTaskStatus`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt)
- **Does NOT update `updatedAtMs`**:
  - Emoji reactions via [`toggleReaction`](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt): keep `existing.updatedAtMs` unchanged (only update `reactionsJson` and recompute `contentHash`).

### Display & Layout Changes
- **Formatting helper** ([ChatDividerLogic.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatDividerLogic.kt)):
  - Format `entry.updatedAtMs` in `entry.zoneId` relative to `entry.createdAtMs`:
    - **Same local day**: `Edited 8:05 PM` (header: `0xA · 7:40 PM · Edited 8:05 PM`)
    - **Different day, same year**: `Edited Nov 22, 9:12 AM`
    - **Different year**: `Edited Jan 3, 2027, 9:12 AM`
- **Bubble Header Layout** ([ChatMessageComponents.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatMessageComponents.kt), [ChatScreen.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatScreen.kt)):
  - Pass `editedLabel: String?` into `MessageBubbleItem`.
  - Replace the single-line `Row` for the upper-left metadata cluster with a `FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically)` so longer headers wrap cleanly on narrow folded screens without clipping or colliding with the `MoreVert` button.

---

## 4. Reject Passwords / `pw` / PINs Aside from `xxxx`

### Goal
Prevent accidental storage of real plaintext passwords, passcodes, or PINs in journal notes (and backups), while still allowing masked placeholders (`xxxx`, `XXXX`, or `x` repeated 4+ times).

### Detection Rules (`SensitiveCredentialGuard` in `:core:parser`)
Create a pure Kotlin validator [SensitiveCredentialGuard.kt](../../../core/parser/src/main/kotlin/io/github/k8242/threadlog/core/parser/SensitiveCredentialGuard.kt) (with `SensitiveCredentialException`) that inspects note text on both **create** and **edit**:

- **Word-boundary keywords** (case-insensitive, so words like `opinion`, `spin`, `pinned`, `pinch`, `spine` never trigger; `pwd` is intentionally excluded because it's a Unix command):
  - Password keywords: `password`, `passwd`, `passcode`, `passphrase`, `pw`, `p/w`
  - PIN keywords: `pin`, `pincode`, `pin code`, `pin#`, `pin #`
- **Allowed placeholder exception**:
  - A value matching `^[xX]{4,}$` (case-insensitive `xxxx` or longer runs of `x`, optionally surrounded by quotes/backticks/brackets or trailing sentence punctuation like `.`, `,`, `;`, `)`).
  - Examples **allowed**: `pw: xxxx`, `password is XXXX`, `pin: xxxx.`, `ATM pin xxxx`, `passcode=xxxxxx`.
- **Rejected patterns** (any value other than `xxxx`+):
  1. **Explicit separator** (`:`, `=`, `->`, `=>`, ` - ` / ` – ` / ` — ` after keyword):
     - Always rejected unless the value is `xxxx`+.
     - Examples blocked: `password: hunter2`, `pw=1234`, `p/w: MyPass!`, `pin: 4829`, `PIN=0451`, `passcode: 998877`, `pw: "let me in"`, `pw: xxx` (fewer than 4 `x`s).
  2. **Natural-language assignment** (`is` / `was` / `set to` / `changed to` / `updated to`):
     - Rejected unless the value is `xxxx`+ or a clear status adjective/negation (e.g., `password is expired`, `pin is locked`, `password is not working`, `pin is reset`).
  3. **Bare space assignment** (no `:`/`=`/`is`):
     - For `pin` / `pincode` / `pin code` / `passcode`: rejected when followed by a numeric or alphanumeric code token (e.g., `pin 4829`, `pin #0451`, `passcode 8844`, `pin 48a9`) unless `xxxx`+. Ordinary prose like `"pin this note"`, `"pin to top"`, `"safety pin"` stays allowed.
     - For `pw` / `p/w` / `passwd`: rejected when followed by a value token unless `xxxx`+ or in a small prose stopword list.
     - For `password` / `passphrase`: rejected when followed by a quoted value or a token containing digits/symbols/internal caps (e.g., `password 1234`, `password hunter2`, `password "my secret"`), while allowing everyday phrases like `"change password tomorrow"`, `"reset password link"`, `"password manager"`.
  4. **Inline tag safety**: `#password`, `#pin`, `+password-manager` alone are not blocked unless followed by a credential value.

### Enforcement & UX
- **UI Layer** ([ChatViewModel.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatViewModel.kt), [ChatComposerComponents.kt](../../../feature/chat/src/main/kotlin/io/github/k8242/threadlog/feature/chat/ui/ChatComposerComponents.kt)):
  - Validate inside `sendOrSave()` for **both new notes and edits**.
  - When rejected, do **not** persist and do **not** clear `composerText`; display a warning banner above the composer with a one-tap **"Mask xxxx"** button that replaces detected value(s) with `xxxx` in place.
- **Repository Layer Defense-in-Depth** ([DefaultEntryRepository.kt](../../../core/data/src/main/kotlin/io/github/k8242/threadlog/core/data/repository/DefaultEntryRepository.kt)):
  - Call `SensitiveCredentialGuard.requireAllowed(bodyRaw)` at the start of both `createEntry(...)` and `editEntry(...)`, throwing `SensitiveCredentialException` if rejected.

---

## 5. Verification Plan

1. **Unit tests**:
   - `SensitiveCredentialGuardTest` (`:core:parser:test`): allow/reject matrix covering `password`, `passwd`, `passcode`, `passphrase`, `pw`, `p/w`, `pin`, `pin code`, `pin#`, quoted values, `xxxx`/`XXXX` exemptions, `xxx` rejection, auto-masking helper (`maskDetectedWithXxxx`), and false-positive prose (`pwd: /home/user`, `opinion`, `pinned`, `safety pin`, `reset password tomorrow`, `password manager`, `password is expired`, `#password`).
   - `TaskAndDrawerRepositoryTest` (`:core:data:testDebugUnitTest`): verify `toggleReaction` preserves `updatedAtMs` (`isEdited == false`), and verify `createEntry` / `editEntry` throw `SensitiveCredentialException` on real passwords/PINs while allowing `xxxx` and `pwd`.
   - `ChatDividerLogicTest` & `ChatViewModelTest` (`:feature:chat:testDebugUnitTest`): edited timestamp formatting, `deleteTag` state cleanup, and `sendOrSave` blocking credentials while preserving composer text + one-tap masking.
   - `DrawerViewModelTest` (`:app:testDebugUnitTest`): verify `deleteTag` delegates to `TagRepository`.
2. **Full Gradle checks**:
   - `./gradlew checkLicenseHeaders`
   - `./gradlew :core:model:test :core:parser:test testDebugUnitTest`
   - `./gradlew :app:lintDebug`
   - `./gradlew :app:assembleDebug`
