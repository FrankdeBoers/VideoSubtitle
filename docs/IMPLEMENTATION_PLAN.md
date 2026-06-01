# VideoSubtitle 实施计划

> 配套 [`SDD.md`](./SDD.md) 阅读。本文给出**按阶段（Phase）拆分的落地步骤**，每阶段都有：明确目标、关键交付物（代码 + 文档）、验收标准、风险点。
>
> 总原则：
> - **每阶段独立可验证**：跑通 + 通过该阶段的验收用例后再进入下一阶段。
> - **接口先行**：每个数据源 / Repository 先写接口和假实现（Fake），UI 用假数据先跑通，再替换真实实现。这样 UI 与底层并行不阻塞。
> - **代码到位即写测试**：domain / data 层的纯逻辑必须有 JVM 单测；UI 层只测 ViewModel 状态。
> - **小步提交**：每阶段拆成 3–6 个可独立编译的 commit，方便回滚。

---

## 阶段 0 — 工程基础（脚手架）

**目标**：把当前空 Android 项目升级成"能落地 MVVM + Hilt + Navigation"的脚手架，但不引入任何业务代码。

### 0.1 Gradle / 依赖（已落地）
- `gradle/libs.versions.toml` 已新增：`coroutines`, `lifecycle`, `navigation`, `koin`, `fragment`, `timber`，并保留 `coreKtx`/`appcompat`/`material`/`activity`/`constraintlayout`。
- `build.gradle.kts`（root）只挂 `android.application` + `navigation.safeargs` 两个 alias。
- AGP 9 内建 Kotlin 支持，**不再需要** `org.jetbrains.kotlin.android` 插件（应用会被官方插件主动拒绝，错误信息引用 issuetracker.google.com/438678642）。
- `app/build.gradle.kts`：
  - `buildFeatures { viewBinding = true; buildConfig = true }`（AGP 8.x 起 buildConfig 默认关闭，需显式开启以支持 `BuildConfig.DEBUG`，即使本阶段还没用上）。
  - 用新 DSL `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }`，`import org.jetbrains.kotlin.gradle.dsl.JvmTarget`。
  - ABI splits：`arm64-v8a`, `armeabi-v7a`，`isUniversalApk = false`。
  - **KSP 暂未引入**——Phase 0 没有 codegen 处理器（Koin 不需要 KSP）；Phase 1/3 引入 Room 时再加 `ksp` 插件。
  - 业务依赖：lifecycle / navigation / coroutines / fragment-ktx / koin-android + koin-androidx-navigation / timber。

### 0.2 应用骨架
- 新建 `VideoSubtitleApp : Application` 加 `@HiltAndroidApp`，并在 manifest 注册 `android:name=".VideoSubtitleApp"`。
- 新增 `res/navigation/nav_main.xml`，仅含 `homeFragment` 占位。
- 把 `MainActivity` 改成 `@AndroidEntryPoint` + `FragmentContainerView` + `NavHostFragment`。保留现有的 edge-to-edge / system bars padding 处理（应用到 NavHost 容器）。
- 新增 `ui/common/` 下的：
  - `BaseFragment<VB : ViewBinding>` —— 处理 ViewBinding 生命周期。
  - `UiState` 接口约定（每屏幕自定义 data class 实现）。
  - `DispatcherProvider`（IO/Default/Main 抽象，便于测试）。
  - `Result/AppError` sealed types（见 SDD §5.4）。

### 0.3 DI 模块占位（Koin）
- `di/AppModule.kt`：单一 Koin module（`val appModule = module { ... }`），目前只绑定 `DispatcherProvider`。后续 Repository / DataSource 直接添加 `single { ... } bind XxxRepository::class`。
- 进入 Phase 1 前，新增 `di/DataModule.kt` 拆分聚合，再在 `VideoSubtitleApp.startKoin { modules(appModule, dataModule) }` 中合并。

> **DI 历史决策**：原计划 Hilt，因 Hilt 当前 Maven 发布版（2.56.2）与 AGP 9 不兼容（`BaseExtension not found`），改用 Koin 4.1.0。详见 SDD §4.3。

