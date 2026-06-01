# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android application `com.frank.videosubtitle` (VideoSubtitle). Goal: pick a local video → generate subtitles on-device with Whisper → burn subtitles back into the video with FFmpeg. Currently a scaffolded single-Activity app (`MainActivity` + `activity_main.xml`); feature code has not been built yet.

## Authoritative design docs (read these first)

- [`docs/SDD.md`](./docs/SDD.md) — Spec/Design Document. Layered MVVM architecture, tech stack decisions (XML+ViewBinding, FFmpegKit, whisper-jni), end-to-end pipeline, data model, risks. **All non-trivial work should align with this doc; if you intentionally diverge, update SDD in the same change.**
- [`docs/IMPLEMENTATION_PLAN.md`](./docs/IMPLEMENTATION_PLAN.md) — Phased build-out (Phase 0 scaffold → 1 picker → 2 audio → 3 Whisper → 4 editor → 5 burn → 6 background → 7 settings). Each phase has acceptance criteria; do not start a phase before its predecessor's criteria are met.
- Reference implementation (Python desktop): `/Users/guohongcheng/code/android_project/VideoCaptioner` — its `videocaptioner/core/asr/whisper_cpp.py` and `core/utils/video_utils.py` define the FFmpeg / Whisper command shapes the Android version mirrors.

## Architecture conventions (load-bearing)

- **MVVM, single-Activity + Navigation Component + Fragments.** UI state is `StateFlow<UiState>` from ViewModels; one-shot events go through `SharedFlow`/`Channel`, never inside `UiState`.
- **Long-running work belongs to repositories, not ViewModels.** `TaskRepository` (application-scoped) owns transcription/burn task state so it survives ViewModel recreation and process death; ViewModels are projections.
- **Domain layer has no Android types.** Repositories convert `Uri` → cached `File` at the boundary. Use cases take/return primitives, `File`, and domain models only.
- **Pipeline steps are idempotent on disk.** Each phase writes to `cacheDir/tasks/<taskId>/` (`source.<ext>`, `audio.wav`, `subtitle.srt`, etc.) so a killed/restored task can skip already-completed steps.

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
