# Playback and UI components

777tv uses AndroidX Media3 ExoPlayer and the Android platform's MediaCodec
decoders. Optional SDR picture adjustments use Media3's OpenGL effects pipeline;
neutral playback uses the direct hardware Surface path.

ExoPlayer/Media3, AndroidX, Kotlin and Coil use Apache-2.0. Their notices are
retained by Android dependency packaging. No proprietary MySTB code, APK assets,
libmpv or bundled FFmpeg/native playback binaries are included.

## YouTube feature

- NewPipeExtractor v0.27.1 is used, unmodified, for resolving public YouTube media.
  License: GPL-3.0-or-later. Corresponding source:
  https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.27.1
- Mozilla Rhino 1.8.1: MPL-2.0 (additional portions use BSD licenses described
  in its license). Source: https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release
- jsoup 1.24.1: MIT. Source: https://github.com/jhy/jsoup/tree/jsoup-1.24.1
- nanojson, pinned to e9d656ddb49a412a5a0a5d5ef20ca7ef09549996: Apache-2.0.
  https://github.com/TeamNewPipe/nanojson
- ZXing Core 3.5.3, OkHttp 4.12.0 and Android core library desugaring: Apache-2.0.
- Protocol Buffers Java Lite 4.36.2: BSD-3-Clause.

Full NewPipeExtractor, Rhino, jsoup and nanojson license texts are bundled under
`assets/licenses/`. The YouTube feature uses the same native Android UI and
ExoPlayer as the rest of 777tv. TizenTube Cobalt was inspected as a reference;
its APK, Chromium/Cobalt engine, website UI and assets are not bundled.
