# GPU Acceleration Plan — Whisper Transcription on Android

Status: **Proposal** · Owner: TBD · Target phase: **Phase 8** (post‑1.0 feature)
Today: 2026‑06‑02 · Whisper.cpp vendored version: **v1.7.5** · Current backend: **CPU‑only** (`GGML_USE_CPU`)

> Goal: cut the **Transcribing** stage time on supported devices (Adreno 6xx+, Mali G‑78+, Xclipse, Apple‑style ARM with AVX… n/a) by routing the encoder/decoder matmuls through a GPU backend, while keeping CPU as a guaranteed fallback. Targeted speed‑up on Snapdragon 8 Gen 1+: **2–4× on Tiny/Base, 1.5–2.5× on Small** based on community benchmarks of ggml‑opencl/Vulkan with Whisper.

---

## 1. Why now / what changes

The current setup ([`app/src/main/cpp/CMakeLists.txt`](../app/src/main/cpp/CMakeLists.txt)) compiles `ggml-cpu` only and defines `GGML_USE_CPU`. Whisper transcription is the dominant wall‑clock contributor in the pipeline (see `WhisperJniEngine` and the 36→69% progress freeze the user already reported). Adding a GPU backend doesn't change the public `WhisperEngine` API — `whisper_init_from_file_with_params` accepts a `whisper_context_params` struct with `use_gpu`/`gpu_device` fields that route work to whichever backend was registered at link time.

What we **do not** change:
- `domain/engine/WhisperEngine` interface
- `WhisperLib` JNI surface (we add a single optional `useGpu: Boolean`)
- Pipeline orchestration, idempotent disk layout, settings schema

What we **do** change:
- CMake build adds an opt‑in backend (Vulkan first, OpenCL second)
- `whisper_jni.c` passes `params.use_gpu` from the JNI caller
- A new Settings entry: **Compute** = Auto / CPU / GPU (default Auto)
- `SettingsRepository`/DataStore key + ViewModel projection

---

## 2. Backend choice — Vulkan first, OpenCL as a Snapdragon‑specific accelerator

| Backend | Coverage on Android | Maintenance in whisper.cpp | Build complexity | Notes |
|---|---|---|---|---|
| **Vulkan** (`ggml-vulkan`) | Wide — Adreno 6xx+, Mali G76+, Xclipse, PowerVR. Required by Android 10+ vendor cert on most SoCs | Active, first‑class | Medium — needs `glslc` + runtime SPIR‑V; NDK r25+ ships the Vulkan loader | **Recommended primary** — best portability/effort ratio |
| **OpenCL** (`ggml-opencl`) | Excellent on Adreno (rewritten 2024 by Qualcomm); patchy on Mali (driver dependent); ~zero on Tensor (Pixel) | Active (Adreno‑optimised path) | Medium — `libOpenCL.so` not in NDK; load via `dlopen` of vendor stub or ship `libOpenCL-loader` | **Phase B** — opt‑in for Snapdragon devices once Vulkan ships and we can A/B perf |
| OpenCL via Qualcomm QNN | SoC‑locked | Out‑of‑tree fork | High | Rejected — too narrow |
| Vulkan via Kompute (`ggml-kompute`) | Same coverage as Vulkan | Less active than `ggml-vulkan` | Higher | Rejected — `ggml-vulkan` is upstream's preferred Vulkan path |
| NNAPI | Android only | **No backend exists in whisper.cpp** | Very high (write from scratch) | Rejected — out of scope |

Decision: **Phase A** ships Vulkan, **Phase B** adds OpenCL as a runtime alternative on Adreno. Both can coexist — `ggml-backend-reg` registers whichever was compiled in, and we pick at runtime via the new Compute setting.

---

## 3. Vendoring & build (Phase A — Vulkan)

The vendored subset under `app/src/main/cpp/whisper.cpp/` currently has **only** `ggml/src/ggml-cpu/`. We need to add `ggml/src/ggml-vulkan/` from the same upstream tag (v1.7.5) — sources only, no extra deps.

Concretely:

