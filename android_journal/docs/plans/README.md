# ThreadLog Planning Artifacts

This directory tracks design and implementation plans for `android_journal` (ThreadLog).

## Folder & Naming Convention

Plans are organized by type using `YYYY-MM-DD-<short-slug>.md`:

- **`features/`** — New feature plans (`features/YYYY-MM-DD-<slug>.md`)
- **`bugs/`** — Standalone bug-fix investigations and plans (`bugs/YYYY-MM-DD-<slug>.md`)
- **`batches/`** — Combined plans covering both bug fixes and features in a single commit (`batches/YYYY-MM-DD-<slug>.md`)

*(If multiple plans are written on the same day in the same folder, append `-02`, `-03`, etc. after the date.)*

## Index

| Date | Type | Plan | Status | Commit |
| --- | --- | --- | --- | --- |
| 2026-11-22 | Feature | [Hidable Left Panel, Color Schemes, TODO Tasks, Scroll-Up & Layout](features/2026-11-22-drawer-themes-todos-layout.md) | Implemented | `76348c2` |
| 2026-11-22 | Feature | [Emoji Reactions, Upper-Left Hex-Ordinal Timestamps & `+project` Tagging](features/2026-11-22-reactions-hex-ordinals-projects.md) | Implemented | `ab0e206` |
| 2026-11-22 | Batch (Fix + Features) | [Keyboard Gap Fix, Tag Deletion, Edited Timestamps & Password/PIN Guard](batches/2026-11-22-keyboard-gap-tag-delete-edited-ts-cred-guard.md) | Implemented | `b75d613` |
| 2026-11-22 | Feature | [Composer Backdating & WhatsApp Export Import](features/2026-11-22-backdate-and-whatsapp-import.md) | Implemented | `7726a02` |
| 2026-11-24 | Batch (Fixes + Features) | [History Navigation Fixes, Priorities, Archive, Yellow Highlight, Media Editing & Chat Polish](batches/2026-11-24-history-scroll-priorities-archive-polish.md) | Implemented | *(this commit)* |
