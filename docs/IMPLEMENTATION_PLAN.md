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

## 阶段 1 — 视频选择与首页

**目标**：用户能从 SAF 选一个本地视频，App 解析其元数据并展示在首页"任务列表"中（此时还没有任何处理，仅是录入）。

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

## 阶段 2 — 音频提取（FFmpegKit 接入）

**目标**：把 `cacheDir/tasks/<id>/source.<ext>` 转成 `audio.wav`（16kHz / mono / PCM s16le），并在 UI 上展示进度。

### 2.1 引入 FFmpegKit
- 在 `libs.versions.toml` 新增 `com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS.1`。
- `proguard-rules.pro` 加 `-keep class com.arthenica.** { *; }` `-keep class com.arthenica.ffmpegkit.** { *; }`。
- 验证：在 `MainActivity.onCreate` 中临时打印 `FFmpegKitConfig.getFFmpegVersion()`。验证完成立即移除。

### 2.2 FFmpegEngine 抽象
- 接口：
  ```kotlin
  interface FFmpegEngine {
      suspend fun extractAudio(input: File, output: File): DomainResult<Unit>
      fun extractAudioFlow(input: File, output: File): Flow<FfmpegProgress>
      // burn 在阶段 5 加
  }
  ```
- `FFmpegProgress(percent: Int, sizeBytes: Long, speed: Double)`。
- 实现 `FFmpegKitEngine`：用 `FFmpegKit.executeAsync(cmd, completeCallback, logCallback, statsCallback)`，把 `Statistics.time / durationMs * 100` 通过 `callbackFlow` 上报。

### 2.3 用例
- `domain/usecase/ExtractAudioUseCase(input, output): Flow<FfmpegProgress>`。

### 2.4 UI
- 进度页 `ProgressFragment`：从启动转写按钮进入，先展示音频提取进度条（5% 权重映射）。
- 后台 Service：先用普通 coroutine + ApplicationScope，到阶段 6 再升级为前台 Service。

### 验收
- 选一个 1080p 5min mp4，提取出的 wav 文件用 ffprobe 看是 `pcm_s16le, 16000 Hz, mono`。
- 进度条平滑增长到 100%，耗时 < 视频时长 × 0.3。

### 风险
- FFmpegKit 在某些机型 Android 14+ 启动慢，第一次调用有 ~500ms 延迟 —— 接受。
- libass 依赖在 full-gpl 中已包含，不要换成普通 `ffmpeg-kit-full`，否则阶段 5 字幕样式失败。

---

## 阶段 3 — Whisper 集成与字幕生成

**目标**：把 `audio.wav` 跑过 Whisper，得到 `Subtitle`，序列化为 SRT 文件，UI 展示进度。

### 3.1 模型管理
- `ModelRepository`：
  - `observeAvailableModels(): Flow<Set<WhisperModel>>`（扫 `filesDir/models`）。
  - `download(model: WhisperModel): Flow<DownloadProgress>`（OkHttp + 范围请求 + SHA-256 校验）。
  - 默认下载源：HuggingFace `ggerganov/whisper.cpp` 仓库（`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin`）。在设置中允许用户改为镜像。
- 设置页 `SettingsFragment` 提供"下载/删除模型"入口。

### 3.2 引入 whisper-jni
- 加依赖 `io.github.givimad:whisper-jni:<version>`。
- ABI splits 已配置，确认 jniLibs 包含 arm64-v8a / armeabi-v7a。
- `proguard-rules.pro` 加 `-keep class io.github.givimad.whisperjni.** { *; }`。
- 写一个 `WhisperSmokeTest`（仪器化）：跑 5 秒英文音频确认能拿到 segment。