### 0.4 Lint / 质量门禁
- 在 `app/build.gradle.kts` 启用 `lintOptions { warningsAsErrors = true; disable += listOf(...) }`（按需）。
- 添加 `detekt`（可选）。

### 验收
- `./gradlew :app:assembleDebug` 通过。
- 首页 Fragment 展示一个"Hello"占位即可。
- `./gradlew :app:testDebugUnitTest` 通过（含一个示例 `DispatcherProviderTest`）。

### 风险
- AGP 9.1 比较新，部分库的版本可能不兼容；遇到不兼容时**先记录到本文件 §风险表**，再做替换。

---

## 阶段 1 — 视频选择与首页 ✅ 已落地

**目标**：用户能从 SAF 选一个本地视频，App 解析其元数据并展示在首页"任务列表"中（此时还没有任何处理，仅是录入）。

**关键决策与坑（落地后追加）**：
- KSP `2.2.10-2.0.2` 与 AGP 9.1.1 的源集冲突：AGP 9 默认禁止 `kotlin.sourceSets` 修改，KSP 仍通过该 DSL 注册 `build/generated/ksp/...`，构建报 `Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`。在 `gradle.properties` 加 `android.disallowKotlinSourceSets=false` 解锁；待 KSP2 完成 AGP 9 迁移后移除。
- Room 引入了 `androidx.room` Gradle 插件 + `room { schemaDirectory("$projectDir/schemas") }`，schema 落到 `app/schemas/`，今后改 entity 必须 bump `@Database(version)` 并提交新 JSON。
- `TaskEntity` 用扁平列存（不存 JSON），stage 通过 `stageKind/stagePercent/stageMessage` 三列还原；`TaskMappers` 和 `TaskMappersTest` 把 stageKind 字符串钉死（`idle/extracting/transcribing/editing/burning/done/failed`），后续重命名相当于 schema 变更。
- ViewModel 通过 Koin 的 `viewModel { ... }` DSL（`org.koin.core.module.dsl.viewModel`）注册，Fragment 用 `by viewModel()` 注入。
- 缩略图用 Coil 3（`io.coil-kt.coil3:coil` + `coil-video`）。当前直接用 `MediaMetadataRetriever.frameAtTime` 抽出 JPEG 落到 `cacheDir/tasks/<id>/thumb.jpg` —— `coil-video` 暂未启用，但 Phase 4 编辑器抽帧时会派上用场。

### 1.1 VideoMeta 解析
- `data/source/media/UriResolver`：
  - `suspend fun copyToCache(uri: Uri): File`：流式拷贝到 `cacheDir/tasks/<taskId>/source.<ext>`。
  - `suspend fun probe(file: File): VideoMeta`：用 `MediaMetadataRetriever` 抓取 duration / width / height / bitrate / video codec / audio codec / audio sample rate。
- `VideoRepository.importVideo(uri)`：调用上述两步，返回 `VideoMeta`。

### 1.2 任务模型与持久化
- 新增 Room：`TaskEntity` 表，DAO 暴露 `insert / observeAll / observeById / update / delete`。
- `TaskRepository` 包装 DAO，对外暴露 `Flow<List<TaskState>>`。
- 任务初始 `stage = Idle`，导入时只填 `video` 字段。

### 1.3 首页 UI（HomeFragment + HomeViewModel）
- `HomeViewModel.uiState: StateFlow<HomeUiState>`，含 `tasks: List<TaskState>`。
- 一个"+"按钮触发 `ActivityResultContracts.OpenDocument(arrayOf("video/*"))`。
- 拿到 Uri 后：
  - 调 `contentResolver.takePersistableUriPermission(...)`。
  - 调 `VideoRepository.importVideo(uri)`。
- 任务列表用 RecyclerView，item 点击进入"模型选择 + 启动转写"（阶段 3 完成）。

### 验收
- 选 1 个视频，能在首页看到一行带缩略图 / 时长 / 文件名的 item。
- 杀掉 App 重开，列表仍在（Room 持久化生效）。

