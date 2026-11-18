# YouTube TV parity review, 0.23.0

The three-agent review compared native browsing, recommendation data and player
behavior with the saved TizenTube Cobalt APK/source investigation and real TV
response shapes. Cross-review then followed ordinary viewer journeys and failure
paths. TizenTube loads YouTube's TV application; 777tv uses native Android views
and ExoPlayer. These architectures do not establish identical screens, feeds or
account behavior. The previous coverage remains documented in
[the 0.21 review](youtube-parity-0.21.md).

## Changes in this pass

| Viewer task | Result |
| --- | --- |
| Type with the remote | Real YouTube TV query completions beside the keyboard, debounced and cancellable. Six reused rows retain the selected query through late updates; a removed selection returns to the keyboard. Recent queries remain a local fallback. |
| Find something while signed out | Actual public Home still comes first. Its request and the subsequent public-topic group each have a five-second deadline. Finished topics survive another topic stalling; unfinished calls cancel. Account Home keeps its server-provided order. |
| Recognize watched videos | A static thumbnail progress line uses an exact supplied resume position or the server's watched percentage. Percentages never become synthetic playback seek positions. |
| Move around feeds | Horizontal shelf ends and actual grid bottom edges retain focus. A partial final grid row remains reachable. Submitting a suggestion waits for the current shelf to bind, avoiding focus on a retiring row with reused video IDs. Full videos still precede the canonical Shorts row. |
| Return to the previous video | A direct Previous control appears when the existing playback trail has a previous item. Disabled transport actions are skipped by focus navigation. |
| Jump to a chapter | Real chapter titles/timestamps from the shared watch-next response enable a compact Chapters action and current chapter label. Selection preserves pause and the decoder. No markers means no chapter control. Phone-owned playback can read chapters without enabling a competing local queue. |
| Read comments and replies | Server comment labels/counts remain visible; Right opens an available reply thread and Left returns to its parent comment. Video/thread context and positions stay visible and retained. |
| Reach Related | Down opens Related consistently from the transport actions, including Next, quality and Chapters. |
| Play a rejected media URL | HTTP 403/404/410 gets one full-extractor source recovery, preserving position and play intent. A second failure offers Retry. The recovered source survives a background/foreground cycle. |

Optional replay metadata and the next Short's single URL warmup now begin after
a rendered video frame. Chapters share the existing bounded watch-next metadata;
they add no playback-start request. Completions use a separate four-second,
64 KiB public request with shared HTTP connections and no account bearer. Native
recycled rows, artwork budgets, media buffer targets and the single decoder remain
in place. No browser runtime, alternate engine or new dependency was added.

## Remaining differences

This is improved everyday YouTube TV coverage, not a claim of 1:1 equivalence.
The saved TV Home reference includes per-shelf Recommended/Explore reload chips;
these filters are not implemented. Guest topic recommendations are not Google's
cookie-based guest personalization. Signed-in feed content and ranking depend on
Google; private/paid playback authorization is not supported by the public media
resolver. Real account approval, phone pairing and Google watch-history sync
require an owner-led acceptance check.

Comments remain read-only. Multiple-account switching, background/PiP, certified
Chromecast discovery and outbound casting remain absent. The upstream
[TizenTube features](https://github.com/reisxd/TizenTube#-features) also include
SponsorBlock, DeArrow, PiP and playback-speed extensions; their complete extension
set is not reproduced. 777tv keeps its own themes and typography, including the
black/violet On-Demand appearance, rather than using the TV website's renderer.

The final-source test results, source checksums, APK identity and any physical
device measurements are recorded separately in the release verification.
