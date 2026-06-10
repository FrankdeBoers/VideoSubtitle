# UI/UX Optimization Plan

Generated 2026-06-08 from a research + audit workflow (`ui-ux-optimize`, run `wf_c8e68abb-4b0`). 4 research agents surveyed open-source design tools / MD3 / Android UI references / video-app UX patterns; 6 audit agents inspected layouts, theming, navigation, interaction & a11y, i18n, and end-to-end user flows.

## Executive summary

VideoSubtitle ships solid architecture (MVVM, idempotent pipeline, FGS-based service) but its UI is on Material 3 baseline-purple with no brand tokens, hardcoded `dp`/`sp` throughout, several real a11y blockers (`SegmentAdapter` has no `ItemTouchHelper` at all, `item_model_card` `MaterialButton` zeroes `minHeight`/`minWidth`, `RadioGroup`s in compute/output settings interleave `TextView`s, Progress has no `ScrollView` so large-font reflow buries the action stack), and i18n contamination where raw exception strings (SHA-256 mismatch, Microsoft HTTP 401, `whisper_init_from_file failed`) reach end users via `task_stage_failed`.

**Highest-leverage external lever:** bump to Material Components for Android 1.14.0 + `Theme.Material3.Expressive.*` + `DynamicColors.applyToActivitiesIfAvailable` — all XML-only, no Kotlin rewrites, fully compatible with the current `targetSdk 36` / `minSdk 26`.

**Scope:** 14 quick wins (under 1h each), 6 medium projects, 4 big bets including an Editor v2 with drag/swipe/split/merge/audio-scrub and an Android-16-mandated adaptive-layout pass.

## Recommended design tool: Penpot

- **URL:** [penpot.app](https://penpot.app)
- **Why:** MPL-2.0, web-based with Docker self-host, the closest open-source Figma replacement. Frames pre-sized to common Android viewports (sw320/360/411/600), Flex/Grid layouts, components and design tokens that round-trip to the Material Theme Builder export you'll be dropping into `values/colors.xml`. No free tool reliably exports to Android XML/Compose (Google's Relay-for-Android is dead) — the win is having the M3 tokens and component specs side-by-side with your mockup so what you draw matches what `MaterialButton` / `MaterialCardView` actually render at runtime.

### Mockups, in this order

1. **Editor v2** (highest audit severity) — three artboards:
   - Canonical row: 24dp drag handle, index + monospace `mm:ss.mmm to mm:ss.mmm` on top, multi-line text below, play-from-here circular icon button right, undo Snackbar at bottom.
   - Selected/edit state: `FloatingToolbarLayout` (split / merge / delete) docked at bottom referencing M3 Expressive shape tokens.
   - Swipe-to-delete state with undo Snackbar.
2. **Progress** — 4-step stepper (Extracting → Transcribing → Editing → Burning), MDC 1.14 `LoadingIndicator`, fuzzy ETA copy ("About 3 minutes"), visible 10s burn-countdown chip with Cancel; vertically scrollable action stack. Reference Seal and AntennaPod's per-task download card.
3. **Home** — empty + populated + selection-mode states on one artboard. Task row with thumbnail, title, stage chip, secondary cost line, overflow menu; FAB with new `ic_add` vector; selection-mode toolbar with selected-count plurals. Reference NewPipe's download manager screen (closest XML-era analog).

Settings can be designed alongside the Material Theme Builder output rather than separately.

### Other tools considered

| Tool | License | Verdict |
|------|---------|---------|
| Excalidraw | MIT | Best for the FIRST whiteboard pass — sketch all 4 screens + flows in 30 min |
| Lunacy | Free, not OSS | Strong second if you prefer offline native; built-in Icons8 library |
| Quant-UX | GPL-3.0 | Skip unless you plan real usability tests |
| Pencil Project | GPL | Dormant since Jan 2023, Holo-era visuals |

## Quick wins (under 1h each)