1. **Vendor backend sources.** Copy `ggml/src/ggml-vulkan/{ggml-vulkan.cpp, vulkan-shaders/*}` from whisper.cpp v1.7.5 into the existing tree. Keep the v1.7.5 commit pin — do **not** mix versions.
2. **Shaders → SPIR‑V at build time.** `vulkan-shaders/` are GLSL compute shaders. Upstream provides a `vulkan-shaders-gen` host tool that pre‑compiles them to a single embedded header. Add a CMake `add_executable(vulkan-shaders-gen …)` built for the **host** (not Android) via an `ExternalProject_Add` or a dedicated `if (CMAKE_CROSSCOMPILING)` host‑build pass — same pattern upstream uses. Output: `ggml-vulkan-shaders.hpp` linked into `libwhisper.so`.
3. **CMake gating.** New option `WHISPER_VULKAN` (default ON). Guard everything in `CMakeLists.txt`:
   ```cmake
   option(WHISPER_VULKAN "Build Vulkan backend" ON)
   if (WHISPER_VULKAN)
     find_package(Vulkan REQUIRED)             # NDK r23+ ships this find module
     list(APPEND SOURCE_FILES
       ${WHISPER_LIB_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp)
     target_compile_definitions(whisper PRIVATE GGML_USE_VULKAN)
     target_link_libraries(whisper Vulkan::Vulkan)
   endif()
   ```
4. **APK size budget.** Vulkan backend adds ~600–900 KB per ABI to `libwhisper.so` (compressed) plus ~200–400 KB of embedded SPIR‑V. With both ABIs (`arm64-v8a`, `armeabi-v7a`), expect **~+2 MB** on the release APK (current ~31 MB → ~33 MB, well under the 80 MB ceiling in CLAUDE.md).
5. **NDK / minSdk.** Vulkan loader is in NDK r25+; project uses NDK from AGP 9.1.1 default (r26+) — fine. `minSdk=26` already satisfies Vulkan 1.0 availability (API 24+); the runtime check still has to confirm the device exposes a queue family with compute. **Do not raise `minSdk`** — just degrade gracefully.

### Phase B (OpenCL on Adreno)

Same shape, conditionally included via `WHISPER_OPENCL=ON`. Two extra wrinkles:
- `libOpenCL.so` is **not** in the NDK. Either ship Qualcomm's redistributable loader (`libOpenCL-loader.so` from KhronosGroup/OpenCL-ICD-Loader) or open `libOpenCL.so` via `dlopen` and resolve `clGetPlatformIDs` etc. at runtime. The latter avoids a hard dependency on a vendor file at install time and is what whisper.cpp's android example does.
- Adreno path needs `CL_QCOM_…` extensions for best perf — detect at runtime, fall back to standard OpenCL 3.0 path otherwise.

---

## 4. JNI / runtime wiring

`whisper_context_params` already has `use_gpu` and `gpu_device` (default device 0). Plumb a single boolean through:

1. **`whisper_jni.c`** — add a parameter to `initContext`:
   ```c
   // initContextWithParams(modelPath, useGpu)
   struct whisper_context_params cparams = whisper_context_default_params();
   cparams.use_gpu = (use_gpu != 0);
   ctx = whisper_init_from_file_with_params(model_path_chars, cparams);
   ```
   Keep the old `initContext(modelPath)` symbol as a thin wrapper for source compatibility — it calls the new variant with `use_gpu = false`. JNI symbol stability matters per the existing comment in `whisper_jni.c`.
2. **`WhisperLib.kt`** — add `external fun initContextWithParams(modelPath: String, useGpu: Boolean): Long`.
3. **`WhisperJniEngine`** — read a new `WhisperConfig.computeMode` (Auto/CPU/GPU) and pass the resolved boolean. On `initContextWithParams` returning 0 with `useGpu=true`, log + retry once with `useGpu=false` (graceful fallback) before surfacing `WhisperException`. Emit a one‑shot `TranscribeEvent.Info("Falling back to CPU: <reason>")` so the UI can show a subtle hint.
4. **Capability probe.** Add `external fun gpuAvailable(): Boolean` that returns true iff the linked backend reports ≥1 usable device. Implementation: call `ggml_backend_vk_get_device_count()` (or the OpenCL equivalent) and return `> 0`. Used by the Settings UI to enable/disable the GPU radio without trying to init a context.

