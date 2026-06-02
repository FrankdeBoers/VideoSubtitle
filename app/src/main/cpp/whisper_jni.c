// JNI bridge for VideoSubtitle's WhisperLib. Adapted from whisper.cpp's
// examples/whisper.android/lib/src/main/jni/whisper/jni.c (MIT) with these
// extensions:
//   * fullTranscribe takes language / translate / prompt / numThreads
//   * a small heap-allocated 'state' struct exposes polled progress and an
//     abort flag, both written from whisper's progress / abort callbacks
//   * upstream's stdout-style realtime printing is disabled (we read segments
//     after whisper_full returns)
//
// All exported symbols match Java_com_whispercpp_whisper_WhisperLib_<method>
// so existing JNI naming conventions from upstream are preserved; the Kotlin
// class lives at com.whispercpp.whisper.WhisperLib (no Companion — methods are
// declared on the class itself for stable JNI symbols).
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml-backend.h"

// Forward decls of the C++ safety shims in whisper_safe.cpp. They funnel
// Vulkan-Hpp / std exceptions into stable rc values so this pure-C JNI
// layer can't be unwound past by an exception (which would otherwise
// abort the process — the Vulkan backend's vk::Queue::submit can throw
// vk::DeviceLostError mid-graph).
extern int whisper_full_safe(struct whisper_context *ctx,
                             struct whisper_full_params params,
                             const float *samples, int n_samples);
extern struct whisper_context *whisper_init_safe(const char *model_path,
                                                 struct whisper_context_params cparams);

#define TAG "WhisperJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct whisper_state_extras {
    volatile int progress;   // 0..100, last reported by progress_callback
    volatile int aborted;    // non-zero requests whisper_full to stop
};

static void progress_cb(struct whisper_context *ctx,
                        struct whisper_state *state,
                        int progress,
                        void *user_data) {
    (void)ctx; (void)state;
    struct whisper_state_extras *extras = (struct whisper_state_extras *) user_data;
    if (extras) extras->progress = progress;
}

static bool abort_cb(void *user_data) {
    struct whisper_state_extras *extras = (struct whisper_state_extras *) user_data;
    return extras && extras->aborted != 0;
}

// Internal helper: open a context with the requested params. Returns 0
// (which Kotlin reads as a null context) on failure so callers can fall back.
static jlong init_context_internal(JNIEnv *env, jstring model_path_str, jboolean use_gpu) {
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu ? true : false;
    // gpu_device defaults to 0; first-discovered device is fine until we
    // expose multi-GPU selection (none of our target SoCs ship >1 GPU).
    struct whisper_context *context = whisper_init_safe(model_path_chars, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    if (!context) {
        LOGW("whisper_init_from_file_with_params failed (use_gpu=%d)", (int)cparams.use_gpu);
    }
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void)thiz;
    return init_context_internal(env, model_path_str, JNI_FALSE);
}

// Companion variant that lets the host pick a backend. We keep both symbols
// for source compatibility per the comment at the top of this file.
JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContextWithParams(
        JNIEnv *env, jobject thiz, jstring model_path_str, jboolean use_gpu) {
    (void)thiz;
    return init_context_internal(env, model_path_str, use_gpu);
}

JNIEXPORT jboolean JNICALL
Java_com_whispercpp_whisper_WhisperLib_gpuAvailable(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    // ggml-backend-reg returns the count of registered devices across all
    // compiled-in backends. On a CPU-only build this is 1 (the CPU backend);
    // a Vulkan/OpenCL build that found a usable device exposes 2+.
    //
    // We can't link against the per-backend probes (e.g. ggml_backend_vk_get_device_count)
    // until the corresponding source is vendored — so we use the generic
    // device-count API and treat "more than CPU" as "GPU available."
    size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_gpuDeviceName(JNIEnv *env, jobject thiz) {
    (void)thiz;
    size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            const char *name = ggml_backend_dev_description(dev);
            return (*env)->NewStringUTF(env, name ? name : "");
        }
    }
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env; (void)thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context) whisper_free(context);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_newState(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    struct whisper_state_extras *extras =
            (struct whisper_state_extras *) calloc(1, sizeof(struct whisper_state_extras));
    return (jlong) extras;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_freeState(JNIEnv *env, jobject thiz, jlong state_ptr) {
    (void)env; (void)thiz;
    struct whisper_state_extras *extras = (struct whisper_state_extras *) state_ptr;
    if (extras) free(extras);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_readProgress(JNIEnv *env, jobject thiz, jlong state_ptr) {
    (void)env; (void)thiz;
    struct whisper_state_extras *extras = (struct whisper_state_extras *) state_ptr;
    return extras ? extras->progress : 0;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_setAbort(JNIEnv *env, jobject thiz, jlong state_ptr, jboolean abort) {
    (void)env; (void)thiz;
    struct whisper_state_extras *extras = (struct whisper_state_extras *) state_ptr;
    if (extras) extras->aborted = abort ? 1 : 0;
}

// Returns 0 on success, non-zero on failure (whisper_full return code) or
// -1 if the call was aborted.
JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz,
        jlong context_ptr,
        jlong state_ptr,
        jint num_threads,
        jstring language_str,    // nullable: null -> "auto"
        jstring prompt_str,      // nullable
        jboolean translate,
        jfloatArray audio_data) {
    (void)thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    struct whisper_state_extras *extras = (struct whisper_state_extras *) state_ptr;

    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_len = (*env)->GetArrayLength(env, audio_data);

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }
    const char *prompt = NULL;
    if (prompt_str != NULL) {
        prompt = (*env)->GetStringUTFChars(env, prompt_str, NULL);
    }

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = translate ? true : false;
    params.language         = language ? language : "auto";
    params.n_threads        = num_threads > 0 ? num_threads : 4;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;
    params.suppress_blank   = true;
    if (prompt != NULL) {
        params.initial_prompt = prompt;
    }
    if (extras != NULL) {
        params.progress_callback           = progress_cb;
        params.progress_callback_user_data = extras;
        params.abort_callback              = abort_cb;
        params.abort_callback_user_data    = extras;
    }

    whisper_reset_timings(context);

    LOGI("fullTranscribe: lang=%s prompt=%s threads=%d translate=%d samples=%d",
         params.language, prompt ? prompt : "(none)", params.n_threads,
         (int)translate, (int)audio_len);

    int rc = whisper_full_safe(context, params, audio, audio_len);

    if (language) (*env)->ReleaseStringUTFChars(env, language_str, language);
    if (prompt)   (*env)->ReleaseStringUTFChars(env, prompt_str, prompt);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    if (extras && extras->aborted) {
        LOGW("fullTranscribe: aborted by host (rc=%d)", rc);
        return -1;
    }
    if (rc == -2) {
        LOGW("fullTranscribe: GPU runtime exception — caller should retry on CPU");
    } else if (rc == -3) {
        LOGE("fullTranscribe: unrecoverable C++ exception during inference");
    } else if (rc != 0) {
        LOGE("fullTranscribe: whisper_full failed rc=%d", rc);
    } else if (extras) {
        extras->progress = 100;
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env; (void)thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    return (*env)->NewStringUTF(env, text ? text : "");
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)env; (void)thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)env; (void)thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_getSystemInfo(JNIEnv *env, jobject thiz) {
    (void)thiz;
    const char *sysinfo = whisper_print_system_info();
    return (*env)->NewStringUTF(env, sysinfo ? sysinfo : "");
}
