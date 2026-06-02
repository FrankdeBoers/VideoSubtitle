# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android application `com.frank.videosubtitle` (VideoSubtitle). Goal: pick a local video → generate subtitles on-device with Whisper → burn subtitles back into the video. Two media backends are wired and selectable via Settings → Output: `FFmpegKitEngine` (software, libass — full styling support) and `Media3TransformerEngine` (hardware, MediaCodec + Transformer OverlayEffect — faster, no per-line `{\fs..\c..}` overrides). `RoutingMediaEngine` picks per call from `settings.mediaBackend`; for HARD burn with distinct translated styling it falls back from AndroidMedia to FFmpeg automatically (libass-only feature).

**Current state: end of Phase 7** — Phase 1–6 + settings/UX/release polish. `service/VideoProcessingService` is a foreground service (no binding) that observes `TaskRepository.observeAll()`, posts a single rolling notification (title=video name, stage text, progress bar, Cancel action via PendingIntent → `ACTION_CANCEL` → `TaskOrchestrator.cancel`), and `stopSelf` once no task is in an in-progress stage. `TaskOrchestrator.start`/`startBurn` call `VideoProcessingService.start(context)` before launching pipeline coroutines on the application scope — the service keeps the process alive but does NOT own the work. API 34+ uses `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (we originally used `MEDIA_PROCESSING`, but Android 16 / targetSdk=36 rejects `mediaProcessing` FGS starts with `InvalidForegroundServiceTypeException("Starting FGS with type unknown ... has been prohibited")`; `dataSync` has the same 6h/day quota and no Android-16 start-state restriction); manifest declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (runtime prompt not yet wired). On app start, `VideoSubtitleApp` runs `TaskRepository.recoverInterrupted()`: any task left in {Extracting, Transcribing, Burning} is rewound to the closest completed step based on disk artifacts (`subtitle.srt`/`.original.srt` → Editing, `audio.wav` → Idle, otherwise Idle) — recovery never auto-resumes; the user re-runs explicitly. `TaskOrchestrator.hasEnoughSpace` (StatFs on `cacheDir`) gates Extract and Burn at `source.length() * 2`; on insufficient space the task fails with `error_insufficient_storage`. Settings live in Preferences DataStore via `SettingsRepository`/`SettingsDataStore`; `TaskOrchestrator.start`/`startBurn` read `settingsRepository.current()` at pipeline kick-off (model, language+initialPrompt, burnMode, preset, font size/color, outline, alignment) — repo is the source of truth, ViewModels project. `WhisperModel` enum has Tiny/Base/Small with verified upstream SHA-256. UX polish: shared `ui/common/StateView` (loading/empty/error with optional action) used by Home (empty) + Progress (Failed retry → branches on `subtitle.srt` presence to call `orchestrator.startBurn` vs `orchestrator.start`) + Editor (empty segments). i18n: `values/strings.xml` (zh, default) + `values-en/strings.xml` (English) — keep parity when adding strings. Release build is R8-shrunk + resource-shrunk; keep rules in `app/proguard-rules.pro` cover FFmpegKit/SmartException reflection, WhisperLib JNI symbols, Koin-resolved ViewModel constructors. arm64-v8a release APK ≈ 31 MB (target ≤ 80 MB). Release APK is unsigned (signing config deferred); Crashlytics/Sentry deferred to v1.1; mirror URL setting deferred.

## Authoritative design docs (read these first)

- [`docs/SDD.md`](./docs/SDD.md) — Spec/Design Document. Layered MVVM architecture, tech stack decisions (XML+ViewBinding, FFmpegKit, vendored whisper.cpp), end-to-end pipeline, data model, risks. **All non-trivial work should align with this doc; if you intentionally diverge, update SDD in the same change.**
- [`docs/IMPLEMENTATION_PLAN.md`](./docs/IMPLEMENTATION_PLAN.md) — Phased build-out (Phase 0 scaffold → 1 picker → 2 audio → 3 Whisper → 4 editor → 5 burn → 6 background → 7 settings). Each phase has acceptance criteria; do not start a phase before its predecessor's criteria are met.
- Reference implementation (Python desktop): `/Users/guohongcheng/code/android_project/VideoCaptioner` — its `videocaptioner/core/asr/whisper_cpp.py` and `core/utils/video_utils.py` define the FFmpeg / Whisper command shapes the Android version mirrors.

## Architecture conventions (load-bearing)

- **MVVM, single-Activity + Navigation Component + Fragments.** UI state is `StateFlow<UiState>` from ViewModels; one-shot events go through `SharedFlow`/`Channel`, never inside `UiState`.
- **Long-running work belongs to repositories, not ViewModels.** `TaskRepository` (application-scoped) owns transcription/burn task state so it survives ViewModel recreation and process death; ViewModels are projections.
- **Domain layer has no Android types.** Repositories convert `Uri` → cached `File` at the boundary. Use cases take/return primitives, `File`, and domain models only.
- **Pipeline steps are idempotent on disk.** Each phase writes to `cacheDir/tasks/<taskId>/` (`source.<ext>`, `audio.wav`, `subtitle.srt`, etc.) so a killed/restored task can skip already-completed steps.

## Toolchain gotchas (real, not hypothetical)

- **AGP 9.0+ has built-in Kotlin support.** Do NOT apply `org.jetbrains.kotlin.android` — it's actively rejected with `issuetracker.google.com/438678642`. Configure Kotlin via the `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }` block (importing `org.jetbrains.kotlin.gradle.dsl.JvmTarget`).
- **Hilt is not on Maven Central with AGP 9 support yet.** Latest published Hilt (2.56.2, 2025-04) fails with `Android BaseExtension not found` against AGP 9.x. The fix landed on `main` (dagger PR #5084, 2026-01-20) but isn't released. Project uses **Koin 4.1.0** instead. See `docs/SDD.md` §4.3.
- **`buildFeatures.buildConfig` is off by default in AGP 8+.** Must be explicitly `= true` to use `BuildConfig.DEBUG`.
- **ABI splits enabled** (`arm64-v8a`, `armeabi-v7a`). If you build on an x86_64 emulator (e.g. Intel Mac), add `x86_64` to the include list locally — don't commit it.
- **KSP + AGP 9 source-set guardrail.** AGP 9 disallows `kotlin.sourceSets` mutations, but KSP 2.0.x still registers its `build/generated/ksp/...` outputs that way. The opt-out `android.disallowKotlinSourceSets=false` in `gradle.properties` is required until KSP2 fully migrates. When KSP releases an AGP-9-clean version, drop the flag.
- **Room schema export** is wired through the `androidx.room` Gradle plugin (`room { schemaDirectory("$projectDir/schemas") }`). Bump `@Database(version = ...)` for any schema change and commit the generated JSON under `app/schemas/`.
- **FFmpegKit was pulled from Maven Central in 2025.** Author archived `arthenica/ffmpeg-kit`; `6.0.LTS` POM still lists in Central's index but the AAR/POM 404 on the CDN. The Aliyun (`maven.aliyun.com/repository/public`) and HuaweiCloud (`repo.huaweicloud.com/repository/maven`) mirrors still serve the cached AAR (SHA1 `4b3fc143f29a61044bb87b9c8dd80982d7b1c35b` matches across both). They're declared in `settings.gradle.kts` with `content { includeGroup("com.arthenica") }` so they're never consulted for anything else. If both mirrors stop serving it, fallbacks: self-host AAR in `app/libs/`, or pivot to Media3 Transformer.

- **libass needs explicit font-directory setup on Android.** ffmpeg-kit-full-gpl ships libass + fontconfig + freetype, but on Android fontconfig has no font dirs registered by default. Without setup, ffmpeg's `subtitles=` filter re-encodes the video successfully but renders every glyph blank — output looks like "burn ran but no subtitles." Fix lives in `VideoSubtitleApp.onCreate()` which calls `FFmpegKitConfig.setFontDirectoryList(this, listOf("/system/fonts"), mapOf("Arial" to "Roboto", "sans-serif" to "Roboto", "Helvetica" to "Roboto"))`. `FFmpegKitEngine.buildForceStyle()` also pins `Fontname=Roboto` so the SRT→ASS default style ("Arial", missing on Android) doesn't sneak through. If you ever support custom fonts, extend the directory list and the name map in lockstep.
- **whisper-jni doesn't ship Android binaries.** `io.github.givimad:whisper-jni:1.7.1` jar contains only desktop GLIBC `.so`/`.dylib`/`.dll` (`macos-*`, `debian-*`, `win-amd64`) — they fail to load on Android (bionic libc). Project vendors `ggerganov/whisper.cpp` v1.7.5 source under `app/src/main/cpp/whisper.cpp/` and builds via NDK + CMake. **Do not** add a `whisper-jni` dep — it'll just bloat the apk without doing anything.
- **whisper.cpp's `ggml-cpu.cpp` includes `amx/amx.h` unconditionally.** The Intel AMX backend implementations are guarded by `__AMX_INT8__` && `__AVX512VNNI__` (so they're empty TUs on ARM), but the header include itself isn't guarded. We vendor `ggml/src/ggml-cpu/amx/` so the include resolves and link to empty `amx.cpp`/`mmq.cpp` objects. If you trim further, this is the trip-wire.
- **Two `libc++_shared.so` in the APK.** FFmpegKit and our `libwhisper.so` both ship it; AGP picks the app build's copy and warns. Acceptable — both were built with the same NDK-shipped libc++.
- **AGP 9 disallows `splits.abi` + `defaultConfig.ndk.abiFilters` together.** Pick one. We use `splits.abi.include` only; CMake auto-builds for whatever ABI list `splits.abi` declares.

## Build & test commands

Use the Gradle wrapper from the repo root:

- Debug build: `./gradlew :app:assembleDebug`
- Release build: `./gradlew :app:assembleRelease`
- Install debug on connected device/emulator: `./gradlew :app:installDebug`
- Lint: `./gradlew :app:lint` (reports under `app/build/reports/lint-results-*.html`)
- Unit tests (JVM, `app/src/test`): `./gradlew :app:testDebugUnitTest`
- Instrumented tests (`app/src/androidTest`, requires device/emulator): `./gradlew :app:connectedDebugAndroidTest`
- Run a single unit test class: `./gradlew :app:testDebugUnitTest --tests "com.frank.videosubtitle.ExampleUnitTest"`
- Run a single instrumented test: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.frank.videosubtitle.ExampleInstrumentedTest`
- Clean: `./gradlew clean`