### 风险
- 不同厂商的 SAF 行为差异（小米/华为）。在测试设备清单中包含至少一台 OEM 机型。
- `MediaMetadataRetriever` 对部分 webm 文件返回 null —— 在解析失败时 fallback 到 ffprobe（FFmpegKit 自带），但 ffprobe 引入要等阶段 2。**阶段 1 先返回部分字段为 0**，不阻塞。

---

## 阶段 2 — 音频提取（FFmpegKit 接入）✅ 已落地

**目标**：把 `cacheDir/tasks/<id>/source.<ext>` 转成 `audio.wav`（16kHz / mono / PCM s16le），并在 UI 上展示进度。

### 2.1 引入 FFmpegKit
- **供应链坑（最重要）**：原计划写的 `6.0-2.LTS.1` 实际上**从未在 Maven Central 发布过**；作者于 2025 年初归档了 `arthenica/ffmpeg-kit` 并撤下了 Central 上的二进制。最后真实存在的版本是 `6.0.LTS`（2023-08），但 CDN 上 AAR 也已 404。
- 当前接入：在 `settings.gradle.kts` 添加阿里云 + 华为云 Maven 镜像，使用 `content { includeGroup("com.arthenica") }` 严格限制到该 group；这两家公共镜像仍缓存有 `ffmpeg-kit-full-gpl-6.0.LTS.aar`，SHA1 一致 (`4b3fc143f29a61044bb87b9c8dd80982d7b1c35b`)。`smart-exception-java:0.2.1` 仍在 Central。
- `libs.versions.toml` 中：`ffmpegKit = "6.0.LTS"`，`ffmpeg-kit-full-gpl = ...`。
- `proguard-rules.pro` 加 `-keep class com.arthenica.ffmpegkit.** { *; }` 和 `-keep class com.arthenica.smartexception.** { *; }`（JNI 桥与异常类）。
- 后续 v2 时若想脱离镜像：要么自托管 AAR 到 `app/libs/`（73MB+），要么换路径（Media3 Transformer / 自己 fork 出货）。先用镜像，把这一项作为已知技术债记录在 SDD §11。

### 2.2 FFmpegEngine 抽象（实际落地）
- 接口（`domain/engine/FFmpegEngine.kt`）只暴露 Flow，DomainResult 形态在内部体现为 Flow 的成功/异常：
  ```kotlin
  interface FFmpegEngine {
      fun extractAudio(input: File, output: File, durationMs: Long): Flow<FfmpegProgress>
  }
  data class FfmpegProgress(val percent: Int, val timeMs: Long, val sizeBytes: Long, val speedMultiplier: Double)
  class FfmpegException(val returnCodeValue: Int, val logsTail: String, ...): RuntimeException(...)
  ```
- 实现 `data/engine/FFmpegKitEngine.kt`：`FFmpegKit.executeAsync(cmd, completeCallback, logCallback, statsCallback)`；`Statistics.time / durationMs * 100` → `FfmpegProgress`；命令失败时 `close(FfmpegException)` 携带最后 40 行 warning 日志，便于诊断；`awaitClose` 调 `FFmpegKit.cancel(sessionId)` 并删除半成品 wav。

### 2.3 用例
- `domain/usecase/ExtractAudioUseCase(input, output, durationMs): Flow<FfmpegProgress>`。
- 已实现幂等：若 `audio.wav` 存在且 > 1KB，直接返回单条 `FfmpegProgress(percent=100,...)`，跳过 FFmpeg。

### 2.4 编排与 UI
- `data/orchestrator/TaskOrchestrator`：`single` Koin 作用域，注入 `applicationScope`（`SupervisorJob() + Dispatchers.IO`）。`startAudioExtraction(taskId)` / `cancel(taskId)` 把进度写回 `TaskRepository`，ViewModel 只观察 Room。Phase 6 会把这块包到前台 Service。
- 导航：`nav_main.xml` 加 `progressFragment`，`taskId: String` 作为 safeArgs；`HomeFragment` item 点击触发 `actionHomeToProgress(taskId)`。
- `ProgressFragment` + `ProgressViewModel`：观察 `TaskRepository.observe(taskId)`，把 `TaskStage` 投影成 `ProgressUiState(percent, running, canStart, canCancel)`。两个按钮：开始 / 取消。

