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

## 阶段 5 — 视频合成（已落地）

**目标**：把编辑后的 SRT 烧回原视频（硬字幕默认；mp4/mov 输出可选软字幕），文件落地到 MediaStore。✅

### 5.1 BurnSubtitlesUseCase ✅
- `domain/usecase/BurnSubtitlesUseCase` thin wrapper → `FFmpegEngine.burnSubtitles(video, srt, output, durationMs, BurnOptions)`。
- `BurnOptions(mode = HARD, crf = 23, preset = "medium", fontSize = 24, fontColorArgb = 0xFFFFFFFF, outlineColorArgb = 0xFF000000, outlineWidth = 2, alignment = BottomCenter)` 默认值即 v1 行为，未来 Phase 7 设置页直接覆盖。

### 5.2 FFmpegEngine.burnSubtitles ✅
- HARD 命令：`-y -i video -vf "subtitles='<escaped path>':force_style='Fontsize=...,PrimaryColour=&H...&,Alignment=2,Outline=2,BorderStyle=1,MarginV=24'" -c:v libx264 -preset medium -crf 23 -c:a copy -movflags +faststart output.mp4`。
- SOFT 命令：`-y -i video -i srt -map 0:v:0 -map 0:a:0? -map 1:0 -c:v copy -c:a copy -c:s mov_text -metadata:s:s:0 language=und output.mp4`（仅 mp4/m4v/mov 容器）。
- SRT 落到任务目录下的 `subs.srt`（task dir UUID 路径已经全 ASCII，再多一层防呆）。`subtitles=` 过滤器值用 `escapeForSubtitlesFilter()` 转义 `\\` `:` `'` `,` `[` `]`，再单引号包裹。
- `argbToAssBgr(int)` 把 ARGB 转成 ASS 颜色：`&HAABBGGRR`，alpha 反向（00=不透明）。
- 复用 `runFFmpegSession()` 私有 helper（共享 LogCallback/StatisticsCallback/awaitClose 取消逻辑），`extractAudio` 也走该路径。

### 5.3 输出落地 ✅
- `data/source/media/MediaStoreSaver`：
  - API 29+：`MediaStore.Video.Media.EXTERNAL_CONTENT_URI` + `RELATIVE_PATH=Movies/VideoSubtitle/`，`IS_PENDING=1` 写入 → `IS_PENDING=0` 提交，写失败回滚 `delete(uri)`。
  - API 26–28：写到 `Environment.DIRECTORY_MOVIES/VideoSubtitle/`，通过 `androidx.core.content.FileProvider`（manifest 已注册 `${applicationId}.fileprovider`，paths 在 `res/xml/file_paths.xml`）暴露 `content://` URI。
- 复制完成后 `source.delete()` 清理 cacheDir 中间文件，把 `Uri.toString()` 写进 `TaskStage.Done(outputPath)`。

### 5.4 编辑器导出 → 进度页 → 在播放器中打开 ✅
- `menu_editor.xml` 新增 `action_export`；`EditorViewModel.export()` 若 dirty 先调 `save { ok -> orchestrator.startBurn(taskId) }`，再 emit `EditorEffect.NavigateBack`。
- `TaskOrchestrator.startBurn(taskId, options)` 使用与 `start` 相同的 `jobs` map（同一 `taskId` 不会并发）；任务序列：`Burning(0..100)` → `MediaStoreSaver.saveToMovies` → `Done(uri)` 或 `Failed(reason)`。`cancel()` 现在覆盖 `Burning` 阶段。
- `ProgressFragment` 在 `task.stage is TaskStage.Done` 时显示 "在播放器中打开" 按钮，发出 `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`。