---

## 5. Settings & UX

Add to `SettingsRepository`/`SettingsDataStore`:

```
key: compute_mode          // "auto" | "cpu" | "gpu", default "auto"
```

`TaskOrchestrator.start` reads it alongside the existing `settingsRepository.current()` call and passes it through `WhisperConfig`. Auto = "GPU if `gpuAvailable()` returns true, else CPU".

UI (Settings fragment):
- New section **Compute** with three radio options. Subtitle line under "GPU" reads either device GPU info ("Adreno 740 — Vulkan 1.3") or "Not supported on this device" — driven by `gpuAvailable()` and a small `gpuDeviceName()` JNI extra.
- Default to **Auto**. We do **not** flip existing users to GPU silently in case of driver bugs in older firmware.
- The existing **Threads** setting stays — even on GPU, ggml runs encoder pre/post and the decoder's softmax/feed‑forward partially on CPU, so threads still matter. No code change required there.

i18n strings needed (`values/strings.xml` + `values-en/strings.xml` parity):
`settings_compute_title`, `settings_compute_auto`, `settings_compute_cpu`, `settings_compute_gpu`, `settings_compute_gpu_unsupported`, `error_gpu_init_failed_falling_back`.

---

## 6. Edge cases & gotchas

- **Model size vs VRAM.** Whisper Tiny/Base fit easily on any GPU with ≥256 MB. Small q5_1 needs ~500 MB. On low‑memory devices the GPU backend will OOM — `whisper_init_from_file_with_params` returns null in that case; our retry‑on‑CPU path handles it.
- **First‑run shader compile.** Vulkan backends can pre‑compile pipelines on first use (~1–3s). User‑visible: the 0% phase of transcription will appear paused. Fix by warming the backend during model‑download "verifying…" stage if `compute_mode != "cpu"`.
- **Foreground service quotas.** GPU work doesn't change the FGS picture — still `dataSync` (see CLAUDE.md). Nothing to do.
- **Power/thermals.** GPU is faster but draws more peak power. We may want to log a warning if battery < 20% and offer to fall back to CPU; **deferred**, not in v1.0 of this feature.
- **`libc++_shared.so` already duplicated** with FFmpegKit (CLAUDE.md notes). Vulkan backend doesn't add a third — it links against the app build's libc++. No new packaging warnings expected.
- **AGP 9 / KSP guardrail** unchanged — this work is C++/CMake side, doesn't touch Kotlin source‑set wiring.
- **R8/ProGuard** — no new keep rules needed; the Vulkan loader is pure native, no reflection.
- **CI build time.** Cross‑compiling `vulkan-shaders-gen` for the host adds ~30s on a clean build. Acceptable.

---

## 7. Phased rollout & acceptance criteria

### Phase A — Vulkan backend (target: 2 weeks)

1. Vendor `ggml-vulkan` sources from upstream v1.7.5; commit shader sources; verify license headers preserved.
2. CMake: `WHISPER_VULKAN=ON` by default, host build of `vulkan-shaders-gen`, generated SPIR‑V header linked.
3. JNI: `initContextWithParams(modelPath, useGpu)` + `gpuAvailable()` + `gpuDeviceName()`; old `initContext` kept as wrapper.
4. Engine: `WhisperConfig.computeMode` propagated; CPU‑fallback path on init failure with one‑shot UI event.
5. Settings: `compute_mode` key, three‑option radio, device‑capability subtitle.
6. i18n parity (zh + en).
7. Bench harness: a debug‑build menu entry that runs Tiny+Base+Small over a fixed 30s WAV with each mode and logs wall‑clock.

**Acceptance:**
- Tiny/Base/Small all transcribe correctly with `compute_mode=gpu` on at least one Adreno (8 Gen 1+) and one Mali (G‑78+) device — output SRT byte‑identical to CPU within whisper's deterministic flag.
- Falling back to CPU on a non‑Vulkan emulator (x86_64 host) works without a crash; UI shows the hint string.
- Release APK ≤ 35 MB per ABI.
- No regression in CPU‑only path (`compute_mode=cpu`) — wall‑clock within ±3% of pre‑change baseline.

