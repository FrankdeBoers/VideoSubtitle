# VideoSubtitle

[English](./README.md) | [中文](./README_zh.md)

一款 Android 应用，提供离线一站式流水线：**从相册选择本地视频 → whisper.cpp 端侧识别 → 编辑字幕 → FFmpeg 合成 → 写入系统相册**。参考实现：Python 桌面版 [`VideoCaptioner`](https://github.com/WEIFENG2333/VideoCaptioner) —— 本工程只参考其核心管线（识别 → 字幕 → 合成），不参考 LLM、在线下载等能力。

- 包名：`com.frank.videosubtitle`
- `minSdk` 26 · `targetSdk` 36 · `compileSdk` 36.1 · Java / Kotlin 11
- 单 module 工程（root + `:app`）

## Spec-Driven Development（本项目的开发模式）

本项目坚持 **先规约、再代码**：下面两份文档是契约，代码是契约的实现。**任何非琐碎改动开工前都按顺序读完。**

1. [`docs/SDD.md`](./docs/SDD.md) —— Spec / Design Document。范围与非目标（§1）、端到端用户流程（§2）、分层架构（§3）、含理由的技术选型（§4）、MVVM 落地约定（§5）、含具体 FFmpeg / Whisper 命令形状的处理管线（§6）、领域模型（§7）、风险登记（§8）、构建与权限清单（§11）、v1 验收标准（§12）。
2. [`docs/IMPLEMENTATION_PLAN.md`](./docs/IMPLEMENTATION_PLAN.md) —— 分阶段实施计划（Phase 0 脚手架 → Phase 7 设置/UX 打磨）。每个 Phase 都给出：明确目标、关键交付物（代码 + 文档）、验收标准、风险点。**当前进度：Phase 7 末尾。**

**SDD 过程约束**（沿用自 `IMPLEMENTATION_PLAN.md` 与 `CLAUDE.md`）：

- **规约先行。** 新行为先在 `SDD.md` 中规约（并在 `IMPLEMENTATION_PLAN.md` 中拆解），*然后*才落第一条 commit。
- **Phase 闸门。** 上一阶段的验收标准没跑通之前，不进下一阶段。
- **同 Phase 内接口先行。** 每个数据源 / Repository 先写接口和 Fake；UI 先消费 Fake；真实实现后续替换。UI 与底层并行不阻塞。
- **代码与测试同提交。** Domain / data 的纯逻辑必须随同 JVM 单测一起提交；UI 层只测 ViewModel 状态。
- **小步可回滚。** 每个 Phase 拆成 3–6 个能独立编译的 commit。
- **偏离规约必须同步更新规约。** 有意偏离 SDD 时，**同一个 diff 里**修改 SDD，绝不让代码与规约静默漂移。当前已记录的偏离见本文末尾。
- **参考实现而非克隆。** Python 桌面版 [`VideoCaptioner`](https://github.com/WEIFENG2333/VideoCaptioner) 用作 §6 / §10 中 FFmpeg / Whisper 命令形状的参照；其 LLM、翻译、下载等能力不纳入 v1。

## 不变量（SDD §1.4）

下面这些约束是承重的，改动需谨慎：

- **离线优先。** 默认链路下，语音识别与字幕烧录全部在设备本地完成，不向任何服务发送音视频或文本。可选的在线翻译后端（百度 / 有道 / 腾讯 / 微软）属于 v1 范围之外的扩展，需用户显式选择；默认翻译后端为 **ML Kit 端侧翻译**。
- **MVVM。** 单 Activity + Navigation Component + Fragment。UI 状态用 `StateFlow<UiState>` 暴露；一次性事件走 `SharedFlow`/`Channel`，**不放进 `UiState`**。
- **只用 XML + ViewBinding。** 不引入 Compose。列表与编辑器统一用 RecyclerView。
- **Domain 层不感知任何 Android 类型。** `Uri` → 本地缓存 `File` 的转换在 Repository 边界完成；use case 只接收/返回基本类型、`File`、领域模型。
- **长任务归 Repository 而非 ViewModel。** `TaskRepository`（应用单例）持有任务真正的状态，能在 ViewModel 重建与进程重启之后存活；ViewModel 只是它的"投影"。
- **管线步骤在磁盘上幂等。** 每一阶段写入 `cacheDir/tasks/<taskId>/`（`source.<ext>`、`audio.wav`、`subtitle.srt`、`subtitle.original.srt` …），即使被系统杀掉重启，也能跳过已完成的步骤。应用启动时 `TaskRepository.recoverInterrupted()` 会按磁盘上的中间产物把卡在某一阶段的任务回退到最近一个已完成节点；恢复后**不会自动续跑**，由用户手动重新触发。

## 处理流水线（SDD §6）

```
Uri ──[1]──▶ cacheDir/tasks/<id>/source.<ext>    ──[2]──▶ audio.wav (16kHz mono PCM)
                                                                │
                                                                ▼
                                                       [3] Whisper 推理  → SubtitleSegment[]
                                                                │
                                                                ▼
                                                       [4] subtitle.srt（可附译文）
                                                                │
                                                                ▼
                                                       [5] FFmpeg 合成（硬字幕 / 软字幕）
                                                                │
                                                                ▼
                                                       [6] MediaStore.Movies/VideoSubtitle/
```

总进度采用加权和：**音频提取 5% + 识别 70% + 合成 25%**。

各步骤命令形式与 VideoCaptioner 完全一致：
- **提取音频**：`-i in -map 0:a:0 -vn -ac 1 -ar 16000 -y out.wav`，对齐 `videocaptioner/core/utils/video_utils.py:video2audio`。
- **硬字幕**（重新编码，默认）：`-vcodec libx264 -crf 23 -preset <preset> -vf "subtitles='<srt>'"`，对齐 `add_subtitles`。
- **软字幕**（仅 mp4/mov，不重编码）：`-c:v copy -c:a copy -c:s mov_text`。

磁盘空间检查：提取与烧录前用 `StatFs` 检查 `cacheDir`，剩余空间不足 `2 × 源视频大小` 直接失败。

## 功能特性

- **本地 SAF 选片**，并 `takePersistableUriPermission`，确保后台任务在 App 重启后仍能读取该 Uri。
- **Whisper 模型可选** —— Tiny / Base / Small（GGML，从 HuggingFace 下载，**SHA-256 校验**）。校验不通过直接报错，绝不把损坏的权重喂给 whisper.cpp。
- **多语言识别** —— 自动 / 中文 / English / 日本語 / 한국어。每种语言带不同的 `initial_prompt` 来引导解码（例如中文会注入"以下是普通话的句子，使用全角标点。"）。
- **硬字幕 / 软字幕可切换** —— 硬字幕重编码（任意容器）或 `mov_text` 封装（仅 mp4/mov，更快）。
- **字幕文本/时间轴编辑** —— RecyclerView 行级编辑，返回键会确认未保存的修改。
- **字幕样式编辑** —— 字号 16–60 sp、颜色、描边、九宫格对齐、上下/左右边距、可选半透明背景。预览以 1080×720 为基准，按手机屏幕等比缩放。
- **双语字幕（v1 之外的扩展）** —— 仅原文 / 仅译文 / 同时显示，原文与译文可分别配置字号、颜色。默认翻译后端为 ML Kit 端侧；在线后端属于打破"离线优先"不变量的可选项。
- **并发策略** —— 提取音频和烧录字幕在多任务间并行；**语音识别串行**（实测多个 whisper 实例并行反而比串行更慢）。
- **前台服务** —— `VideoProcessingService` 在长任务期间维持进程存活，只发一条滚动通知，含进度条与"取消"按钮。
- **批量操作** —— 长按任务进入多选模式，支持批量删除，或"批量开始"一键跑完 提取 → 识别 → 烧录 全流程。
- **国际化** —— 简体中文（默认）+ 英文界面；新增字符串需保持双语对齐。

## 技术栈（SDD §4）

| 模块 | 选型 |
| --- | --- |
| 构建 | Gradle wrapper、AGP **9.1.1**、Version Catalog（`gradle/libs.versions.toml`） |
| UI | XML + ViewBinding + Navigation Component + Fragment（不引入 Compose） |
| 异步 | Coroutines + Flow；长任务跑在应用域 `SupervisorJob+IO` |
| 依赖注入 | **Koin 4.1.0** —— Hilt 暂无兼容 AGP 9 的发布版（详见 SDD §4.3） |
| 持久化 | Room（`androidx.room` 插件导出 schema） + Preferences DataStore + 文件 SRT |
| 网络 | OkHttp（模型下载并 SHA-256 校验） |
| 图片 | Coil（视频缩略图） |
| 语音识别 | 内置 `whisper.cpp` v1.7.5 源码于 `app/src/main/cpp/whisper.cpp/`，NDK + CMake 构建为 `libwhisper.so`。Kotlin 门面：`com.whispercpp.whisper.WhisperLib`（`object`，保证 JNI 符号稳定） |
| 视频 / 音频 | FFmpegKit `6.0.LTS`（`ffmpeg-kit-full-gpl`，含 libass + fontconfig + freetype）。FFmpegKit 已于 2025 年从 Maven Central 下架，工程改用阿里云 / 华为云 Maven 镜像；`settings.gradle.kts` 中以 `content { includeGroup("com.arthenica") }` 严格隔离 |
| 翻译（离线） | Google ML Kit 端侧翻译 |
| 日志 | Timber |
| 测试 | JUnit4 + Truth + Turbine + MockK；Espresso 仅核心路径 |

## 编译与运行

在仓库根目录使用 Gradle wrapper：

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:installDebug           # 安装到已连接的设备 / 模拟器
./gradlew :app:assembleRelease        # R8 优化后的 release APK（arm64-v8a 约 31 MB，目标 ≤ 80 MB）
./gradlew :app:lint                   # 报告位于 app/build/reports/lint-results-*.html
./gradlew :app:testDebugUnitTest      # JVM 单元测试（app/src/test）
./gradlew :app:connectedDebugAndroidTest   # 设备测试（需要设备 / 模拟器）
./gradlew clean
```

ABI：通过 `splits.abi` 输出 `arm64-v8a` 与 `armeabi-v7a`。如果你在 x86_64 模拟器上构建，请**只在本地**把 `x86_64` 加进 `splits.abi.include`，不要提交。当前 release APK **未签名**（签名配置延后）；Crashlytics / Sentry 延后到 v1.1。

## v1 验收标准（SDD §12）

1. 在 arm64-v8a 设备上，对一段 5 分钟 1080p mp4 用 `base` 模型跑通全流程，输出可在系统相册播放。
2. 任务运行中切回桌面 → App 被系统杀掉 → 重新打开 App，能看到任务恢复（或展示失败原因，提供重试）。
3. 标准普通话场景下，与桌面版 VideoCaptioner 在同模型 + 同视频上的字符错误率（CER）差异 ≤ 2%。
4. 硬字幕产物 `ffprobe` 看不到 subtitle stream；软字幕产物存在 `mov_text` 流。
5. 卸载 App 后，`Movies/VideoSubtitle/` 中的输出视频保留（已转移到 MediaStore 用户域）。

## 工程结构（关键目录）

```
app/src/main/cpp/                          ← 内置 whisper.cpp + JNI 桥 → libwhisper.so
app/src/main/java/com/whispercpp/whisper/  ← WhisperLib.kt（Kotlin 门面，稳定 JNI 符号）
app/src/main/java/com/frank/videosubtitle/
├── VideoSubtitleApp.kt                    ← Application + Koin + Timber + libass 字体目录注册
├── MainActivity.kt                        ← 单 Activity，NavHostFragment，edge-to-edge
├── di/                                    ← AppModule / DataModule / UiModule（Koin）
├── domain/{model,engine,usecase}/         ← 不依赖任何 Android 类型
├── data/
│   ├── engine/                            ← FFmpegKitEngine、WhisperJniEngine、各翻译引擎
│   ├── orchestrator/TaskOrchestrator.kt   ← 提取 → 识别 → 编辑 → 烧录，全步骤幂等
│   ├── repository/                        ← TaskRepository（含 recoverInterrupted）、Video / Model / Settings
│   └── source/{local,media}/              ← Room + SrtSerializer + UriResolver + MediaStoreSaver
├── service/VideoProcessingService.kt      ← 前台服务，滚动通知，Cancel 动作
└── ui/{home,progress,editor,settings,common}/
```

`stageKind` 字符串（`idle/extracting/transcribing/editing/burning/done/failed`）已经持久化进 Room —— 重命名等同 schema 变更，由 `TaskMappersTest` 钉死。

## 工具链注意事项

挑几个不那么显然的，完整版见 [`CLAUDE.md`](./CLAUDE.md)：

- AGP 9.0+ 已经内置 Kotlin 支持 —— **不要**再 apply `org.jetbrains.kotlin.android`（会被官方插件主动拒绝，参见 issuetracker.google.com/438678642）。
- Hilt 暂未支持 AGP 9，工程改用 Koin（SDD §4.3）。
- `whisper-jni` 只发布桌面端二进制（macOS/Linux/Windows GLIBC），所以工程内置了 `whisper.cpp` 源码。
- Android 上 libass 必须显式注册字体目录，详见 `VideoSubtitleApp.onCreate()`。否则烧录能正常完成，但所有字符渲染为空白。
- API 34+ 的前台服务类型用 **`dataSync`**，不是 `mediaProcessing`（Android 16 / targetSdk 36 会抛 `InvalidForegroundServiceTypeException` 拒绝启动）。这是与 SDD §11 的有意偏离；两者共享同样的 6h/天 配额。

## 与 SDD 的偏离

为追溯方便记录于此：
- **翻译流水线（Phase 7 之后追加）。** SDD §1.3 把翻译列为 v1 非目标。当前实现已加入翻译环节（Whisper → 翻译 → 双语 SRT），默认离线后端为 ML Kit。四个在线后端会破坏"离线优先"不变量，仅作可选项。
- **前台服务类型。** SDD §11 声明的是 `mediaProcessing`；运行时在 targetSdk 36 上被强制改为 `dataSync`（详见上文）。
- **Hilt → Koin。** 已在 SDD §4.3 中记录。

## License

暂未声明。
