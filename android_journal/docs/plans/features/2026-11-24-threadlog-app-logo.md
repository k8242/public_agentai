# ThreadLog Feature Plan: Adaptive Yarn, Pencil & Highlighter App Logo

- **Date:** 2026-11-24
- **Type:** Feature
- **Status:** Implemented

Replace ThreadLog's placeholder teal `"T"` launcher drawable with a custom, playful Android Adaptive Icon built around a coral yarn ball, crossed wooden pencil, electric-lime chisel-tip highlighter, neon highlight swipe, and trailing chat-bubble yarn loop on a slate-blue (`#283E55`) background.

---

## Design & Asset Decisions

- **Visual Motif**: Flat-vector Material aesthetic combining ThreadLog's core metaphors — **yarn** ("thread") unwinding into a small speech bubble, **pencil** (journaling/logging), and **highlighter** (emphasizing/tagging key moments) — centered over a slate-blue radial backdrop (`#283E55` outer edge).
- **Adaptive Icon Safe Zone (`minSdk = 26`)**: Foreground artwork scaled so the entire motif (including eraser, chisel tip, and bottom speech-bubble loop) stays inside the central `66 dp` safe circle (`108 dp` canvas) with a smooth outer alpha feather into `#283E55` to prevent rectangular seams during launcher parallax.
- **Material You Themed Icons (Android 13+)**: Added a crisp single-color vector `<monochrome>` layer (`ic_launcher_monochrome.xml`) using true transparent negative-space cutouts (`evenOdd` clipped to the yarn ball circumference) and smooth quadratic Bézier curves (`<= 396` chars per path for Android Lint `VectorPath` compliance).
- **Lint-Clean Resource Layout**: Placed adaptive icon XMLs in `mipmap-anydpi/` (rather than `mipmap-anydpi-v26/`) to avoid `ObsoleteSdkInt` warnings with `minSdk = 26`, and removed the unused placeholder `res/drawable/ic_launcher.xml`.

---

## Planned & Implemented Changes

- **Adaptive icon XML & vectors (`:app`)**:
  - [ic_launcher.xml](../../../app/src/main/res/mipmap-anydpi/ic_launcher.xml) & [ic_launcher_round.xml](../../../app/src/main/res/mipmap-anydpi/ic_launcher_round.xml) — `<adaptive-icon>` wiring `@drawable/ic_launcher_background`, `@mipmap/ic_launcher_foreground`, and `@drawable/ic_launcher_monochrome`.
  - [ic_launcher_background.xml](../../../app/src/main/res/drawable/ic_launcher_background.xml) — `#283E55` slate-blue vector background.
  - [ic_launcher_monochrome.xml](../../../app/src/main/res/drawable/ic_launcher_monochrome.xml) — Material You monochrome silhouette vector.
- **Density mipmaps & Play Store artwork (`:app`)**:
  - `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` (`108`–`432 px`)
  - `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` & `ic_launcher_round.png` (`48`–`192 px`)
  - [ic_launcher-playstore.png](../../../app/src/main/ic_launcher-playstore.png) (`512×512`)
- **Manifest, cleanup & docs**:
  - [AndroidManifest.xml](../../../app/src/main/AndroidManifest.xml) — switched to `android:icon="@mipmap/ic_launcher"` and added `android:roundIcon="@mipmap/ic_launcher_round"`.
  - Removed obsolete `app/src/main/res/drawable/ic_launcher.xml`.
  - Updated reserved icon asset paths in [TRADEMARKS.md](../../../TRADEMARKS.md).

---

## Verification

- **Android SDK `aapt2` (`build-tools/35.0.0`, `platforms/android-35`)**: `aapt2 compile` + `aapt2 link` (`--min-sdk-version 26 --target-sdk-version 35`) succeeded with zero warnings/errors; `aapt2 dump badging` resolved all densities to `res/mipmap-anydpi-v21/ic_launcher.xml`.
- **Visual mask inspection**: Checked rendered previews under Circle, Rounded Square, Squircle, Play Store `512×512`, and Light/Dark Material You monochrome masks with zero motif clipping.
- **CI / Local Gradle**: Full `./gradlew :app:assembleDebug :app:lintDebug` to be verified in CI or a networked local build (plugin repo download unavailable in this offline VM).