### Phase B — OpenCL backend (target: +1 week, opt‑in)

1. Vendor `ggml-opencl` sources from upstream v1.7.5.
2. CMake: `WHISPER_OPENCL=OFF` default; flip to `ON` once stable on Adreno.
3. Runtime `dlopen("libOpenCL.so")` loader; capability probe checks `CL_DEVICE_TYPE_GPU` presence.
4. Both backends registered with ggml; pick by `compute_mode=gpu` + a hidden `compute_backend` debug pref (Auto/Vulkan/OpenCL) — the user‑facing setting stays a single GPU toggle.

**Acceptance:**
- On a Snapdragon 8 Gen 2 device, OpenCL beats Vulkan on Whisper Small by ≥15% wall‑clock; otherwise we ship Phase B disabled by default.

### Phase C — Polish (deferred)

- Pre‑warm shaders during the model‑download "verifying…" step.
- Battery/thermal heuristic for auto‑downshift to CPU.
- Mirror‑URL setting for whisper model downloads (already deferred per CLAUDE.md) bundled into the same release.

---

## 8. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Driver bugs on older Mali firmware (G‑72/G‑76) producing wrong tokens | Medium | High (silent quality regression) | Default = Auto with a deny‑list of known‑bad GPU strings; CPU fallback on init failure; bench harness diff vs CPU output as gate |
| Shader‑compile delay surprises users | High | Low | Pre‑warm path; UI hint |
| `vulkan-shaders-gen` host build flakiness on CI runners without GLSL toolchain | Medium | Medium | Pin `glslc` from NDK prebuilt; document fallback to a pre‑generated header committed to the repo |
| OpenCL on non‑Adreno is slower than Vulkan | High | Low | Phase B is opt‑in; default GPU = Vulkan everywhere |
| APK size creep above 80 MB ceiling | Low | Medium | Per‑ABI splits already on; track size in CI |
| Upstream API drift if we later bump whisper.cpp past v1.7.5 | Medium | Medium | All backend vendoring pinned to the **same** whisper.cpp tag; bump in lockstep |

---

## 9. Out of scope

- NNAPI / ML Accelerator delegation (no whisper.cpp backend exists; would be a from‑scratch port).
- Per‑model GPU offload tuning (Whisper's `n_gpu_layers` analogue) — whisper.cpp's GPU backends are all‑or‑nothing for the matmul kernels; no per‑layer split today.
- Burn‑in pipeline GPU acceleration (FFmpeg / Media3 already run hardware‑accelerated decode/encode where the device supports it).
- Thermal throttling auto‑pause (Phase C above, intentionally deferred).

---

## 10. Touch list (files this plan will modify)

- `app/src/main/cpp/CMakeLists.txt` — add `WHISPER_VULKAN` option, host shader‑gen, link Vulkan
- `app/src/main/cpp/whisper.cpp/ggml/src/ggml-vulkan/**` — **new**, vendored
- `app/src/main/cpp/whisper_jni.c` — `initContextWithParams`, `gpuAvailable`, `gpuDeviceName`
- `app/src/main/java/com/whispercpp/whisper/WhisperLib.kt` — three new `external fun`
- `app/src/main/java/com/frank/videosubtitle/data/engine/WhisperJniEngine.kt` — read `computeMode`, fallback path, info event
- `app/src/main/java/com/frank/videosubtitle/domain/engine/WhisperConfig.kt` — `computeMode: ComputeMode` field
- `app/src/main/java/com/frank/videosubtitle/data/repository/SettingsRepository.kt` + `SettingsDataStore.kt` — new key
- `app/src/main/java/com/frank/videosubtitle/ui/settings/**` — Compute section
- `app/src/main/res/values/strings.xml` + `values-en/strings.xml` — 6 new strings
- `docs/SDD.md` — add §4.x "Compute backends"; mark Phase 8 entry
- `docs/IMPLEMENTATION_PLAN.md` — Phase 8 = GPU acceleration with the acceptance criteria above
