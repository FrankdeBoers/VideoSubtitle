# VideoSubtitle 整体技术设计文档（SDD）

> 版本：v0.1（2026-05-22）
> 文档定位：SDD（Spec-Driven Development）的"设计 / 规约"层。落地步骤与里程碑见 [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md)。
> 参考实现：桌面版 [VideoCaptioner](https://github.com/WEIFENG2333/VideoCaptioner)（Python，本仓库已克隆于 `../VideoCaptioner`）。Android 版只复刻其核心管线（识别 → 字幕 → 合成），不复刻 LLM/翻译等可选能力作为 v1 范围。

---

## 1. 目标与范围

### 1.1 产品目标
为 Android 用户提供"选视频 → 自动生成字幕 → 把字幕烧回视频"的离线一站式工具：
1. 从手机系统文件选择器（SAF）选择本地视频；
2. 用 Whisper 在端侧识别语音生成 SRT 字幕；
3. 允许用户在 App 内编辑字幕文本与时间轴；
4. 用 FFmpeg 把字幕写回视频（硬字幕烧入 / 软字幕封装可选）；
5. 输出文件保存到 `MediaStore.Movies`，可在系统相册中直接看到。

### 1.2 v1 范围（In）
- 本地视频文件输入（mp4 / mov / mkv / webm 等 FFmpeg 支持的格式）。
- Whisper 端侧识别（默认 ggml-base，可在设置中切换 tiny/base/small）。
- SRT 字幕的生成、查看、行级文本编辑、时间轴微调。
- 视频合成：硬字幕（subtitles filter，重新编码）+ 软字幕（mov_text，仅 mp4/mov）。
- 任务进度展示与中断恢复（前台 Service + WorkManager）。
- 中英自动识别，支持 Whisper 全部受支持语言代码透传。

### 1.3 v1 非目标（Out）
- LLM 字幕优化 / 翻译（VideoCaptioner 的 `core/optimize`、`core/translate`、`core/llm`）。
- 在线视频下载（VideoCaptioner 的 yt-dlp 能力）。
- ASS 高级样式编辑器（仅暴露字号/颜色/位置等少量预设）。
- 多音轨选择（默认取 `0:a:0`）。
- iOS / 桌面端。

### 1.4 不变量与约束
- **离线优先**：识别与合成全部在本机完成，不向任何服务发送音视频或文本。
- **架构**：MVVM（View / ViewModel / Repository / DataSource），单 Activity + 多 Fragment。
- **最低支持**：Android 8.0（minSdk 26），与现有 `app/build.gradle.kts` 一致。
- **Java/Kotlin 目标**：Java 11，Kotlin 官方代码风格（与 `gradle.properties` 一致）。

---

## 2. 用户场景与端到端流程

```
[首页] 选择视频 ──▶ [视频信息确认 + 模型选择] ──▶ [生成字幕 (Whisper)]
                                                          │
                                                          ▼
[导出/分享] ◀── [合成视频 (FFmpeg)] ◀── [字幕预览/编辑 (RecyclerView)]
```

关键交互细节：
- 选择视频走 SAF 的 `ActivityResultContracts.OpenDocument(["video/*"])`；保留 `Uri` 和 `takePersistableUriPermission` 以便后续在后台 Service 中读取。
- 识别与合成是长任务，UI 始终通过 `StateFlow<TaskState>` 订阅进度，允许后台运行；用户回到 App 后能从首页恢复进入。
- 字幕编辑界面用列表逐行编辑，**修改即时保存到内存中的 `Subtitle` 对象**，离开页面前持久化为 SRT 文件。

---

## 3. 整体架构

### 3.1 分层

```
┌──────────────────────────────────────────────────────────────┐
│ ui/  (View 层 — Fragment + ViewBinding)                      │
│   home / picker / progress / editor / settings              │
└──────────────────┬───────────────────────────────────────────┘
                   │ StateFlow / LiveData / SharedFlow(events)
┌──────────────────▼───────────────────────────────────────────┐
│ ui/*ViewModel  (ViewModel 层 — 持有 UI state，编排 use case) │
└──────────────────┬───────────────────────────────────────────┘
                   │ suspend 调用
┌──────────────────▼───────────────────────────────────────────┐
│ domain/usecase  (业务用例 — 纯 Kotlin，无 Android 依赖)       │
│   ProcessVideoUseCase = ExtractAudio → Transcribe → Burn    │
└──────────────────┬───────────────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────────────┐
│ data/repository  (聚合多个数据源，暴露 Flow / suspend API)   │
│   VideoRepository / TranscriptionRepository /               │
│   SubtitleRepository / ModelRepository                      │
└──────────────────┬───────────────────────────────────────────┘
                   │
┌──────────────────▼───────────────────────────────────────────┐
│ data/source                                                  │
│   FFmpegEngine     ← FFmpegKit 封装（音频提取/合成）         │
│   WhisperEngine    ← whisper-jni / whisper.cpp 封装          │
│   ModelDownloader  ← OkHttp 下载 ggml 模型                   │
│   MediaStoreSaver  ← 输出文件落地到 MediaStore.Movies        │
└──────────────────────────────────────────────────────────────┘
```

层间依赖只允许 **上层依赖下层**。`domain` 层不感知任何 Android 类型（包括 `Uri`），由 Repository 在边界做转换：传给 use case 的是已解析好的本地缓存 `File` 路径。

### 3.2 单 Activity + Navigation

- `MainActivity` 是唯一的 Activity，承载 `NavHostFragment`。当前 `app/src/main/java/com/frank/videosubtitle/MainActivity.kt` 已经处理了 edge-to-edge 与 system bars padding，保留这部分实现并把内容容器替换为 `FragmentContainerView`。
- 路由图（`res/navigation/nav_main.xml`）：
  - `homeFragment`（默认起点，含视频选择 + 历史任务列表）
  - `progressFragment`（识别 / 合成进度，可中途切换到后台）
  - `editorFragment`（字幕预览 + 编辑）
  - `settingsFragment`（模型选择、字幕样式预设）

### 3.3 包结构（目标）

```
com.frank.videosubtitle
├── VideoSubtitleApp                     (Application + Hilt entry)
├── MainActivity
├── di/                                  Hilt modules
├── ui/
│   ├── common/                          BaseFragment, UiState, exts
│   ├── home/        HomeFragment + HomeViewModel
│   ├── progress/    ProgressFragment + ProgressViewModel
│   ├── editor/      EditorFragment + EditorViewModel + SubtitleAdapter
│   └── settings/    SettingsFragment + SettingsViewModel
├── domain/
│   ├── model/       Subtitle, SubtitleSegment, VideoMeta, TaskState, WhisperModel
│   └── usecase/     ExtractAudioUseCase, TranscribeAudioUseCase,
│                    BurnSubtitlesUseCase, ProcessVideoUseCase
├── data/
│   ├── repository/  VideoRepository, TranscriptionRepository,
│   │                SubtitleRepository, ModelRepository, TaskRepository
│   └── source/
│       ├── ffmpeg/  FFmpegEngine, FFmpegProgressParser
│       ├── whisper/ WhisperEngine, WhisperConfig, ModelDownloader
│       ├── media/   MediaStoreSaver, UriResolver
│       └── local/   TaskDao (Room), SubtitleStore (file-based)
└── util/            Logger, DispatcherProvider, Result/Resource
```

---

## 4. 技术选型（已决策）

| 关注点 | 选型 | 备注 |
|---|---|---|
| 语言 | Kotlin（JVM 11） | 与 AGP 9.1.1 默认 toolchain 一致 |
| UI 框架 | **XML + ViewBinding** + Navigation Component + Fragment | 已确认。复杂列表/编辑也用 RecyclerView 实现，不引入 Compose |
| 异步 | Kotlin Coroutines + Flow | ViewModel 用 `viewModelScope`，长任务在 Application 域 scope |
| DI | **Koin 4.1.0**（AGP 9 兼容版的 Hilt 尚未发布，详见 §4.3） | 单一选择，不做手写工厂 |
| 持久化 | Room（任务历史）+ 文件（SRT、模型）+ DataStore（偏好） | 不用 SharedPreferences |
| 后台任务 | WorkManager + 前台 Service（合成耗时大） | 任务可观察、可取消、可恢复 |
| 视频播放 | Media3 ExoPlayer（编辑预览用，可选） | v1 可先不内嵌播放器 |
| 视频处理 | **FFmpegKit**（`com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS.1`） | 已确认。ffmpeg-kit 上游归档，需固定版本，并在 README 注明替换备选 |
| 语音识别 | **Whisper 第三方封装**：`io.github.givimad:whisper-jni`（首选） | 已确认。备选见 §4.1 |
| 模型分发 | App 内下载 ggml-base.bin（默认 ~140MB），存到内部 `filesDir/models` | 不打进 APK |
| 文件选择 | SAF：`ActivityResultContracts.OpenDocument` | 取得 persistable URI 权限 |
| 输出落地 | MediaStore（`Movies/VideoSubtitle/`） | 兼容 scoped storage |
| 日志 | Timber + 写入 `cacheDir/logs/yyyymmdd.log` | 便于用户提交问题 |
| 测试 | JUnit4 + Truth + Turbine + MockK；Espresso 仅核心路径 | 已存在 `ExampleUnitTest`/`ExampleInstrumentedTest` 占位 |

### 4.1 Whisper 集成路径

**首选**：`whisper-jni`（GiviMAD），原因：
- Maven Central 直接拉取，无须本地编 NDK；
- 暴露 `WhisperContext` / `WhisperFullParams`，能拿到段级 + 词级时间戳；
- 模型文件即标准 GGML（`ggml-tiny/base/small.bin`），与 VideoCaptioner 桌面版完全一致，便于复用 prompt（如中文场景的 "你好，我们需要使用简体中文..." 提示词，参考 `core/asr/whisper_cpp.py:107`）。

**风险与备选**：
- whisper-jni 的 Android `.so` 体积较大（armeabi-v7a/arm64-v8a 合计 ~30MB）；如需进一步瘦身，备选：
  - **vilassn/whisper_android**：把 whisper.cpp 编成 AAR 模块，体积更可控。
  - **Argmax WhisperKit Android**：最新方案，工程化更完整，但生态/版本较新。
- 集成前必须验证：`arm64-v8a` 和 `armeabi-v7a` 都跑得通；Vulkan/OpenCL 加速在多数机型不可用，CPU 推理速率以 `base` 模型 / 1080P 5min 视频 ≈ 实时倍率 1× 为基线。

### 4.3 DI：为什么是 Koin 而不是 Hilt

最初规划是 Hilt（codegen 路线）。Phase 0 接入时遇到：Hilt Gradle 插件在 AGP 9.x 上抛 `Android BaseExtension not found`。修复 PR（dagger #5084）2026-01-20 才合并到 main，**Maven Central 上还没有任何兼容 AGP 9 的 Hilt 发布版（最新是 2025-04 的 2.56.2）**。

可选项：
1. 降级 AGP 到 8.9.x —— 失去 `compileSdk { release(36) { minorApiLevel = 1 } }` DSL；与现有项目 AGP 9.1.1 选型冲突。
2. 等 Hilt 新版本 —— 阻塞 Phase 0。
3. 切到 Koin —— 无 codegen、无 Gradle 插件、与 AGP 9 零冲突；语义上 `@Inject` 构造 → `module { single { ... } }` 一一映射。**已采用此项**。

**后续何时考虑切回 Hilt**：当 Hilt 发布 ≥ 2.57 且明确兼容 AGP 9 时，可在一个独立分支上评估迁移。所有依赖注入点都通过 `module {}` 集中声明，迁移成本只限定在 `di/` 目录。

### 4.2 FFmpegKit 选型说明

- `ffmpeg-kit-full-gpl` 包含 `libass` + `libfreetype`，是 `subtitles=` filter 烧入字幕样式的前提（普通包不含 libass）。
- 锁定 `6.0-2.LTS.1`（最后稳定版，2024 年发布）。**上游已归档**，团队需确认能接受"不再有上游修复"。在 v2 评估迁移到 Media3 Transformer + 自渲染 OverlayEffect。
- 包大小代价：`full-gpl` ABI 拆分后单架构 APK 增加 ~25–35MB。设置里默认 ABI splits + R8 关闭混淆 ffmpeg 相关包（`proguard-rules.pro` 添加保留规则）。

---

## 5. MVVM 落地约定

### 5.1 ViewModel 与 UI 状态
- 每个屏幕一个 `*ViewModel`，UI 状态封装为单一不可变 `data class UiState(...)`，通过 `StateFlow<UiState>` 暴露。
- 一次性事件（导航、Toast）走 `Channel` / `SharedFlow`，**不放进 `UiState`**。
- `Fragment` 用 `viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(STARTED) { vm.uiState.collect ... } }` 订阅，避免后台收集。

### 5.2 长任务的归属
- 视频处理任务的真实状态属于 **`TaskRepository`（应用单例）**，不属于任何 ViewModel。
- ViewModel 只是状态的"投影"。这是为了：
  - 用户离开 `ProgressFragment` 后 ViewModel 销毁，但任务必须继续跑；
  - 多个屏幕（首页历史、进度页）能看到同一个任务的进度；
  - 进程被系统杀掉再恢复时，能从 Room + 文件状态重建。

### 5.3 单向数据流
- View → ViewModel：通过函数调用（`onPickVideoClicked()`），不暴露 LiveData 双向绑定。
- ViewModel → View：只通过 `StateFlow`/`SharedFlow`。
- ViewModel → Repository：`suspend` 函数 + `Flow`。Repository 内部决定缓存 / 下载 / 计算的来源。

### 5.4 错误传递
统一用 `sealed interface DomainResult<out T> { data class Success<T>(val value: T); data class Failure(val error: AppError) }`。
`AppError` 枚举常见错误（`ModelMissing`, `AudioExtractFailed`, `TranscribeFailed`, `BurnFailed`, `Cancelled`, `Unknown`），UI 层把 `AppError` 映射成本地化字符串，不直接显示异常 message。

---

## 6. 核心处理管线

### 6.1 Pipeline 概览

```
Uri ──[1]──▶ 缓存原视频    ──[2]──▶ 16kHz mono PCM WAV
                                          │
                                          ▼
                                     [3] Whisper 推理
                                          │
                                          ▼
                                     SubtitleSegment[]
                                          │
                                          ▼
                                     [4] 序列化 SRT
                                          │
                                          ▼
                                     [5] FFmpeg 合成
                                          │
                                          ▼
                                     [6] MediaStore 落地
```

每步都设计成幂等的 `suspend` 函数，输入/输出都是 `File` 路径或不可变数据，便于在后台任务恢复时跳过已完成步骤（通过 `TaskRepository` 中保存的中间产物路径）。

### 6.2 步骤详细设计

**[1] Uri → 本地缓存**
- 路径：`cacheDir/tasks/<taskId>/source.<ext>`
- 用 `ContentResolver.openInputStream(uri)` 流式拷贝；同时调用 `MediaMetadataRetriever` 抓取时长、分辨率、码率，写入 `VideoMeta`。
- 注意：必须先 `takePersistableUriPermission`，否则 App 重启后再用 Uri 读会失败。

**[2] 提取音频 — `FFmpegEngine.extractAudio(input, output)`**
- 命令（与 `videocaptioner/core/utils/video_utils.py:67` 的 `video2audio` 完全一致）：
  ```
  -i <input> -map 0:a:0 -vn -ac 1 -ar 16000 -y <output.wav>
  ```
- 输出：`cacheDir/tasks/<taskId>/audio.wav`，16kHz / mono / PCM s16le —— 这是 Whisper 唯一接受的格式。
- 进度通过 FFmpegKit `Statistics` 回调换算（`time / duration`）。

**[3] Whisper 转写 — `WhisperEngine.transcribe(wav, config)`**
- 输入参数：
  - `language`：`null` 表示 auto，或 ISO-639-1（`zh`、`en`...）。
  - `model`：`tiny | base | small`，对应 `ModelRepository` 已下载的文件。
  - `prompt`：中文场景默认注入 "你好，我们需要使用简体中文，以下是普通话的句子。"（参考 VideoCaptioner）。
  - `enableWordTimestamps`：v1 默认 false（段级足够），编辑器若需要词级再 true。
- 输出：`List<SubtitleSegment>`，字段 `index, startMs, endMs, text`。
- 进度：whisper-jni 的 `progressCallback` 回调按比例上报 0~100。
- 取消：`CoroutineScope.cancel()` → `WhisperContext.close()`。

**[4] 序列化 SRT — `SubtitleRepository.writeSrt(segments, file)`**
- 自实现 SRT 序列化（无需依赖），毫秒格式 `HH:MM:SS,mmm --> HH:MM:SS,mmm`。
- 同时维护一份 `subtitle.json`（含未编辑前的原始时间戳），用于重置编辑。

**[5] 合成 — `FFmpegEngine.burnSubtitles(video, srt, output, options)`**
- **硬字幕**（默认）：与 `video_utils.py:add_subtitles` 同构。
  ```
  -i <video> -acodec copy -vcodec libx264 -crf 23 -preset medium
  -vf "subtitles='<escaped srt path>'"  -y <output>
  ```
  路径转义遵循 ffmpeg filter 规则：把 `:` 转 `\:`，反斜杠转 `/`。
- **软字幕**（仅当输出容器为 `.mp4/.mov`）：
  ```
  -i <video> -i <srt> -c:v copy -c:a copy -c:s mov_text -y <output>
  ```
- 进度通过 FFmpegKit `Statistics.time / videoMeta.durationMs` 计算。

**[6] 落地 MediaStore — `MediaStoreSaver.saveToMovies(file, displayName)`**
- API 29+ 用 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` + `RELATIVE_PATH = Movies/VideoSubtitle/`。
- API 26–28 退化到 `getExternalStoragePublicDirectory(DIRECTORY_MOVIES)` 路径（已声明 `WRITE_EXTERNAL_STORAGE` 权限）。

### 6.3 进度合成
任务总进度 = 加权和：音频提取 5% + 识别 70% + 合成 25%。`TaskRepository` 把每步的 0–100 映射到全局 0–100 后再发到 `StateFlow`。

---

## 7. 数据模型（domain/model）

```kotlin
data class VideoMeta(
    val sourceUri: String,           // content:// uri (string)
    val cachedFile: String,          // local cached path
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrateKbps: Int,
    val videoCodec: String,
    val audioCodec: String,
    val audioSampleRate: Int,
)

data class SubtitleSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class Subtitle(
    val segments: List<SubtitleSegment>,
    val language: String?,           // null = auto-detected
)

enum class WhisperModel(val fileName: String, val sizeMb: Int) {
    TINY("ggml-tiny.bin", 75),
    BASE("ggml-base.bin", 142),
    SMALL("ggml-small.bin", 466),
}

sealed interface TaskStage {
    data object Idle : TaskStage
    data class ExtractingAudio(val percent: Int) : TaskStage
    data class Transcribing(val percent: Int) : TaskStage
    data object Editing : TaskStage
    data class Burning(val percent: Int) : TaskStage
    data class Done(val outputUri: String) : TaskStage
    data class Failed(val error: AppError) : TaskStage
}

data class TaskState(
    val taskId: String,
    val video: VideoMeta,
    val stage: TaskStage,
    val totalPercent: Int,
    val subtitle: Subtitle?,
    val createdAt: Long,
)
```

Room 实体只持久化 `taskId, video json, stage 名, totalPercent, subtitlePath, outputUri, createdAt, updatedAt`，复杂结构以 JSON 字符串存储（kotlinx.serialization）。

---

## 8. 关键技术问题与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| FFmpegKit 已归档 | 无上游安全/兼容更新 | 锁定 6.0-2.LTS.1；在 SDD §4.2 标注 v2 评估 Media3 Transformer 替代；用 `FFmpegEngine` 接口隔离 ffmpeg-kit，方便替换 |
| Whisper 推理慢 / 设备发烫 | 用户体验差，可能被系统降频 | 默认 `base` 模型 + 4 线程上限；任务跑在前台 Service，电池告警时降到 `tiny` |
| 模型文件大（base ~140MB） | 首装下载慢，存储占用高 | 安装后引导一次性下载；下载 + 校验 SHA-256；提供"删除模型"选项 |
| SAF Uri 权限丢失 | App 重开后任务无法续接 | `takePersistableUriPermission`，并把视频拷贝到 `cacheDir` 一次性使用 |
| 中文识别错字多 | 影响主要用户场景 | 注入中文 prompt（同 VideoCaptioner）；语言被识别为 zh 时 prefer simplified |
| 长视频内存爆炸 | OOM | 永远走"音频提取到磁盘 → 流式喂给 Whisper"，不在内存里保留整段 PCM |
| `subtitles=` filter 路径转义错 | 合成失败 | 复制字幕到 `cacheDir/tasks/<id>/subs.srt`（ASCII 路径）后再传给 ffmpeg，规避中文/空格路径 |
| FFmpeg 重新编码慢 | 720P+ 视频耗时 | 默认 `preset=medium`，设置中提供 `ultrafast` 选项；提示用户软字幕模式更快 |
| 进程被杀任务丢失 | 状态混乱 | Room 持久化任务；恢复时检查中间产物是否存在，从最近一个完成的步骤继续 |

---

## 9. 待决策与未来扩展

1. **是否引入 LLM 字幕优化**（VideoCaptioner `core/optimize`）。倾向放到 v2，需要先评估免费/低成本 LLM 接入路径。
2. **是否支持 ASS 富文本样式**。倾向 v2，若做需要参考 VideoCaptioner 的 `core/subtitle/ass_renderer.py`。
3. **是否内置 ExoPlayer 预览编辑结果**。强体验提升，复杂度中等，建议放在合成完成后的"结果页"，v1.1 加。
4. **Whisper 模型量化到 q5_0** 以减体积（base 142MB → ~57MB）。需要验证识别质量是否可接受。
5. **多音轨选择**。v1 固定取 0:a:0；多音轨视频用户可在 v2 中通过 `audioStreams` 列表选择。

---

## 10. 与 VideoCaptioner 的对应关系（便于代码迁移参考）

| Android 模块 | VideoCaptioner 对应 | 说明 |
|---|---|---|
| `data/source/ffmpeg/FFmpegEngine.extractAudio` | `videocaptioner/core/utils/video_utils.py:video2audio` | 命令参数完全复刻 |
| `data/source/ffmpeg/FFmpegEngine.burnSubtitles` | `core/utils/video_utils.py:add_subtitles` | 软/硬字幕分支同构 |
| `data/source/whisper/WhisperEngine` | `core/asr/whisper_cpp.py` | 进度解析逻辑可参考 stdout 时间戳计算 |
| `domain/usecase/ProcessVideoUseCase` | `videocaptioner/cli/process.py`（如有） | 全流程编排 |
| `data/source/whisper/SrtSerializer` | `core/asr/asr_data.py:ASRData.to_srt` | 字幕序列化 |
| 中文 prompt 注入 | `core/asr/whisper_cpp.py:107` | 完全复用 |

---

## 11. 构建与权限清单（落地必需）

`AndroidManifest.xml` 需新增：
- `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>` （前台 Service 进度通知，API 33+）
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"/>` （API 34+ 必须声明子类型）
- `<uses-permission android:name="android.permission.INTERNET"/>` （仅模型下载用）
- `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>` （回退路径）
- `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28"/>`
- 注册 `VideoProcessingService`（`foregroundServiceType="mediaProcessing|dataSync"`）。

`app/build.gradle.kts` 新增片段（具体版本号在实施阶段锁定）：
- `id("org.jetbrains.kotlin.android")`、`id("com.google.devtools.ksp")`、`id("dagger.hilt.android.plugin")`、`id("androidx.navigation.safeargs.kotlin")`。
- `buildFeatures { viewBinding = true }`。
- ABI splits：`arm64-v8a`、`armeabi-v7a`。
- Hilt / Room / Navigation / Coroutines / FFmpegKit / whisper-jni / Timber / OkHttp / Media3（可选）依赖。

`proguard-rules.pro`：
- 保留 `com.arthenica.**`、`io.github.givimad.**`、whisper.cpp 的 native 入口类。

---

## 12. 验收标准（v1）

1. 在 Pixel 6（arm64-v8a）上选 5 分钟 1080p mp4，使用 `base` 模型，全流程跑通，输出可在系统相册播放。
2. 任务运行中切回桌面 → App 被系统杀掉 → 重新打开 App，能看到任务恢复并继续（或至少展示失败原因，提供重试）。
3. 中文 ASR 在标准普通话场景下，与 VideoCaptioner 桌面版同模型在同视频上的字符错误率（CER）差异 ≤ 2%。
4. 硬字幕合成产物用 `ffprobe` 看不到 subtitle stream，软字幕产物存在 `mov_text` 流。
5. 卸载 App 后，`/sdcard/Movies/VideoSubtitle/` 中的输出视频保留（已转移到 MediaStore 用户域）。