| # | Title | File | Change |
|---|-------|------|--------|
| 1 | Fix `dp` → `sp` on settings text sizes | `fragment_settings_style.xml:141,229,339` | `android:textSize="16dp"` → `"16sp"` (high a11y) |
| 2 | Replace framework FAB icon | `fragment_home.xml:38-45` | Add `drawable/ic_add.xml` Material vector; switch `srcCompat` |
| 3 | Material3 password toggle attr | `fragment_settings_translate.xml:84,117,150,183` | `app:passwordToggleEnabled` → `app:endIconMode="password_toggle"` |
| 4 | Restore button touch target | `item_model_card.xml:102-109` | Remove `minWidth="0dp"`/`minHeight="0dp"` on `btn_action` (high a11y, <48dp) |
| 5 | RadioGroup descriptive children | `fragment_settings_compute.xml:48-73`, `fragment_settings_output.xml:42-61` | Move hint `TextView`s out of `RadioGroup`; wrap each radio+desc pair in a sibling LinearLayout |
| 6 | Drop deprecated `android:editable` | `fragment_settings_language.xml:41-42` (+ output/translate/style dropdowns) | Remove; rely on `inputType="none"` on `MaterialAutoCompleteTextView` |
| 7 | Standardize secondary text color | `view_settings_row.xml:36-37,42-47`, `item_model_card.xml:75,84`, `fragment_settings_threads.xml:73-74` | `?android:attr/textColorSecondary`/`Tertiary` → `?attr/colorOnSurfaceVariant` |
| 8 | FAB clearance dimension | `fragment_home.xml:26` | Add `<dimen name="fab_clearance">96dp</dimen>`; replace literal |
| 9 | Subtitle preview color resources | `fragment_settings_style.xml:73,81` | Add `subtitle_preview_white`/`subtitle_preview_yellow` to `colors.xml` |
| 10 | Add `tools:context` to settings layouts | `fragment_settings_*.xml`, `view_*.xml` | One per root |
| 11 | `imeOptions` on credential fields | `fragment_settings_translate.xml:72-201` | `actionNext` on intermediate, `actionDone` on last per provider |
| 12 | FGS notification flags | `service/VideoProcessingService.kt` | Add `.setOnlyAlertOnce(true)` and `.setOngoing(true)` |
| 13 | Cancel confirmation | `ui/progress/ProgressFragment.kt:39` | Wrap `viewModel.cancel()` in `MaterialAlertDialogBuilder` when running |
| 14 | Real `confirmRestore` dialog | `ui/editor/EditorFragment.kt:119-121` | Add `MaterialAlertDialogBuilder` around `viewModel.restoreOriginal()` (high interaction) |

## Medium projects (multi-hour, single concern)

### 1. Spacing/dimens scale + color resource extraction

Create `values/dimens.xml` with a named scale (`spacing_xs=4dp`, `spacing_sm=8dp`, `spacing_md=12dp`, `spacing_lg=16dp`, `spacing_xl=24dp`, plus component dims `fab_clearance=96dp`, `thumbnail_height=180dp`, `settings_row_height=56dp`, `radio_desc_indent=32dp`, `screen_padding=24dp`). Sweep all layouts to replace raw dp literals.

Steps:
1. Audit current literals (counts: 12dp ×35, 16dp ×21, 8dp ×20, 24dp ×9, etc.).
2. Define the scale in `values/dimens.xml`.
3. Replace literals file-by-file starting with high-traffic layouts (`fragment_progress.xml`, `fragment_home.xml`, `item_task.xml`, `item_segment.xml`, `item_model_card.xml`).
4. Move slider `valueFrom`/`valueTo` magic numbers (16/60, -200/200, 0/200) to integer resources.
5. Add `@dimen/fab_clearance` to RecyclerView `paddingBottom` in `fragment_home.xml:26`.

### 2. MDC 1.14.0 + Theme.Material3.Expressive + Material You