### 验收
- 选一个 1080p 5min mp4，提取出的 wav 文件用 ffprobe 看是 `pcm_s16le, 16000 Hz, mono`。
- 进度条平滑增长到 100%，耗时 < 视频时长 × 0.3。

### 风险
- FFmpegKit 在某些机型 Android 14+ 启动慢，第一次调用有 ~500ms 延迟 —— 接受。
- libass 依赖在 full-gpl 中已包含，不要换成普通 `ffmpeg-kit-full`，否则阶段 5 字幕样式失败。
- 阿里云/华为云镜像可用性：两家都是历史性公共镜像，但都没有承诺保留已撤下的 artifact。**如果哪天某一家也开始返回 404**：先切到剩下那家；都丢失就走「自托管 AAR / Media3 重写」两条 fallback 之一。把 AAR 的 SHA1 钉死可以提前发现镜像被替换。

---

## 阶段 3 — Whisper 集成与字幕生成

**目标**：把 `audio.wav` 跑过 Whisper，得到 `Subtitle`，序列化为 SRT 文件，UI 展示进度。

**状态：已落地**（2026-05-22）。`assembleDebug` 通过，`libwhisper.so` 同时为 `arm64-v8a` 与 `armeabi-v7a` 产出；JVM 单测（含 SRT 往返）绿。真机端到端尚未跑（待用户在物理设备上验证）。

### 3.1 模型管理 ✅
- `data.repository.ModelRepository` / `DefaultModelRepository`：
  - `fileFor(model)` → `filesDir/models/<name>`；`isAvailable(model)` 校验文件存在 + 字节数匹配。
  - `download(model): Flow<ModelDownloadEvent>` — OkHttp 4.12.0，`.part` 临时文件 + `Range: bytes=N-` 断点续传，64KB 读取，每 256KB 发一次 `Progress`，完成后 SHA-256 校验，失败抛 `ModelChecksumException` 并删除文件。
  - 默认源：HuggingFace `ggerganov/whisper.cpp/resolve/main/ggml-base.bin`，SHA-256 `60ed5bc3...2efe`，141 MB。
- 设置页（Phase 7）尚未做；当前在 `ProgressFragment` 内联显示"下载模型"按钮。

### 3.2 vendoring whisper.cpp + NDK 构建 ✅（路线 A，由 SDD §4.1 修订记录）
**为何不用 whisper-jni：** 1.7.1 jar 内只有 `macos-arm64/`、`debian-*/`、`win-amd64/` 等桌面 GLIBC 二进制，Android（bionic libc）加载会直接失败。SDD 当时假设它跨平台是错的。

**改为：** 把 `ggerganov/whisper.cpp` v1.7.5 的最小子集 vendor 到 `app/src/main/cpp/whisper.cpp/` —— 仅保留 ggml + whisper + ggml-cpu 后端（CoreML/OpenVINO/Metal/CUDA 全部砍掉）。AGP 9 + NDK 26.3.11579264 + CMake 3.22.1 + `externalNativeBuild` 直接编出 `libwhisper.so`。
- `app/src/main/cpp/CMakeLists.txt`：单 target `whisper`，源文件清单显式列出 ggml/ggml-alloc/ggml-backend(-reg)/ggml-quants/ggml-threading + ggml-cpu/{ggml-cpu.c, ggml-cpu.cpp, aarch64, hbm, quants, traits, unary-ops, binary-ops} + amx/{amx.cpp, mmq.cpp}（amx 在 ARM 上是空翻译单元，但 ggml-cpu.cpp 无条件 `#include "amx/amx.h"`，所以头要在路径上）。Release 加 `-O3 -fvisibility=hidden -ffunction-sections --gc-sections --exclude-libs,ALL`。
- `app/src/main/cpp/whisper_jni.c`：JNI bridge，导出 `Java_com_whispercpp_whisper_WhisperLib_*`。新增 `whisper_state_extras { volatile progress; volatile aborted }` 给 `progress_callback` 写、给 `abort_callback` 读，Kotlin 侧 250ms 轮询读进度、取消时翻 `setAbort(true)`。
- Kotlin facade：`com.whispercpp.whisper.WhisperLib`（`object`，方法直接挂在类上没有 Companion，避免 `_00024Companion_` mangling）。
- `proguard-rules.pro` `-keep class com.whispercpp.whisper.** { *; }` + 保住 `native` 方法。
- AndroidManifest `INTERNET` 权限（模型下载用）。
- AGP 9 坑：`splits.abi` 与 `defaultConfig.ndk.abiFilters` 不能同时声明，CMake 会跟随 `splits.abi` 的 ABI 列表自动构建。
- FFmpegKit 也带 `libc++_shared.so`，AGP 选 app 自己的版本（构建期 warning 可忽略）。