## Source layout (current)

```
app/src/main/cpp/
├── CMakeLists.txt             Single libwhisper.so target (ggml + whisper + ggml-cpu)
├── whisper_jni.c              JNI bridge with progress/abort extras struct
└── whisper.cpp/               Vendored upstream v1.7.5 minimal subset (~2.7 MB)

app/src/main/java/com/whispercpp/whisper/
└── WhisperLib.kt              Kotlin facade (object, no Companion → stable JNI symbols)

app/src/main/java/com/frank/videosubtitle/
├── VideoSubtitleApp.kt        Application + Koin startup + Timber
├── MainActivity.kt            Single Activity, hosts NavHostFragment, edge-to-edge
├── di/
│   ├── AppModule.kt           DispatcherProvider, applicationScope (SupervisorJob+IO)
│   ├── DataModule.kt          Room, repos, engines (FFmpeg + Whisper), use cases, TaskOrchestrator
│   └── UiModule.kt            ViewModels (HomeViewModel, ProgressViewModel, EditorViewModel)
├── domain/
│   ├── model/                 VideoMeta, TaskStage, TaskState, Subtitle, WhisperModel
│   ├── engine/                FFmpegEngine (+BurnOptions), WhisperEngine (interfaces), config + event types
│   └── usecase/               ExtractAudioUseCase, TranscribeAudioUseCase, BurnSubtitlesUseCase (idempotent)
├── data/
│   ├── engine/                FFmpegKitEngine (extractAudio + burnSubtitles), WhisperJniEngine, WavDecoder
│   ├── orchestrator/          TaskOrchestrator (start → extract → transcribe → editing → startBurn → done;
│   │                           StatFs disk-space gate; FGS lifecycle)
│   ├── repository/            TaskRepository (incl. recoverInterrupted sweep), VideoRepository,
│   │                           ModelRepository (OkHttp+SHA-256)
│   └── source/
│       ├── local/             Room (TaskEntity/Dao/AppDatabase/Mappers), SrtSerializer
│       └── media/                UriResolver (copyToCache + thumb),
│                                  MediaStoreSaver (Movies/VideoSubtitle output)
├── service/
│   └── VideoProcessingService.kt  Foreground service (dataSync on API 34+); observes
│                                   TaskRepository, posts rolling notification with cancel action,
│                                   stopSelf when no in-progress task remains
├── util/{DispatcherProvider, AppError, DomainResult}.kt
└── ui/
    ├── common/BaseFragment.kt
    ├── home/                  HomeFragment (FAB+SAF picker), HomeViewModel,
    │                           HomeUiState, TaskListAdapter (Coil for thumb)
    ├── progress/              ProgressFragment (taskId arg), ProgressViewModel,
    │                           ProgressUiState (incl. ModelStatus); observes TaskRepository,
    │                           one-shot navigates to editor when stage == Editing
    └── editor/                EditorFragment (taskId arg), EditorViewModel,
                                EditorUiState/Effect, SegmentAdapter (ListAdapter+DiffUtil)
```

`stageKind` strings (`idle/extracting/transcribing/editing/burning/done/failed`) are persisted in Room — renaming them is a schema break. `TaskMappersTest` pins them.

## Toolchain & SDK

- Android Gradle Plugin **9.1.1** — note this is a major version that uses the new Kotlin-DSL `compileSdk { version = release(36) { minorApiLevel = 1 } }` block (not the legacy `compileSdk = 34` shorthand). Preserve that form when editing `app/build.gradle.kts`.
- `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36.1`.
- Java/Kotlin source & target: **Java 11**.
- Kotlin code style: `official` (`gradle.properties`).
- `kotlin.code.style=official` is the only Kotlin setting; the Kotlin Gradle plugin is pulled in transitively via AGP — there is no separate `org.jetbrains.kotlin.android` plugin declaration.

## Dependency management

All versions and library coordinates live in `gradle/libs.versions.toml` (Gradle version catalog). Add new dependencies there first, then reference via `libs.xxx` in `app/build.gradle.kts` — do not hardcode group:name:version strings in build scripts.

## Module layout

Single-module project: root + `:app`. `settings.gradle.kts` enforces `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so any new repositories must be added to `dependencyResolutionManagement` in `settings.gradle.kts`, not in module-level `build.gradle.kts`.
