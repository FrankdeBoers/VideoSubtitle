package com.frank.videosubtitle.data.engine

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridge OkHttp's [Call] to coroutines, propagating cancellation by calling
 * [Call.cancel] when the coroutine is cancelled. Mirrors the suspend-cancel
 * pattern used by [com.frank.videosubtitle.data.engine.MlKitTranslationEngine].
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}