### 3.3 WhisperEngine 抽象 ✅
```kotlin
interface WhisperEngine {
    fun transcribe(wav: File, config: WhisperConfig): Flow<TranscribeEvent>
}
sealed interface TranscribeEvent {
    data class Progress(val percent: Int) : TranscribeEvent
    data class Done(val subtitle: Subtitle) : TranscribeEvent
}
data class WhisperConfig(
    val model: WhisperModel, val modelFile: File,
    val language: String? = null, val translate: Boolean = false,
    val initialPrompt: String? = null, val nThreads: Int = 4,
)
```
实际比早期草稿少了 `Segment` / `Failed` 子类（流的失败用 `close(throwable)` 表达，分段在 `Done` 一次性拿到）。

`data.engine.WhisperJniEngine`：`callbackFlow` 包住 `whisper_full`；`SupervisorJob + io` 子作用域里跑两个 coroutine —— 一个解码 WAV → `whisper_full` 阻塞调用、一个 250ms 轮询 native 进度。`awaitClose` 时 `setAbort(true)`、cancel 子作用域、起后台线程 sleep 50ms 让 native 退栈再 `freeState/freeContext`（避免在 `whisper_full` 还引用 ctx 时释放）。

`data.engine.WavDecoder`：自实现 RIFF/fmt/data chunk 解析 —— 严格要求 mono 16kHz 16-bit PCM（管线唯一生产者就是 FFmpegKitEngine，错误就是 bug 不做兼容），输出 `FloatArray`（s16 / 32768f）。

### 3.4 SRT 序列化 ✅
- `data.source.local.SrtSerializer.writeSrt/readSrt`，`HH:MM:SS,mmm`（comma decimal），跨小时、补零、跳过空 segment 全部由 `SrtSerializerTest` 锁住（6 个 case，往返、负数 clamp、`.` / `,` 都接受）。
- `domain.usecase.TranscribeAudioUseCase` 在产出 `Done` 时落盘 `subtitle.srt`，并在已存在且非空时跳过推理（幂等于磁盘）。

### 3.5 UI 与编排 ✅
- `TaskOrchestrator.start(taskId, model)` —— 抽取 → 转写一站式驱动，按阶段更新 `TaskState.stage`（`Extracting` → `Transcribing` → `Editing`，`Editing` 是 Phase 4 接手前的终态占位）。Failed 不会被后续阶段覆盖。
- `ProgressViewModel` 引入 `ModelStatus` sealed（Unknown/Missing/Downloading/Ready/Failed）；模型未就绪时禁用 Start，按钮入口直接调 `modelRepository.download`。
- `ProgressFragment` 上半部模型状态（label + 进度条 + 下载按钮），下半部任务进度。组合进度的 5%/70% 加权暂未做（两个阶段独立显示，等真机看主观体验再决定要不要合）。

### 验收（已通过）
- ✅ `./gradlew :app:testDebugUnitTest` 绿（含 `SrtSerializerTest`）。
- ✅ `./gradlew :app:assembleDebug` 绿，`libwhisper.so` arm64-v8a 9.6 MB / armeabi-v7a 8.5 MB。
- ⏳ 真机端到端：5min 英文视频，待用户跑（模型下载 + 转写）。
- ⏳ 取消测试：< 2s 内 native 退栈，无 .part 残留。

