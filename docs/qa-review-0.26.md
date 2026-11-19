# 0.26 YouTube motion and playback review

This review covers the sidebar overlap reported in the TV photo, vertical shelf
navigation, fixed-time seeking, Shorts transitions, loading presentation and
the subsequent signed-out Home and transparent-rail feedback.
Independent reviewers examined native geometry/input, playback ownership and
resource cleanup, the official TizenTube reference, and the test assertions.

## Confirmed product corrections

A generated-media test reproduced a pending next-Short source reopening the
player after Back had already returned to browsing. The resolver's replacement
callback fell through to opening a new player when the original player route
was gone. Explicit Back and section/route changes now cancel pending playback
immediately; stale replacements cannot open another route. This also covers
leaving YouTube browsing while an initial Play is resolving. Explicit phone
handoffs and legitimate initial playback retain their separate ownership paths.

A separate native-window test reproduced ready video actions leaving focus on
Done when metadata finished before the dialog acquired window focus. A single
scoped pending target now waits for that window and its layout. New remote
input, dismissal, account changes or backgrounding cancel it. It uses window and
layout callbacks with a bounded timeout, rather than a frame polling loop. A
second case requires a newer user-selected row to retain focus after metadata
arrives.

The original failure and its source/APK identity are retained in the versioned
verification. The final matrix includes all three exit cases, rapid Shorts
reversals, failed next clips and background/foreground cleanup.

## Native coverage and test corrections

The selected matrix contains 257 exact native scenarios, including one focused
visual recording scenario. The matrix includes
all YouTube browse, held-input, account, voice, phone, playlist, history, actions,
watch, panel and queue suites; real-media transport and all four player modes;
On-Demand catalog/stream focus; Live navigation; shared dialogs; and navigation,
startup and fluidity measurements. Forty-one unchanged or duplicate standalone
settings/theme/profile/cache cases retain prior 0.25 evidence and are explicitly
omitted. Five configured-device, private-provider or real-service opt-in methods
are excluded. This is a stated regression scope, not an owner-account or real
IPTV acceptance claim.

- Sidebar tests require readable first-card text, a 12 dp gap, stable measured
  content and no animation layout work. Ten generated rows verify intermediate
  scroll deltas, eight held Down moves, reversal, correct final focus and no
  focus-triggered playback.
  Solid and gradient captures compare the actual rail gaps and rounded pill
  corners with the native backdrop after hardware frame commits; evidence is
  saved before cleanup or assertions can replace the screen.
- Seven unit cases and four native duration-controlled timeline cases verify
  fixed tap times, repeat-independent travel, movement between repeat packets,
  bounded acceleration, cancellation, reversal, external-command precedence and
  keyboard/accessibility fallbacks. Separate generated-media cases verify real
  decoder seeks, play/pause intent, release, focus loss, auto-hide and resume.
- Shorts observers are installed before input, record actual drawable interior
  frames and verify both directions, correct committed identity, an idle/detached
  old player, one attached player and normalized final bounds. Screenshot
  timestamps disclose whether the outgoing view remained after capture.
- Loading component tests verify delayed initial/stall indicators, no brief
  rebuffer flash, cancellation and no text card. Integrated media cases require
  the correct attachment's rendered frame. Deterministic real-network stall
  acceptance is not implied by the component test.

Several old test assumptions needed correction for the new gesture contract.
Android [Instrumentation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java)'s `sendKeyDownUpSync` constructs two zero-time events;
`sendKeySync` fills their times separately. The updated tests send a paired
remote gesture explicitly. Planned release times remain fixed when test work
or software rendering runs late. The former per-repeat jump assertions now use
the continuous-time distances; they retain real media, release and pause checks.

One watch test waited for an enabled Next button after its HUD had hidden.
Failure captures showed uninterrupted video and native-player focus. The test
now requires model Next readiness, reveals the HUD and checks a shown/enabled
button. Queue input waits for the dialog and panel to return actual window/player
focus. These are test readiness corrections; no speculative panel or Next
product change is claimed. Initial failures and their passing reruns remain
recorded rather than being counted as successful original jobs.

Guest Home tests require the actual server labels, order and continuation, no
topic fallback or local-history seed, no authentication on anonymous pages and
no focus-triggered player requests. JVM cases cover the real bootstrap context,
visitor headers/rotation and the memory-only allowlisted cookie budget. A fixture
continuation initially sat outside its real section-list container; it was moved
into the upstream container without widening the parser. Subscription pagination
uses real Down presses after the first adapter and native focus have committed.
Home may prefetch its second page during layout. The revised guest checks require
exact server prefixes and actual anonymous continuation requests, rather than a
transient first-page token or a page-size snapshot. The initial player-metadata
network check also distinguishes that specific Home continuation from action
requests. It still rejects unrelated, authenticated or duplicate added calls.

Two other native setup corrections keep the customer's actual input path:
the created-playlist case waits for the committed collection and moves Right
through its real cards before OK; the low-memory Shorts case establishes remote
mode with a harmless Up at the first clip, then measures exactly one Down.
The original Shorts trace received only Up while Android left synthetic touch
mode, so it did not establish an app-level queue failure. The corrected test
requires the measured native Down and Up as well as exact clip, decoder and Back
behavior.

An early gradient check captured a black pixel after two animation callbacks.
Its assertion-time screenshot was not retained, so the precise cause remains
unproven. With the same production rail, the corrected check waits for submitted
hardware frames and retains the actual screenshot before asserting. Both solid
and gradient pixels then match the native backdrop exactly; an independent
reviewer also checked the collapsed and expanded captures. This is superseded
capture evidence, not a claimed production gradient correction.

## Resource and reference boundaries

Playback retains native SurfaceView and one ExoPlayer decoder. A Shorts move
releases the old decoder before starting the new one, briefly retaining only
one outgoing surface. No transition bitmap, PixelCopy, browser runtime, media
prefetch player, new dependency or enlarged encoded buffer is introduced.
Artwork continues to use the pixel-accounted 8/16 MiB cache.

Worker screenshots can establish AOSP retained-picture behavior and native
geometry; software-rendered timings do not establish physical TCL smoothness or
universal OEM buffer retention. Generated Shorts share media, so logical clip
identity is verified through committed watches/requests rather than a unique
picture watermark for each ID. TizenTubeCobalt delegates its base player to
YouTube TV; exact live upstream loading and Shorts timing parity remains
unverified. See the [implementation review](youtube-motion-0.26.md).

The navigation measurement reaches all seven intended destinations with no
focus-triggered portal-link requests. Artwork remains within its 16 MiB normal
cache limit. The broader QA process grows from about 165 to 190 MiB PSS during
that journey without forced GC; these are whole-process worker samples, not a
cache size or isolated retained-growth baseline. Worker UI frames do exceed
their deadlines. The verification retains those measurements and does not claim
zero jank or physical-TV frame rates from software rendering.

The [release verification](../releases/0.26.0/verification.json) records exact
results, APK signatures, source hashes, job identities, exclusions and deployment
scope. Raw reports, screenshots and source archives remain private under
`.tools/youtube026/` and `artifacts/remote/`.
Complete unchanged passing classes can be selected from a closed job whose
other, now-corrected test classes failed. That job retains its failed status and
unselected failures in the report. Selected cases must all pass with matching
current app, class and test-base source; the changed classes are rerun separately.
This avoids rerunning unrelated successes while preserving every failure and
requiring every selected current scenario to pass once.
