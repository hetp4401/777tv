# Playback and UI components

777tv uses AndroidX Media3 ExoPlayer and the Android platform's MediaCodec
decoders. Optional SDR picture adjustments use Media3's OpenGL effects pipeline;
neutral playback uses the direct hardware Surface path.

ExoPlayer/Media3, AndroidX, Kotlin and Coil use Apache-2.0. Their notices are
retained by Android dependency packaging. No proprietary MySTB code, APK assets,
libmpv or bundled FFmpeg/native playback binaries are included.