### 风险（剩余）
- 真机首次推理可能 OOM —— 当前只支持 base（141MB 文件，运行时 ~200MB），更大模型留到 Phase 7 设置页。
- 中文 prompt 默认值（"你好，我们需要使用简体中文..."）暂未注入，等设置页接入后再加。

---

## 阶段 4 — 字幕预览与编辑（已落地）

**目标**：用户进入编辑器，看到段级字幕列表，能逐行修改文本和时间轴；保存后回写 SRT。✅

### 4.1 EditorFragment + EditorViewModel ✅
- `EditorUiState(segments, isDirty, originalAvailable, loaded, title)` + `EditorEffect`（Toast / NavigateBack，走 `Channel`）。
- `ui/editor/SegmentAdapter` 用 `ListAdapter<SubtitleSegment, VH>` + DiffUtil，按 `index` 比 item 同一性、按 data class equality 比内容。
- 行布局 `item_segment.xml`：`#N` 序号 + `HH:MM:SS,mmm → HH:MM:SS,mmm` 时间区间（可点）+ 多行文本（可点）。
- `fragment_editor.xml`：MaterialToolbar（带返回键 + `menu_editor.xml` 保存/恢复原文）+ 标题（任务文件名）+ RecyclerView + 空态文案。
- "恢复原文"从 `TranscribeAudioUseCase.ORIGINAL_SRT_NAME` (`subtitle.original.srt`) 重新读取 segments，并标记 dirty 让用户主动保存。
- "保存"通过 `SrtSerializer.writeSrt(...)`（IO 调度）覆盖 `subtitle.srt`，写前 renumber `index = i+1`。

### 4.2 校验与体验 ✅
- 文本对话框：多行 EditText，确定时 trim 后写回；空字符串视为有效编辑，`SrtSerializer` 落盘时跳过空白段。
- 时间对话框：两个 `HH:MM:SS,mmm` EditText，复用 `SrtSerializer.parseTimestamp`（兼容 `,` 与 `.`）。
- `EditorViewModel.updateTime` 返回可空的 `@StringRes Int`（成功为 null）：格式错误 / `start>=end` / 与前后相邻段重叠均映射到独立的 `editor_validation_*` 串。错误时只弹 Toast、不关闭对话框，方便用户继续修改。
- 返回键拦截：`OnBackPressedCallback` + `MaterialAlertDialogBuilder` 三选项（保存 / 丢弃 / 继续编辑）；不脏直接 `navigateUp()`。
- 进度页 → 编辑器自动跳转：`ProgressFragment.render` 在 `task.stage is TaskStage.Editing` 时一次性 `navigate(action_progress_to_editor)`，`navigatedToEditor` 防抖避免重复入栈。

### 4.3 不做（v1，仍按计划）
- 不支持合并 / 拆分段。
- 不支持 ASS 样式编辑。

### 验收 ✅
- 单测：`SrtSerializerTest` 覆盖 format/parse 双向、`,` 与 `.` 兼容、空段跳过、trim 行为。
- `:app:assembleDebug` 通过；`:app:testDebugUnitTest` 通过。
- 编辑 → 保存后 `subtitle.srt` 内容反映新文本（手动验证：`adb shell run-as ... cat`）。
- 时间冲突时不关闭对话框、Toast 提示原因。

### 风险（剩余）
- 长字幕（500+ 段）滑动性能未实测；当前用 `ListAdapter` + DiffUtil（无 `setHasFixedSize(true)`，因行高随文字行数变化）。若实测有卡顿，转 fixed-size + 单行文本 + tap-to-expand。
- 时间编辑用纯文本输入而非时间轴 picker；首版可接受，未来若加波形预览再升级。

---

## 阶段 5 — 视频合成

**目标**：把编辑后的 SRT 烧回原视频（硬字幕默认；mp4/mov 输出可选软字幕），文件落地到 MediaStore。