### 5.5 字体目录修复（Phase 7 后回归发现并落地）✅
- **问题**：真机实测发现硬字幕烧录后视频成功重编码但所有字幕字形渲染为空白。原因是 ffmpeg-kit-full-gpl 在 Android 上不会自动注册任何 fontconfig 字体目录，libass 找不到任何 face；同时 SRT→ASS 默认 Style 是 `Fontname=Arial`，Android 系统也没有 Arial。
- **修复 1：`VideoSubtitleApp.onCreate`** 调用 `FFmpegKitConfig.setFontDirectoryList(this, listOf("/system/fonts"), mapOf("Arial" to "Roboto", "sans-serif" to "Roboto", "Helvetica" to "Roboto"))`，把通用名 remap 到 Android 必装的 Roboto。
- **修复 2：`FFmpegKitEngine.buildForceStyle`** 在 ASS `force_style` 头部显式指定 `Fontname=Roboto`，保证哪怕 SRT 内嵌别名也能 hit Roboto face。
- 这就避免了再 bundle ~10MB Noto Sans SC 的代价；中文/日韩字符依赖 `/system/fonts` 里 OEM 自带的 NotoSansCJK family（Pixel/小米/三星等大厂均有）。
- 若后续在某些去字体精简 ROM 上中文仍渲染为方框，再 bundle NotoSansSC 到 `assets/fonts/`，把 `assets://fonts` 追加进 `setFontDirectoryList` 列表即可，不必动 force_style。

### 5.6 不做（v1）
- 用户不能选择 SOFT 模式，Phase 7 设置页接入。
- 输出文件名固定 `${baseName}_subtitled.mp4`，未做去重，重复导出会撞名（MediaStore 行为：同名文件追加 `(1)` 后缀）。

### 验收
- `:app:assembleDebug` ✅ / `:app:testDebugUnitTest` ✅。
- 真机端到端验证：硬字幕输出 mp4 在系统相册可播放、字幕可见（修复字体目录后），`ffprobe` 看不到 subtitle stream。
- 中文路径与文件名通过 `escapeForSubtitlesFilter()` 处理；MediaStore RELATIVE_PATH 不依赖路径字符。

### 风险（剩余）
- SOFT 模式没有 UI 入口；`burnSubtitles` 已支持但当前编辑器的导出按钮硬编码 `BurnOptions()` 默认（HARD）。
- 没有磁盘空间预检查（Phase 6）。导出 1080p 长视频可能在 cacheDir 写一份 + MediaStore 再写一份 = 视频大小 × 2 占用，瞬时高峰需要注意。

---

## 阶段 6 — 后台任务与稳健性（已落地）

**目标**：长任务在 App 进入后台后继续跑；进程被杀后能恢复任务状态；任务可取消、可重试。✅