### 3.3 WhisperEngine 抽象
```kotlin
interface WhisperEngine {
    fun transcribe(
        wav: File,
        config: WhisperConfig,
    ): Flow<TranscribeEvent>
}

sealed interface TranscribeEvent {
    data class Progress(val percent: Int) : TranscribeEvent
    data class Segment(val seg: SubtitleSegment) : TranscribeEvent
    data class Done(val subtitle: Subtitle) : TranscribeEvent
    data class Failed(val error: AppError) : TranscribeEvent
}

data class WhisperConfig(
    val model: WhisperModel,
    val language: String? = null,        // null = auto
    val translate: Boolean = false,      // 翻译为英文（whisper 内置，非 LLM）
    val initialPrompt: String? = null,
    val nThreads: Int = 4,
    val wordTimestamps: Boolean = false,
)
```

实现 `WhisperJniEngine`：
- 在 `Dispatchers.IO` 上 load model；任务一次实例一个 `WhisperContext`（避免并发）。
- 中文场景默认注入 `initialPrompt = "你好，我们需要使用简体中文，以下是普通话的句子。"`。
- 进度回调：whisper-jni 的 `progressCallback(percent)` 转成 `TranscribeEvent.Progress`。
- 在 `awaitClose` 中关闭 `WhisperContext`，处理取消。

### 3.4 SRT 序列化
- `SubtitleRepository.writeSrt(subtitle, outFile)` —— 自实现，单测覆盖边界（毫秒补零、跨小时、空文本跳过）。
- `SubtitleRepository.readSrt(file): Subtitle` —— 解析回 domain 对象（用于编辑器）。

### 3.5 UI 集成
- `ProgressFragment` 展示组合进度：音频 5% + 识别 70%。
- 识别完成后自动导航到 `EditorFragment`。

### 验收
- `WhisperEngineTest`（JVM）以一段已知小 wav 验证至少返回 1 个 segment、文本非空。
- 真机端到端：5min 英文视频跑完不崩，输出 SRT 与 VideoCaptioner 桌面版同模型结果"看起来差不多"。
- 中断（用户点取消）能在 < 2s 内停止 native 调用，不留泄漏文件。

### 风险
- whisper-jni 在 armeabi-v7a 设备上可能 OOM；设置中默认隐藏 `small` 模型，并在 RAM < 4GB 时仅暴露 `tiny`。
- 模型下载中断恢复：OkHttp 用 `Range: bytes=N-` 续传 —— 要测断网恢复。

---

## 阶段 4 — 字幕预览与编辑

**目标**：用户进入编辑器，看到段级字幕列表，能逐行修改文本和时间轴；保存后回写 SRT。

### 4.1 EditorFragment + EditorViewModel
- `uiState`：`EditorUiState(segments: List<SubtitleSegment>, isDirty: Boolean)`。
- 列表 RecyclerView + `ListAdapter` + DiffUtil。
- 每行：序号、`startMs --> endMs`（可点编辑成时间选择器）、文本（点击进入全屏多行 EditText）。
- 顶部"恢复原文"按钮：从 `subtitle.json`（阶段 3 同时落盘的原始字幕）重置。
- 顶部"保存"按钮：覆盖写 `subtitle.srt`。

### 4.2 校验与体验
- 时间轴编辑时校验：`startMs < endMs`，且与上下相邻段不重叠。
- 文本编辑后 trim 行尾空白。
- 自动保存：用户按返回键时，如果 `isDirty`，弹"保存 / 丢弃"。

### 4.3 不做（v1）
- 不支持合并 / 拆分段（要么留到 v2，要么作为简化操作：在文本里用换行符让 SRT 渲染时分行）。
- 不支持 ASS 样式编辑。

### 验收
- 编辑 3 行，保存后 `subtitle.srt` 内容反映新文本。
- 退出再进，编辑结果保留。
- 时间冲突时按钮置灰并 Toast 提示原因。

### 风险
- 长字幕（500+ 段）滑动卡顿：必须用 `ListAdapter` + DiffUtil + `setHasFixedSize(true)`，不要全量 `notifyDataSetChanged`。

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
