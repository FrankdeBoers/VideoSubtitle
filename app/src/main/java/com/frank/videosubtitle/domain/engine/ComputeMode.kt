package com.frank.videosubtitle.domain.engine

/**
 * Backend selection for Whisper inference. The plan in `docs/GPU_SUPPORT_PLAN.md`
 * starts with Vulkan; OpenCL is a future opt-in. The user-facing setting is a
 * single GPU toggle — which native backend actually services that toggle is
 * decided at link time + at runtime by capability probes.
 */
enum class ComputeMode {
    /** Pick GPU when [com.whispercpp.whisper.WhisperLib.gpuAvailable] is true; otherwise CPU. */
    Auto,
    Cpu,
    Gpu,
}