### 5.1 BurnSubtitlesUseCase
- 输入：`videoFile`, `srtFile`, `outputFile`, `BurnOptions(mode: HARD|SOFT, crf: Int, preset: String, fontSizeSp: Int, fontColor: Int, vAlign: Top|Mid|Bottom)`。
- 调 `FFmpegEngine.burnSubtitles(...)`，返回 `Flow<FfmpegProgress>`。

### 5.2 FFmpegEngine.burnSubtitles
- 命令分支与 SDD §6.2[5] 一致。
- 路径预处理：把 srt 拷到 `cacheDir/tasks/<id>/subs.srt`（保证 ASCII 路径），FFmpeg filter 用单引号 + `:` 转义。
- 字幕样式（仅硬字幕）通过 `subtitles=...:force_style='Fontsize=24,PrimaryColour=&H00FFFFFF&,Alignment=2'` 注入；这是简化的 ASS override syntax。
- `OnStatisticsCallback` → `FfmpegProgress`。

### 5.3 输出落地
- `MediaStoreSaver.saveToMovies(file, displayName)`：
  - API 29+：`MediaStore.Video.Media.EXTERNAL_CONTENT_URI` + `RELATIVE_PATH=Movies/VideoSubtitle/`，`IS_PENDING=1` → 写入 → `IS_PENDING=0`。
  - API 26–28：直接写到 `getExternalStoragePublicDirectory(DIRECTORY_MOVIES)/VideoSubtitle/`。
- 完成后给 ProgressFragment 一个"在相册中打开"action（intent ACTION_VIEW 带返回 URI）。

### 5.4 软字幕检查
- 仅当 output suffix 在 `{mp4, m4v, mov}` 时允许软字幕；否则自动切换硬字幕，并 Toast 告知用户。

### 验收
- 硬字幕：输出 mp4 在系统播放器看到字幕，`ffprobe` 看不到 subtitle stream。
- 软字幕：输出 mp4 用 VLC 看到 subtitle track，`ffprobe` 看到 `mov_text`。
- 中文文件名 / 路径不导致 filter 失败。
- 5min 1080p 视频在 Pixel 6 上硬字幕合成 < 实时倍率 1×（即 < 5min）。

### 风险
- `subtitles=` filter 字体替换：Android 上 libass 找不到默认字体时会 fallback 到 sans-serif，可能不支持中文 → **必须**在阶段 5 内 bundle 一个开源 CJK 字体（如 `NotoSansSC-Regular.otf`，~10MB），通过 `fontsdir=` 参数传给 filter。需要在 SDD §11 的 APK 体积里预算这部分。

---

## 阶段 6 — 后台任务与稳健性

**目标**：长任务在 App 进入后台后继续跑；进程被杀后能恢复任务状态；任务可取消、可重试。

### 6.1 前台 Service
- 新增 `VideoProcessingService : LifecycleService`（或 `Service` + 自管 scope）。
- `foregroundServiceType="mediaProcessing|dataSync"`（API 34+ 必需）。
- 通知里展示当前阶段 + 总进度，含"取消"动作。
- ViewModel 不再持有任务执行权，只通过 `TaskRepository` 启动/观察。

### 6.2 状态恢复
- `TaskRepository.startup()`：扫 Room 中 `stage` 不为 `Done/Failed/Idle` 的任务，检查中间产物：
  - `audio.wav` 存在 → 跳过提取，从识别开始。
  - `subtitle.srt` 存在 → 跳过识别，进入 Editing（不会自动跑合成，需要用户确认）。
- 任务标记 `stage = Failed(...)` 后，UI 提供"从此步骤重试"按钮。

### 6.3 取消语义
- 取消 → 当前阶段 `cancel()`：
  - FFmpegKit：`FFmpegKit.cancel(sessionId)`。
  - whisper-jni：关闭 `WhisperContext`（在 `awaitClose` 里）。
- 取消后任务保留中间产物，UI 状态变 `Failed(Cancelled)`，允许重试。

