// C++ try/catch shims around whisper.cpp entry points that can throw
// Vulkan-Hpp exceptions (e.g. vk::DeviceLostError from vk::Queue::submit
// when the GPU driver loses the device mid-graph). The pure-C JNI side
// can't unwind C++ exceptions, so without this layer an exception bubbles
// past the JNI frame and aborts the process.
//
// All shims convert any std::exception to a stable negative return code
// the Kotlin host already special-cases.

#include <android/log.h>
#include <exception>
#include <string>
#include "whisper.h"

#define LOG_TAG "WhisperSafe"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// Return codes:
//   >= 0 : whisper_full's own rc (0 success, positive = whisper-internal error)
//   -1   : aborted (handled by JNI extras flag — never returned from this fn)
//   -2   : GPU runtime exception (vk::DeviceLostError etc.) — caller should
//          free the context and retry on CPU.
//   -3   : other std::exception during inference (treated as fatal by caller).
int whisper_full_safe(struct whisper_context * ctx,
                      struct whisper_full_params params,
                      const float * samples,
                      int n_samples) {
    try {
        return whisper_full(ctx, params, samples, n_samples);
    } catch (const std::exception & e) {
        const std::string what = e.what() ? e.what() : "(no message)";
        // vk::DeviceLostError formats as "vk::Queue::submit: ErrorDeviceLost"
        // (and similarly for other ResultValue errors). We treat anything
        // mentioning DeviceLost / OutOfDevice / OutOfHost as recoverable on
        // CPU; everything else as a hard failure.
        if (what.find("DeviceLost")     != std::string::npos ||
            what.find("OutOfDeviceMem") != std::string::npos ||
            what.find("OutOfHostMem")   != std::string::npos ||
            what.find("ErrorOutOfDate") != std::string::npos) {
            LOGW("whisper_full threw recoverable Vulkan exception: %s", what.c_str());
            return -2;
        }
        LOGE("whisper_full threw std::exception: %s", what.c_str());
        return -3;
    } catch (...) {
        LOGE("whisper_full threw unknown exception");
        return -3;
    }
}

// initContext path — Vulkan instance/device creation can also throw on
// driver bugs, OOM, layer load failure. Returning 0 (== nullptr context
// for Kotlin) lets WhisperJniEngine retry on CPU like it already does.
struct whisper_context * whisper_init_safe(const char * model_path,
                                           struct whisper_context_params cparams) {
    try {
        return whisper_init_from_file_with_params(model_path, cparams);
    } catch (const std::exception & e) {
        LOGW("whisper_init threw std::exception (use_gpu=%d): %s",
             (int) cparams.use_gpu, e.what() ? e.what() : "(no message)");
        return nullptr;
    } catch (...) {
        LOGW("whisper_init threw unknown exception (use_gpu=%d)",
             (int) cparams.use_gpu);
        return nullptr;
    }
}

}  // extern "C"
