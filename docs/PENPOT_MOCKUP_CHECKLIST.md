# Penpot mockup checklist (Day 3 of UI/UX plan)

This is the manual design step in `docs/UI_UX_OPTIMIZATION_PLAN.md` Day 3 — Claude can't execute it. Sign in to [penpot.app](https://penpot.app), create a new project named **VideoSubtitle UX v2**, and produce the artboards below before starting Editor v2 implementation in Day 5.

## Setup (one-time, ~15 min)

1. Create a project: **VideoSubtitle UX v2**.
2. Create a file: **VS-screens-v2**.
3. Set up Android frame presets:
   - Phone: 411 × 891 (Pixel 7 logical px)
   - Foldable inner: 673 × 841
   - Tablet: 800 × 1280
4. Pull Material 3 tokens:
   - Open https://m3.material.io/theme-builder, generate from a brand seed (suggested: `#3F51B5` indigo or `#006C4D` green — pick one).
   - Export tokens JSON; in Penpot, **Tokens → Import** and load the JSON.
   - Verify color roles `primary`, `onPrimary`, `surfaceContainer`, `onSurfaceVariant` are visible.
5. Library: install **Material 3 — Components** library from Penpot's community libraries panel.

## Artboards to produce

### A. Editor v2 (highest audit severity — do these first)

**A1. `editor-v2-canonical`** — default state, segments populated, no row selected.
- Top: `MaterialToolbar` with title "Edit subtitles", overflow menu (Save, Restore, Export).
- Body: vertical list of segment rows. Each row:
  - Left: 24dp drag handle (`drag_indicator` Material icon, color `onSurfaceVariant`).
  - Middle column:
    - Top: `#1` index + monospace timestamp `00:00:01.234 to 00:00:03.456` (replace literal `→`). Color: `onSurfaceVariant`, `textAppearanceLabelMedium`.
    - Below: subtitle text, multi-line, `textAppearanceBodyLarge`, color `onSurface`.
  - Right: circular icon button (40dp), `play_arrow` icon, tonal fill from `secondaryContainer`. Visible only on the row currently being scrubbed.
- Floating Action: none (use FloatingToolbarLayout in A3).
- Bottom: nothing — undo Snackbar appears only after an edit.

**A2. `editor-v2-swipe-delete`** — same list, one row mid-swipe.
- Show row 3 swiped 80% to the left, revealing a red `errorContainer` background with a centered `delete` icon.
- Below the list, render the post-action Snackbar:
  - Background: `inverseSurface`, text `inverseOnSurface`.
  - Body: "Segment deleted"
  - Action label: "Undo" (color `inversePrimary`, all caps).
  - Auto-dismiss: 5s indicator.

**A3. `editor-v2-split`** — long-press at cursor mid-row.
- Show row 2 in selected state: `surfaceContainerHighest` background, primary-colored caret bar at character 14 of the text.
- Bottom: `FloatingToolbarLayout` (MDC 1.14) docked, height 56dp, four icon buttons spaced evenly:
  - `content_cut` "Split"
  - `merge` "Merge with next"
  - `delete` "Delete"
  - `close` "Cancel"
- Background: `surfaceContainerLow` with `cornerRadiusLarge` and 4dp elevation shadow.

### B. Progress

**B1. `progress-v2-running`** — transcription stage at ~42%.
- Hero: video thumbnail, height 180dp.
- Title: filename, `textAppearanceTitleMedium`.
- 4-step **stepper** below the title:
  - `Extracting` (✓ check icon, `primary`)
  - `Transcribing` (filled circle, `primary`, **bolded** label `textAppearanceTitleLargeEmphasized`)
  - `Editing` (outlined circle, `outline`, label muted)
  - `Burning` (outlined circle, `outline`, label muted)
- MDC 1.14 `LoadingIndicator` (pill-shaped contained variant, NOT generic ProgressBar). Indeterminate during transcribe.
- Caption: "About 3 minutes" — fuzzy ETA, `textAppearanceBodyMedium`, `onSurfaceVariant`.
- Buttons (vertically stacked, scrollable per Day 2 fix):
  - Primary: "Cancel" (filled tonal, `errorContainer`).
  - Outlined: "Edit subtitle" (disabled until subtitle ready).

**B2. `progress-v2-burn-countdown`** — 10s auto-burn timer visible.
- Same skeleton as B1, but the stepper's third step "Editing" is `done`, fourth "Burning" is highlighted.
- Above the bottom buttons: a `Chip` (assist variant, color `tertiaryContainer`, `onTertiaryContainer` text):
  - Icon: `schedule`
  - Label: "Burning in 7s — Tap to cancel"
  - Tappable, dismisses the timer.

### C. Home

**C1. `home-empty`** — first run.
- Toolbar: title "VideoSubtitle".
- Body: centered `StateView` with `video_library` icon (96dp, `onSurfaceVariant`), headline "No videos yet", body "Pick a video to add subtitles", primary button "Pick video".
- FAB bottom-right: extended FAB with `add` icon + label "Add" (use the new `ic_add` vector from Day 1).

**C2. `home-populated`** — 4 task cards.
- Toolbar: title "VideoSubtitle", overflow menu (Settings).
- List of cards. Each card:
  - Left: 80×60dp thumbnail.
  - Middle: filename (1 line, ellipsize), state chip ("Done", "Burning 67%", "Editing", "Failed — tap to retry"). Cost line: "12 MB · 02:34".
  - Right: chevron / overflow.

**C3. `home-selection-mode`** — long-press mode, 2 selected.
- Toolbar transforms: navigation icon → close X, title → "2 selected" (rendered via plurals: `home_selection_title_one`/`other`).
- Action icons: `select_all`, `delete`.
- Selected rows: `secondaryContainer` background, checkmark in top-left.

## Hand-off checklist for Day 5 implementation

When mockups are done, capture for the implementer:
- [ ] Exported PNG of A1, A2, A3 at 2x.
- [ ] List of M3 token names used (so they map to `?attr/colorXxx` in XML).
- [ ] One-paragraph description of the swipe-delete + undo motion timing (use M3 tokens: `motionDurationLong2`, `motionEasingEmphasizedDecelerate`).
- [ ] Decision: drag handle on left vs. drag-anywhere-on-row (left handle is the safer default; drag-anywhere conflicts with click-to-edit unless you delay the drag start by `LONG_PRESS_TIMEOUT`).