### 6.1 前台 Service ✅
- `service/VideoProcessingService : Service`，单例，`onBind` 返回 null（启动型 Service）。
- API 34+ 走 `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)`，老版本走两参 `startForeground`。Manifest 注册 `<service ... foregroundServiceType="mediaProcessing">`，权限 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROCESSING` + `POST_NOTIFICATIONS`。
- 不持有任务执行权——`TaskOrchestrator.appScope` 仍是协程跑的地方；service 的唯一职责是观察 `taskRepository.observeAll()` 并在最近一个"in-progress"任务存在时维持 foreground notification，全部结束就 `stopSelf()`。`distinctUntilChanged` 确保通知不抖动。
- 通知样式：标题=任务文件名 / 阶段文本（`task_stage_*` 复用）+ 进度条 + Cancel action（PendingIntent → service 自身的 `ACTION_CANCEL`，由 service 转交给 orchestrator）。
- `TaskOrchestrator.start(...)`/`startBurn(...)` 在 launch 协程之前调用 `VideoProcessingService.start(context)`；service 用 `START_NOT_STICKY`，进程死则放弃，不复活。

### 6.2 状态恢复 ✅
- `TaskRepository.recoverInterrupted()` 在 `VideoSubtitleApp.onCreate` 里启动协程调用：
  - 任务 stage 不在 {Extracting, Transcribing, Burning} 跳过。
  - 否则按文件存在性回退：`subtitle.srt`/`subtitle.original.srt` 在 → `Editing`；`audio.wav` 在 → `Idle`（用户重启即跳过 extract，因为 transcribe usecase 已自带 SRT 缓存短路）；都没有 → `Idle`。
- 进入 `Editing` 后用户可在 ProgressFragment 重启 burn；进入 `Idle` 用户可点"开始处理"。

### 6.3 取消语义 ✅
- 通知里的"取消"和 ProgressFragment 的取消按钮都走 `TaskOrchestrator.cancel(taskId)`。
- 协程 cancel → `awaitClose` 里 `FFmpegKit.cancel(sessionId)` 或 whisper context close + `aborted = 1`。
- Repository 把 stage 标 `Failed("Cancelled")`，service 的观察者看到没有 in-progress 任务后 `stopSelf()`。

### 6.4 通用稳健性 ✅
- 磁盘空间预检查：`runExtraction` / `runBurn` 开头 `StatFs(taskDir).availableBytes >= source.length() * 2`，否则直接 `Failed("Insufficient storage (need ~xx MB)")`。

### 验收
- `:app:assembleDebug` ✅ / `:app:testDebugUnitTest` ✅。
- 真机端到端验证（待执行）：
  - 跑到 50% 切后台 5 分钟仍能完成（通知保活）。
  - `adb shell am force-stop com.frank.videosubtitle` 后重开，stage 回到 Editing/Idle，可继续。
  - 通知"取消"按钮把任务标 `Failed(Cancelled)`，重新点击"开始处理"从头开始。

### 风险（剩余）
- `POST_NOTIFICATIONS` 是 API 33+ 运行时权限，目前没有运行时申请——通知拒绝时 foreground service 仍能跑（OS 给 notification 兜底显示），但用户看不到自定义文案。Phase 7 再加 `requestPermissions`。
- `VideoProcessingService` 当前只显示一个聚合通知（最近的 in-progress 任务）。如果用户同时跑多个任务（v1 不支持，但未来可能），需要拆成多通知。
- 没有 `CoroutineExceptionHandler` —— 仍依赖 use case 内部 `.catch {}`。如果哪条 flow 漏掉，会 crash。

---

## 阶段 7 — 设置、字幕样式与发布准备（已落地）

**目标**：把分散的可配置项集中到 Settings；做一次完整 UX 走查；准备 release 构建。

### 7.1 Settings（DataStore）✅
- `domain/model/AppSettings.kt` 定义不可变设置；持久化在 `data/source/local/SettingsDataStore.kt`（Preferences DataStore，文件名 `settings`）。`SettingsRepository` 暴露 `observe(): Flow<AppSettings>` 与 `current(): AppSettings`，作为唯一真源。
- `WhisperModel` 扩展为 `Tiny / Base / Small`（SHA-256 来自 HuggingFace LFS API）。
- `TaskOrchestrator.start` / `startBurn` 在协程入口读取 `settings.current()`，构建 `WhisperConfig`（model / language.whisperCode / language.initialPrompt）和 `BurnOptions`（burnMode、preset、fontSize、fontColor.argb、outline → 描边宽度、alignment）。
- `ProgressViewModel` 通过 `settingsRepository.observe().map { it.model }` 驱动 `activeModel`，模型切换会取消旧下载任务并刷新 `ModelStatus`。
- Settings UI：`SettingsFragment` + `SettingsViewModel`（XML+ViewBinding，与项目惯例一致）。包含模型 RadioGroup、语言 ExposedDropdown、字号 Slider、颜色 ToggleGroup、对齐 ToggleGroup、描边 Switch、preset Dropdown、软字幕 Switch、清理缓存按钮（任意任务进行中时禁用，仅删除 `cacheDir/tasks/`，不动 `filesDir/models`）。
- 入口：Home Toolbar 菜单 → `action_settings` → `nav_main.xml` 新增 `settingsFragment` 目的地。
- 暂未做：模型镜像 URL（推迟到 v1.x）。

### 7.2 UX 收尾 ✅
- 通用组件 `ui/common/StateView`（`view_state.xml` merge layout）支持 loading / empty / error 三种形态，error 形态可选 action 按钮。
- HomeFragment 空态、ProgressFragment Failed 态（带"重试"按钮，`ProgressViewModel.retry()` 根据 `subtitle.srt` 是否存在决定走 `orchestrator.startBurn` 还是 `orchestrator.start`）、EditorFragment 空段态都接入 StateView。
- 中文/英文 strings 拆分：`values/strings.xml`（zh，默认）+ `values-en/strings.xml`（英文，键集与格式参数与默认完全一致）。
- Light/Dark：依赖系统 `Theme.Material3.DayNight.NoActionBar`，未做单独深色覆写文件——若后续走查发现对比度问题再补 `values-night/`。

### 7.3 Release 构建 ✅
- `app/build.gradle.kts`：`release { isMinifyEnabled = true; isShrinkResources = true }`。
- `app/proguard-rules.pro` keep：FFmpegKit (`com.arthenica.ffmpegkit.**`、`com.arthenica.smartexception.**`)、WhisperLib JNI（`com.whispercpp.whisper.**` + `native <methods>`）、Koin 反射构造的 `ViewModel` 子类构造函数、kotlinx coroutines `MainDispatcherFactory`/`AndroidDispatcherFactory`。
- `./gradlew :app:assembleRelease` BUILD SUCCESSFUL（含 R8 + lintVitalRelease + resource shrinking）。arm64-v8a 切片 APK ≈ 31 MB（< 80 MB 阈值）。
- 暂未做：签名 config（release APK 仍未签名，由用户本地签名）；Crashlytics / Sentry（推迟到 v1.1）；README 用户引导。

### 验收
- ✅ 设置项的修改在下次任务中真实生效（model / language / preset / 字幕样式都从 SettingsRepository 读取，不再 hardcode）。
- ⏳ Release APK 在测试机上跑通 5min mp4 全流程（待真机回归）。
- ✅ 卸载重装后历史任务/模型按设计清空（cacheDir 自动清，`filesDir/models` 保留模型；Settings 清理缓存按钮文案已明示）。

---

## 阶段 8 — Android Media 后端（可选硬件加速）✅ 已落地

**目标**：在 `extractAudio` 与 `burnSubtitles` 两个 CPU 密集步骤上引入 MediaCodec / Media3 Transformer 硬件路径，由用户在 Settings 里二选一。FFmpegKit 仍是默认与回退路径，不破坏现有任何能力；只在 SDD §4.2 risk table 已标注的"v2 Media3 评估"提前到 v1。

### 8.1 设置项 ✅
- 新枚举 `domain/model/MediaBackend.kt`：`Ffmpeg` / `AndroidMedia`。
- `AppSettings.mediaBackend: MediaBackend = MediaBackend.Ffmpeg`，默认 FFmpeg —— 老用户升级后行为完全不变。
- 持久化 key `media_backend`，仿 `burn_mode` 模式。`SettingsRepository.setMediaBackend` / `SettingsViewModel.setMediaBackend` 一一映射。

### 8.2 引擎拆分 ✅
- `data/engine/Media3TransformerEngine.kt`（新）实现 `FFmpegEngine`：
  - `extractAudio`：`MediaExtractor` 选音频轨 → `MediaCodec` 解码 PCM → 通道平均降混到单声道 → Bresenham 风格重采样到 16kHz → 写 44 字节 WAV header + raw PCM。整个流程不涉及视频解码，与 FFmpeg `-vn` 等价。所有错误包装为 `FfmpegException` 复用现有错误通道。
  - `burnSubtitles`：`Transformer` + `OverlayEffect(SrtBitmapOverlay)`。`SrtBitmapOverlay`（继承 `BitmapOverlay`）在 `getBitmap(presentationTimeUs)` 中按当前帧对应的 SRT cue 用 `Canvas`/`StaticLayout` 渲染位图，按 `BurnOptions` 的 `fontSize/fontColorArgb/outline*/alignment/marginV/marginH/background` 反映样式；按 cue 缓存位图避免重复渲染。9 宫格对齐通过 `OverlaySettings.setBackgroundFrameAnchor` + `setOverlayFrameAnchor` 两个 anchor 完成。进度通过 `Transformer.getProgress(ProgressHolder)` 主线程 250ms 轮询。
  - 共享 SRT 解析：`data/engine/SrtBodyFilter.kt`（新）暴露 `parseSrt(text): List<SrtCue>` 与 `SrtCue.filterBody(SubtitleDisplay)`，两个引擎都用。FFmpegKitEngine 仍保留自己的 `writeStyledBilingualSrt` —— 它额外做 ASS inline override 注入，那是 libass 专属。
- `data/engine/RoutingMediaEngine.kt`（新）实现 `FFmpegEngine`，构造接收 `(FFmpegKitEngine, Media3TransformerEngine, SettingsRepository)`：
  - `extractAudio`：`settings.current().mediaBackend` 直接二选一。
  - `burnSubtitles`：`AndroidMedia` 路径上若 `BurnOptions` 含原文/译文不同样式（`fontSizeTranslated` / `fontColorTranslatedArgb` / `outlineWidthTranslated` 任一非空）→ 静默回退到 FFmpeg。这是 SDD §6.2 / §8 中明示的回退规则。
- DI（`di/DataModule.kt`）：原 `single { FFmpegKitEngine() } bind FFmpegEngine::class` 拆成三条 `single`，最外层把 `RoutingMediaEngine` bind 到 `FFmpegEngine::class` —— 上游 `ExtractAudioUseCase` / `BurnSubtitlesUseCase` / `TaskOrchestrator` 一行未改。

### 8.3 UI ✅
- `res/layout/fragment_settings_output.xml`：在 preset 下拉之上插入 "处理引擎" 段，`RadioGroup` 含两个 `MaterialRadioButton`，每个下方一行 `SettingsHint` TextView 说明该路径的取舍与回退规则。
- `SettingsOutputFragment` 监听 `groupMediaBackend.checkedRadioButtonId`，调用 `viewModel.setMediaBackend(...)`；`render(s)` 反向把 `s.mediaBackend` 映射到 `check(...)`。
- `SettingsFragment.renderOutput()` 概要从 `%1$s · %2$s` 扩展到 `%1$s · %2$s · %3$s`（preset · burnMode · backend），三个 `_short` strings 跟语言切换。

### 8.4 依赖 ✅
- `gradle/libs.versions.toml` 新增 `media3 = "1.4.1"`，并 catalog 化 `media3-transformer` / `media3-effect` / `media3-common`。
- `app/build.gradle.kts` 引入对应三条 implementation。

### 验收
- ✅ `./gradlew :app:assembleDebug` 成功（含 R8/资源合并）。
- ⏳ 真机：选 `AndroidMedia` 跑 1min mp4，无 per-line override 时走 Media3 路径（Timber `burnSubtitles routed to Media3TransformerEngine`），输出 `ffprobe` 编码信息为硬件 H.264 格式；切回 `FFmpeg` 同视频跑通。
- ⏳ 真机：开启原文/译文不同字号/颜色 → 选 `AndroidMedia` → Timber 输出 fallback 日志，实际走 FFmpeg 路径。
- ⏳ 取消正在跑的 Media3 任务：`Transformer.cancel()` 被触发，部分写出文件被清理。

### 风险
- Media3 1.4.x 在 ABI splits 下未发现兼容问题（与 ffmpeg-kit-full-gpl 共存的 `libc++_shared.so` 警告与之前相同，AGP 自动选 app build 输出）。
- `OverlaySettings`（非 `StaticOverlaySettings`）API 在 1.4.1 仍是稳定符号；后续升级到 1.5+ 时需评估迁移成本。
- 硬件解码兼容性：少量 codec 在低端设备上可能拒绝某些容器，已通过把 `ExportException` 转 `FfmpegException` 走现有错误通道，并提示用户 Settings 切回 FFmpeg。

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
| M5 硬件路径 | 8 | 用户可选 Android Media 后端，长视频明显加速 |

每个 M 完成后做一次回归测试矩阵执行，并按需更新 SDD。