1. Bump `material` in `libs.versions.toml` to 1.14.0.
2. Run [Material Theme Builder](https://material-foundation.github.io/material-theme-builder/), export Android Views, drop into `res/values{,-night}/`.
3. Switch parent in `themes.xml` to `Theme.Material3.Expressive.NoActionBar`; verify all child styles still resolve (`SettingsGroupCard`, etc.).
4. Add `DynamicColors.applyToActivitiesIfAvailable(this)` in `VideoSubtitleApp.onCreate` next to `FFmpegKitConfig.setFontDirectoryList`.
5. Add `HarmonizedColors.applyToContextIfAvailable` for the stage accent colors (extracting/transcribing/burning/failed).
6. Swap `textAppearance` overrides on Editor segment list and Progress stage label to `?attr/textAppearanceTitleLargeEmphasized`.

### 3. Edge-to-edge + predictive back hardening for Android 16

Forced by `targetSdk 36`: edge-to-edge can no longer be opted out, `KEYCODE_BACK` and `onBackPressed` stop firing.

1. Bump `androidx.core` to 1.16.0+ for `ProtectionLayout`.
2. Add `ViewGroupCompat.installCompatInsetsDispatch` in `MainActivity.onCreate` (minSdk 26 means we cross the API 29 boundary).
3. Sweep fragments — Home FAB, Progress action stack, Editor RecyclerView `paddingBottom`, Settings — for inset listeners on `systemBars()`+`displayCutout()`.
4. Add `android:fitsSystemWindows="true"` on `AppBarLayout`s.
5. Confirm `OnBackPressedCallback` is attached in `EditorFragment` to `viewLifecycleOwner`; add `currentDestination==editorFragment` guard inside `handleBack` to prevent double-pop.

### 4. Error classification layer (i18n contamination fix)

Introduce a domain enum `AppErrorCode` (`CHECKSUM_MISMATCH`, `NETWORK_TIMEOUT`, `MISSING_MODEL`, `NO_AUDIO_TRACK`, `GPU_INIT_FALLBACK`, `TRANSLATION_AUTH`, `TRANSLATION_QUOTA`, `FGS_TIMEOUT`, `OOM`, `CANCELLED`, `UNKNOWN`). Map throw sites in `TaskOrchestrator.kt:270,311,409,469`, `DefaultModelRepository.kt:104,111,116`, `MicrosoftTranslationEngine.kt:85-98` (+ Baidu/Tencent/Youdao/MlKit). Resolve to localized strings at the UI boundary; log raw `t.message` via Timber.

Closes 3 of the 4 "high" i18n findings in one sweep.

### 5. Model download UX hardening (`ModelRepository`)

1. `MeteredNetworkChecker` injecting `ConnectivityManager`.
2. Settings toggle "Download models on Wi-Fi only" backed by `SettingsDataStore`.
3. Pre-download size + network-type confirm `MaterialAlertDialog` ("this will download 142 MB on cellular").
4. Refactor `DefaultModelRepository` to write to `<name>.bin.partial`, `File.renameTo` on SHA-256 verify.
5. Range request support for resume-on-retry (existing OkHttp client supports it).
6. Map size/SHA mismatch to `AppErrorCode.CHECKSUM_MISMATCH` with localized "Downloaded file is corrupted, please retry" / "下载的文件已损坏，请重试".

### 6. Navigation polish: shared motion + deep links + one-shot effect channels

1. Define `res/transition/nav_forward.xml` + `nav_back.xml` referencing `?attr/motionEasingEmphasizedInterpolator` and `?attr/motionDurationLong2`.
2. Apply `app:enterAnim`/`exitAnim`/`popEnterAnim`/`popExitAnim` on every `<action>` in `nav_main.xml`.
3. Add `<deepLink>` to `progressFragment` with `app://videosubtitle/progress/{taskId}`.
4. Build `PendingIntent` in `VideoProcessingService` via `NavDeepLinkBuilder` so notification tap lands on the live progress, not Home.
5. Refactor `ProgressViewModel.navigatedToEditor` field into `ProgressEffect.NavigateToEditor` on `Channel<ProgressEffect>(BUFFERED).receiveAsFlow()`.
6. Refactor `HomeUiState.errorMessage` → `HomeEffect.ShowError` channel. (`EditorViewModel` is already correct — copy it.)

## Big bets (own design + plan cycle)

### 1. Editor v2: drag/swipe/split/merge + audio scrubbing

The audit's most damning finding: `SegmentAdapter` has zero gesture support, no add/delete/insert, no split/merge, no per-edit undo (`EditorViewModel.kt:76-109`). Whisper boundary errors dominate ASR mistakes — split/merge are higher-value than character-level edits.

- New adapter: drag handle, swipe-to-delete with Snackbar undo.
- New `EditorEffect` for undo.
- Media3 `ExoPlayer` integrated against existing per-task `audio.wav` artifact: `seekTo(start)` + `stop(end)` on row tap.
- Gesture spec: long-press → cursor split; swipe-up → merge with next.
- `FloatingToolbarLayout` (MDC 1.14) for split/merge/delete actions.

**Risks:** ExoPlayer dependency weight; RecyclerView ↔ player seek-range sync; drag-reorder vs click-to-edit conflict (use drag-handle pattern).

### 2. M3 Expressive theming pass with Dynamic Color + brand palette

`themes.xml:3-6` confirms baseline purple, no `colorPrimary`, no `values-night/colors.xml`, no `DynamicColors` call. Cascades through the app: bump MDC, run Theme Builder, drop tokens, switch parent, enable `DynamicColors`, call `HarmonizedColors`, verify every screen.

**Risks:** Tokens cascade — verify hardcoded `#FFFFFFFF`/`#FFFFFF00` in `fragment_settings_style.xml:73,81` (literal subtitle colors) are NOT swept into the dynamic palette. Re-test contrast on `item_segment.xml` `time_range` (audit flagged borderline 4.5:1).

### 3. Adaptive layouts for Android 16 sw600dp+ behavior change

Mandatory: on Android 16 `targetSdk 36`, `screenOrientation`/`resizableActivity`/`setRequestedOrientation` are IGNORED on sw600dp+. Editor (segment list + future preview) and Home (task list + progress detail) both have natural two-pane shapes.

Use `SlidingPaneLayout` + `WindowSizeClass` via `androidx.window:window 1.3+`. Best done after Editor v2 lands so the preview pane shape is settled.

**Risks:** Couples with Editor v2; deciding the two-pane Home/Progress relationship may invalidate Progress' standalone deep-link target.

### 4. Settings architecture: PreferenceFragmentCompat or shared component layout

8 settings fragments with hand-rolled XML, repeated boilerplate (4 near-identical provider credential blocks in `fragment_settings_translate.xml:61-203`; `ExposedDropdown` duplications across language/output/translate/style; `RadioGroup`-with-interleaved-`TextView` a11y bug repeated in compute and output).

Migrate to AndroidX `Preference` + `PreferenceFragmentCompat` backed by a custom DataStore adapter against existing `SettingsRepository`. Eliminates an entire class of audit findings at once.

**Risks:** Live subtitle preview in `fragment_settings_style.xml` is bespoke and would remain custom. Translation credentials with conditional visibility need provider-aware preference logic. May not be worth it if bespoke designs are intentional brand expression.

## Flow-specific fixes

### Home

- Replace `app:srcCompat="@android:drawable/ic_input_add"` on the FAB (`fragment_home.xml:38-45`) with Material vector `@drawable/ic_add`.
- Migrate video picker from SAF to `ActivityResultContracts.PickVisualMedia(VideoOnly)` in `HomeFragment` — permission-free, better thumbnails, auto-fallback on minSdk 26. Wire existing `copyToCache` on the resulting `Uri`.
- Wire `state.isLoading` (`HomeFragment.kt:61,77`) to `StateView.showLoading()` so import is no longer silent.
- Convert `HomeUiState.errorMessage` to `Channel<HomeEffect>` matching `EditorViewModel`; eliminates consume-after-show race; allows Snackbar with Retry.
- Convert `home_selection_title` and `home_delete_confirm_message` to `<plurals>` (English one/other; Chinese other only). Resolve via `resources.getQuantityString` in `HomeFragment.kt:114,146`.
- `view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)` on long-press selection mode (`TaskListAdapter:81`); `announceForAccessibility(...)` for mode change.
- Composite `contentDescription` on `item_task.xml` row root including selection state; toggle in `bindSelection()` so TalkBack announces selected/not selected (currently checkmark is `contentDescription=@null` and `root.isActivated` alone is silent to TalkBack).

### Progress

- Wrap `fragment_progress.xml` content in `NestedScrollView` — at large font scale the 5 stacked buttons (start/cancel/open/regenerate/edit) become unreachable. **High** severity.
- Replace generic `ProgressBar`/`LinearProgressIndicator` with MDC 1.14 `LoadingIndicator` for indeterminate transcribe phases.
- 4-step stepper (Extracting / Transcribing / Editing / Burning) above the bar with active step bolded in `?attr/textAppearanceTitleLargeEmphasized`. Multi-phase pipelines benefit more from stage clarity than from a single combined %.
- Replace precise ETA with fuzzy "About a minute" / "About 3 minutes" — Whisper speed varies wildly across Tiny/Base/Small + Vulkan/CPU fallback.
- Smooth transcribe percent so it doesn't stall at 95-99% — clamp updates with a moving floor.
- Confirmation `MaterialAlertDialog` before `viewModel.cancel()` (`ProgressFragment.kt:39`) — currently a single tap discards minutes of work.
- Replace one-shot navigation guard `navigatedToEditor` with `Channel<ProgressEffect>` like `EditorViewModel` — eliminates StateFlow-as-event anti-pattern and the double-navigation race.
- Add `<deepLink>` to `progressFragment` in `nav_main.xml`; rebuild FGS notification's `contentIntent` via `NavDeepLinkBuilder` so tapping the notification lands on the live task.
- `StateView.showError("task not found")` with navigate-up when `state.task` is null (`ProgressFragment.kt:60-61`), instead of returning silently.
- `setOnlyAlertOnce(true)` + `setOngoing(true)` on rolling FGS notification builder; verify the 10s auto-burn timer (commit `f0d5242`) shows visible countdown chip with explicit Cancel.
- `accessibilityLiveRegion="polite"` on `stage_label` and `model_label` so percent updates are announced; give progress bars a `contentDescription` reflecting current percent.
- Implement `Service.onTimeout` for the dataSync FGS so the 6h/day Android-15+ cap fails the task with `error_fgs_timeout_reached` rather than an opaque OS kill.

### Editor

- `ItemTouchHelper` on `binding.segments` for drag-reorder + swipe-to-delete with Snackbar undo (audit **high** — currently zero gesture support).
- Split (long-press at cursor) and Merge (swipe-up) actions; expose via `FloatingToolbarLayout` (MDC 1.14) at the bottom so verbs are primary, not buried in dialogs.
- ExoPlayer audio scrub on row tap using existing `audio.wav` per-task artifact — `seekTo(start)` then `stop(end)`.
- Make `confirmRestore()` (`EditorFragment.kt:119-121`) actually show a `MaterialAlertDialog` before calling `restoreOriginal()`.
- After `updateText`/`updateTime`, send `EditorEffect.UndoSnackbar(priorSegment)` — per-edit undo separate from destructive "restore original".
- Auto-save per row commit + Undo Snackbar pattern, leveraging existing `subtitle.srt` + `.original.srt` backing.
- Make 10s auto-burn countdown visible in editor as a chip ("Burning in 7s — Tap to cancel"); silent timers feel hostile.
- Group `item_segment.xml` row as a single a11y node with synthesized `contentDescription` ("Segment 1, 00:00:01 to 00:00:02, <text>, double tap to edit"); replace literal `→` with localized "to"; remove inner `selectableItemBackground` on `time_range`/`text` or give them `minHeight 48dp`.
- `StateView.showLoading()` while `loaded==false` (`EditorFragment.kt:78-88`); distinguish parse-failure from genuinely-empty SRT in `EditorUiState`.
- Add `hint` on the `TextInputLayout` in `showTextDialog` (`EditorFragment.kt:123-191`); request initial focus + show IME — currently TalkBack announces unlabeled edit field.
- Replace Toast with Snackbar for save/restore confirmations.

### Settings

- Fix `android:textSize="16dp"` → `16sp` in `fragment_settings_style.xml:141,229,339` (**high** a11y, breaks user font-scale).
- Move descriptive `TextView`s OUT of `RadioGroup` in `fragment_settings_compute.xml:48-73` and `fragment_settings_output.xml:42-61` (medium a11y).
- Replace `app:passwordToggleEnabled="true"` with `app:endIconMode="password_toggle"` in `fragment_settings_translate.xml:84,117,150,183`.
- Extract Baidu/Youdao/Tencent/Microsoft credential blocks (`fragment_settings_translate.xml:61-203`) into a single `<include>` layout (or `ViewStub` for hidden providers) — eliminates 4-way duplication.
- `android:imeOptions="actionNext"` between credential fields, `actionDone` on last.
- Replace `?android:attr/textColorSecondary` with `?attr/colorOnSurfaceVariant` across `view_settings_row.xml:36-37`, `item_model_card.xml:75-84`, `fragment_settings_threads.xml:73`.
- `tools:context=".ui.settings.XxxFragment"` on each `settings_*` layout root.
- Hide or replace transformer-architecture jargon in `settings_model_params` ("layers · width · heads") with user-relevant facts (download size, RAM, expected speed).
- Soften technical jargon: "Whisper" → "on-device speech recognition"; "mov_text" → "a compatible player"; "wrong tokens" → "incorrect text".
- Replace `android:alpha="0.85"` on hero subtitle (`fragment_settings.xml:81`) with a properly-toned color role.

## Suggested 5-day sequence

| Day | Hours | Work |
|-----|-------|------|
| 1 | ~2h | **Quick-win sweep** — `textSize=16dp` → `16sp`; replace framework FAB drawable; `passwordToggleEnabled` → `endIconMode`; move RadioGroup-interleaved TextViews out; restore `minHeight=48dp` on `item_model_card` `btn_action`. XML-only, no code touches. |
| 2 | ~3h | **Confirms + scroll** — wrap `fragment_progress.xml` in `NestedScrollView`; `MaterialAlertDialog` confirm before `viewModel.cancel()` and `restoreOriginal()`; `setOnlyAlertOnce(true)` + `setOngoing(true)` on FGS notification. |
| 3 | ~4h | **Penpot mockups** — Editor v2 three states (canonical / swipe-delete / split). Compare against Seal and MyBrain notes editor as visual references. Save as `editor-v2-canonical / editor-v2-swipe / editor-v2-split`. |
| 4 | ~3h | **MDC 1.14 + theme** — bump `material` to 1.14.0 in `libs.versions.toml`; Material Theme Builder export with brand seed; drop into `values{,-night}/`; switch parent to `Theme.Material3.Expressive.NoActionBar`; `DynamicColors.applyToActivitiesIfAvailable` in `VideoSubtitleApp.onCreate`. Verify child styles + `item_segment` time_range contrast in light + dark. |
| 5 | ~5h | **Editor v2 phase 1** — `ItemTouchHelper` for drag-reorder + swipe-to-delete with Snackbar undo (uses `EditorEffect.UndoSnackbar` carrying prior list); `EditorEffect.UndoSnackbar` for per-edit undo after `updateText`/`updateTime`; gate `restoreOriginal()` behind real `MaterialAlertDialog`. ExoPlayer audio scrub + Split/Merge land in week 2 — document `FloatingToolbarLayout` integration in `docs/SDD.md` as planned. |

## References

- MDC 1.14.0 release: https://github.com/material-components/material-components-android/releases
- Material Theme Builder: https://material-foundation.github.io/material-theme-builder/
- Edge-to-edge enforcement: https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Android 16 behavior changes: https://developer.android.com/about/versions/16/behavior-changes-16
- Penpot: https://penpot.app
- Visual references: [Seal](https://github.com/JunkFood02/Seal), [NewPipe](https://github.com/TeamNewPipe/NewPipe), [AntennaPod](https://github.com/AntennaPod/AntennaPod)
