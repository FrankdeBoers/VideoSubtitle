# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android application `com.frank.videosubtitle` (VideoSubtitle). Goal: pick a local video → generate subtitles on-device with Whisper → burn subtitles back into the video with FFmpeg.

**Current state: end of Phase 2** — Phase 1 + audio extraction via FFmpegKit (`6.0.LTS`, sourced from Aliyun/HuaweiCloud Maven mirrors after FFmpegKit was archived from Maven Central). `TaskOrchestrator` runs the pipeline on an application-scoped coroutine and writes progress to Room. `ProgressFragment` observes per-task state. No Whisper / burn yet.

## Authoritative design docs (read these first)

- [`docs/SDD.md`](./docs/SDD.md) — Spec/Design Document. Layered MVVM architecture, tech stack decisions (XML+ViewBinding, FFmpegKit, whisper-jni), end-to-end pipeline, data model, risks. **All non-trivial work should align with this doc; if you intentionally diverge, update SDD in the same change.**
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
app/src/main/java/com/frank/videosubtitle/
├── VideoSubtitleApp.kt        Application + Koin startup + Timber
├── MainActivity.kt            Single Activity, hosts NavHostFragment, edge-to-edge
├── di/
│   ├── AppModule.kt           DispatcherProvider, applicationScope (SupervisorJob+IO)
│   ├── DataModule.kt          Room, repos, FFmpegKitEngine, ExtractAudioUseCase, TaskOrchestrator
│   └── UiModule.kt            ViewModels (HomeViewModel, ProgressViewModel)
├── domain/
│   ├── model/                 VideoMeta, TaskStage (sealed), TaskState
│   ├── engine/                FFmpegEngine (interface), FfmpegProgress, FfmpegException
│   └── usecase/               ExtractAudioUseCase (idempotent on existing audio.wav)
├── data/
│   ├── engine/FFmpegKitEngine wraps FFmpegKit.executeAsync via callbackFlow
│   ├── orchestrator/          TaskOrchestrator (app-scoped pipeline driver)
│   ├── repository/            TaskRepository, VideoRepository
│   └── source/
│       ├── local/             Room: TaskEntity (flat columns), TaskDao,
│       │                       AppDatabase, TaskMappers (StageKind: stable strings)
│       └── media/UriResolver  copyToCache + MediaMetadataRetriever probe + thumb
├── util/{DispatcherProvider, AppError, DomainResult}.kt
└── ui/
    ├── common/BaseFragment.kt
    ├── home/                  HomeFragment (FAB+SAF picker), HomeViewModel,
    │                           HomeUiState, TaskListAdapter (Coil for thumb)
    └── progress/              ProgressFragment (taskId arg), ProgressViewModel,
                                ProgressUiState; observes TaskRepository
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
