package com.frank.videosubtitle.domain.model

/**
 * Which native pipeline drives [extractAudio] and [burnSubtitles].
 *
 *  - [Ffmpeg] is the default. Software decode + libass + libx264. Renders every
 *    subtitle styling option the app exposes, at the cost of CPU-bound encode
 *    speed on long videos.
 *  - [AndroidMedia] routes through Media3 Transformer (MediaCodec hardware
 *    decode + encode, OverlayEffect for subtitle compositing). Faster on long
 *    videos, but the OverlayEffect path can't reproduce libass `{\fs..\c..}`
 *    per-line overrides — when the user has configured distinct styling for
 *    translated lines, [com.frank.videosubtitle.data.engine.RoutingMediaEngine]
 *    silently falls back to [Ffmpeg] for that single burn call.
 */
enum class MediaBackend { Ffmpeg, AndroidMedia }
