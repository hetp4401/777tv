# Navigation performance · 0.25.0

777tv keeps its native Kotlin views, existing themes and one ExoPlayer. This
candidate changes scrolling and presentation work across YouTube, On-Demand,
Live TV and profile lists. It is not deployed to the TCL: the user has explicitly
held installation and testing until asked. The last known installed version is
0.24.0/code 32.

RecyclerView follows focus with a distance-based 70–140 ms decelerating scroll,
instead of restarting its longer default pan. Remote input is not throttled.
Nearby poster, stream and episode holders are retained; YouTube prefetch covers
the viewport with fixed caps. Labels are only assigned when changed. Stream
sorting, semantic labels and episode indexes are prepared for the current list
or series and released when that screen closes. In-flight equivalent diffs are
coalesced, and identity indexes change only when their adapter commit succeeds.

YouTube keeps a temporary page-local focus owner while a destination shelf
attaches. Further keys update the logical destination; the owner disables after
the matching committed card receives focus. A pending OK requires its matching
uncanceled release. New input, Back, backgrounding, account or source changes
retire that intent. The old build's corrected ten-row test reproduced keys
escaping to top navigation while a shelf was detached; both candidate worker
runs reach all seven requested destinations. Live cached rows refresh preview
and favorite marks on reattach. Browsing never starts playback or tunes a channel.

The artwork LRU counts pixel bytes and is capped at 8 MiB on low-RAM devices and
16 MiB otherwise (previously 4/8 MiB), with existing memory-pressure clearing.
YouTube's unbound card pools cap regular/hero/portrait cards at 6/6/8; outer and
inner caches each retain two holders. On-Demand poster/stream caches retain four,
episode buttons six. Decoder concurrency, artwork disk-cache size, playback
buffers, add-ons and saved preferences are unchanged. The APK adds 6,928 bytes
and no dependency, native library or additional player.

The matched comparison uses production 0.24.0 at `dcf4336` and this candidate on
the same API 36 x86_64 KVM worker instance with SwiftShader, generated distinct
HTTP artwork and asynchronous window-delivered D-pad events. Horizontal profiles
send 18 Right presses, vertical YouTube sends 8 Down presses through ten shelves,
and Live sends 24 Down presses through 6,000 channels. Taps target 80 ms spacing;
held repeats target 45 ms. The report records actual delivered events rather
than assuming the emulator maintained that cadence.

Settlement below means the intended card has focus, scrolling is idle, layout
and adapter work are complete, and the temporary focus owner is disabled. Times
run from the last injected Down to that confirmation. Each cell shows baseline
→ candidate; these are single matched worker trials, not physical-TV latency.

| Navigation | Reached / requested index | Settled after last input (ms) | Process CPU (ms) |
| --- | --- | --- | --- |
| on-demand posters · taps | 18/18 → 18/18 | 847 → 591 | 855 → 590 |
| on-demand posters · held | 18/18 → 18/18 | 903 → 503 | 601 → 340 |
| YouTube shelf · taps | 18/18 → 18/18 | 1296 → 305 | 650 → 562 |
| YouTube shelf · held | 18/18 → 18/18 | 1320 → 424 | 433 → 290 |
| YouTube vertical shelves · taps | 7/8 → 8/8 | Not reached → 447 | 452 → 483 |
| Live channels · taps | 24/24 → 24/24 | 506 → 204 | 428 → 307 |
| Live channels · held | 24/24 → 24/24 | 701 → 205 | 273 → 158 |

Retained PSS for this complete journey was 170.7 → 175.1 MiB,
without forcing GC. Whole-process PSS varies with allocator/GC timing; the fixed
artwork budget is the resource tradeoff, rather than a promised memory saving.
Both runs made zero channel-link requests while browsing.

The separate episode/stream input check also reaches index 18 in every profile.
Streams' p95 focus delay was 174 → 99 ms for taps and 119 → 94 ms held;
episodes were 102 → 105 ms and 115 → 87 ms. This shows improved held navigation,
not a universal reduction in every latency measurement.

Software-rendered frame timings still contain substantial jank. In the separate
forced artwork-scroll trials, p95 frame duration was 169/158 → 180/200 ms;
the 6,000-channel trial was 97 → 99 ms. These measurements do not establish
better GPU frame pacing or 60 FPS on a physical TV. The verification records
actual per-frame deadline overruns excluding first-draw frames, layout timings,
CPU and memory alongside the input results. Raw artifacts also retain GC
counters. A TCL comparison remains pending
the user's instruction to deploy.

Validation: 245 JVM passes, three opt-in skips,
lint with zero errors/107 warnings, and
126 unique native fixture scenarios. Coverage includes metadata refresh,
season/episode rollover, stream identity renewal and resume focus, search,
YouTube rail/grid/shelf bounds and reversals, deferred/canceled OK, source removal,
Back, cached Live favorites/preview marks, settings, themes and watch panels.
All 68 production file hashes plus three root build-configuration hashes
match the selected emulator snapshots and local release inputs.

The first vertical benchmark mistakenly requested eight moves through a fixture
which the production 120-video limit reduced to five shelves. Its result was
correct edge clamping and was discarded. The final fixture uses five shelves of
24 for horizontal input, then ten of 12 for vertical input, asserts their actual
capacity and requires every candidate destination. An earlier instant-scroll
prototype and early-IDLE settlement measurements were also discarded. Final
scrolling retains the native 70–140 ms animation.

[Verification](../releases/0.25.0/verification.json) contains the exact selected
jobs, source/report/APK checksums, all profile metrics, baseline failure and
validation scope. Raw generated-fixture reports stay in ignored local artifacts.
No real provider credentials or Google account mutations are used by these checks.
