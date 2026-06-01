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

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void)thiz;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context *context = whisper_init_from_file_with_params(
            model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
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

    int rc = whisper_full(context, params, audio, audio_len);

    if (language) (*env)->ReleaseStringUTFChars(env, language_str, language);
    if (prompt)   (*env)->ReleaseStringUTFChars(env, prompt_str, prompt);
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);

    if (extras && extras->aborted) {
        LOGW("fullTranscribe: aborted by host (rc=%d)", rc);
        return -1;
    }
    if (rc != 0) {
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