### 6.4 通用稳健性
- 全局 `CoroutineExceptionHandler` → Timber + Crashlytics（可选，先空实现接口）。
- 磁盘空间检查：每阶段开始前确保剩余空间 > 视频大小 × 2，否则提前失败。

### 验收
- 任务跑到 50% 时把 App 切后台 5 分钟，任务能跑完并发系统通知。
- 任务跑到 50% 时强杀 App（`adb shell am force-stop`），重开后能看到任务状态为 Failed 或自动从最近完成步骤恢复。
- 取消任务后再启动同一视频任务，从头开始。

---

## 阶段 7 — 设置、字幕样式与发布准备

**目标**：把分散的可配置项集中到 Settings；做一次完整 UX 走查；准备 release 构建。

### 7.1 Settings（DataStore）
- 模型选择（tiny/base/small），默认 base。
- 默认语言（auto/中文/英文/...）。
- 字幕字号（默认 24sp 等比换算）、字色（白/黄/亮绿）、对齐（底中/顶中）、描边（开/关）。
- 输出质量：`ultrafast / fast / medium / slow`，默认 medium。
- 软字幕优先（仅当 mp4 输出时生效）。
- 模型镜像 URL。
- 清理缓存按钮（删除 `cacheDir/tasks/`）。

### 7.2 UX 收尾
- 空状态、错误页、加载中等状态统一组件化。
- Loading 与失败都给"重试"按钮。
- Light/Dark theme 跑一遍（已有 `values-night/themes.xml`）。
- 中文/英文资源 strings 拆分（默认中文）。

### 7.3 Release 构建
- 启用 R8（`isMinifyEnabled = true`）+ resource shrinking。
- ABI splits 切片；arm64-v8a 单架构 APK 应 < 80MB。
- 在 `proguard-rules.pro` 加全套 keep 规则（whisper-jni / ffmpeg-kit / Hilt / kotlinx.serialization）。
- Crash 上报接入（可选 Firebase Crashlytics 或 Sentry）。
- README / 用户引导。

### 验收
- 设置项的修改在下次任务中真实生效（如换模型、改字幕颜色）。
- Release APK 在测试机上跑通 5min mp4 全流程。
- 卸载重装后历史任务/模型按设计清空（cacheDir 自动清，filesDir/models 保留模型 → 设置中需明示）。

---

## 全局风险与依赖

| 风险 | 缓解 |
|---|---|
| FFmpegKit 上游归档 | 锁 6.0-2.LTS.1，封装 `FFmpegEngine` 接口；v2 评估 Media3 Transformer |
| whisper-jni 体积大、ABI 兼容性 | 默认 base 模型 + arm64-v8a 主推；提供 v7a fallback；评估替代封装 |
| Whisper 中文识别质量 | 中文 prompt + 推荐 small 模型作为高质量预设 |
| 大文件 IO / 内存 | 全程走文件路径，永不在内存保留整段 PCM/视频 |
| 进程被杀 | TaskRepository + Room 状态机 + 中间产物探测恢复 |
| AGP 9.1 + 第三方插件兼容 | 阶段 0 跑通，遇到不兼容立即记录 |

## 测试矩阵（建议）

| 维度 | 取值 |
|---|---|
| 设备 | Pixel 6（arm64）、小米中端机（arm64）、低端 4GB v7a 机型 |
| 视频 | 5min/1080p mp4，30min/720p mp4，10min/4k mov，5min webm |
| 语言 | 中文普通话、英文、中英混说 |
| 模型 | tiny、base、small |
| 输出 | 硬字幕 mp4、软字幕 mp4、硬字幕 mkv |

## 里程碑

| 里程碑 | 包含阶段 | 期望产出 |
|---|---|---|
| M1 脚手架 | 0, 1 | 能选视频、看到任务列表 |
| M2 字幕生成 | 2, 3 | 选视频后能生成 SRT |
| M3 编辑 + 合成 | 4, 5 | 能编辑字幕并烧回视频 |
| M4 后台 + 体验 | 6, 7 | 可发布的 v1.0.0 |

每个 M 完成后做一次回归测试矩阵执行，并按需更新 SDD。
