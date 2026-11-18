# 0.21.1 on the TCL

Installed and tested November 18, 2026 on the TCL Smart TV Pro, Android 14.
The in-place update preserves the application's UID and configuration. Version
code is 28. [Verification JSON](verification.json) records source hashes and
measurement details; [SHA256SUMS](SHA256SUMS) identifies the installed APK.

| Playback sample | First video frame | Steady window | Decoder drops / rebuffers | Playback PSS |
| --- | ---: | ---: | ---: | ---: |
| YouTube, adapting to 1080p60 AVC | 2.23 s | 30 s | 0 / 0 | 84.9 MiB |
| Portrait Shorts, 480×854 AVC | 1.76 s | 20 s | 0 / 0 | 73.4 MiB |
| On-demand, 3840×2160 HEVC | 2.87 s | 30 s | 0 / 0 | 121.6 MiB |
| Generated live HLS, 640×360 AVC | 0.99 s | 20 s | 0 / 0 | 65.1 MiB |

All four samples use MediaTek hardware decoding. A subsequent Short switched in
1.15 seconds. The first on-demand source returned upstream HTTP 502; the second
visible source played. The generated live sample uses no IPTV profile. There are
no configured profiles, so real Stalker authentication/channels remain untested.
First-frame timing is separate from first audible audio, which was not measured.

The awake, force-stopped Activity reaches first draw in a median 923 ms over
three launches; this is not catalog readiness. Guest YouTube Home data loaded in
2.39 seconds and Search reached its first visible card in 804 ms. Artwork readiness
was not timed. Initial Search navigation delivered all 24 moves, with four actual
frame-deadline overruns in 132 frames. Its warm repeat delivered all 24 moves with
none in 124 frames, p95 11.40 ms. Episode navigation delivered all 18 moves across
seasons, with none in 81 frames, p95 8.50 ms. Browsing keeps current streams until
OK; then the selected episode, description and streams update together.

The real controls pass: 10-second tap seek, 170-second advance in the specified
accelerated-hold input, reset to a 10-second step on release, preserved pause,
auto-hidden HUD, no YouTube Picture control, and no seek/pause transport for live.
Actual comments/replies and 30 Related videos load without replacing the decoder;
Next becomes available after metadata. Numeric reply counts now read clearly.
Keyboard input, query-preserving Back and voice-provider cancellation pass.

The uninstrumented On-Demand home uses 74.2 MiB PSS after final launch. Artwork
stays below its 8 MiB limit. Three short play/Back cycles release native decoder
allocations; managed memory fluctuates and is separately sampled after explicit
GC. These are bounded observations, not long-term leak proof or a TizenTube
speed comparison. Cold image loading still has occasional late frames. Optional
on-demand picture-effect adjustments were not exercised in these windows.

212 ordinary JVM tests and lint pass. The preceding 0.21.0 emulator coverage is
recorded separately; this patch changes the version and reply-label parser.
Independent actual-content review found no blocking visual issue. Standard
Android styling in the read-comment dialog remains minor polish. Real Google
owner approval, account mutations/history synchronization, dictated speech and
actual phone pairing require separate acceptance checks.

The release is left open on On-Demand. Temporary test instrumentation, diagnostic
TV downloads, reverse forwarding and local server were removed. Private captures
and raw logs remain outside Git. The [opt-in probe](../../scripts/device_probe/README.md)
documents the repeatable workload without resetting configuration.
