# TizenTube investigation and the native YouTube feature

Inspected before implementation: the official Android TizenTube Cobalt v2.0.3
ARM APK from [reisxd/TizenTubeCobalt](https://github.com/reisxd/TizenTubeCobalt/releases/tag/v2.0.3).

- APK: `cobalt-arm.apk`, 80,570,825 bytes.
- SHA-256: `72a3527f520db3a1b414916ad96481244e451c41e272ae33344ae5e79abb37dd`.
  Matched GitHub's release asset digest before analysis.
- Package `io.gh.reisxd.tizentube.cobalt`, version code 203, min SDK 24.
- Static APK inspection: decoded manifest, archive inventory and JADX decompilation
  of `dev.cobalt.app.MainActivity`, cross-checked against published source.
- The activity delegates to Cobalt/Starboard. Manifest `cobalt.APP_URL` loads
  `https://www.youtube.com/tv?additionalDataUrl=http://localhost:8012/apps/YouTube`.
- The ARM APK includes a 60,843,580-byte `libchrobalt.so`, a 1,198,464-byte
  crash handler and three DEX files. This is a complete browser/runtime, not a
  reusable library for native YouTube cards or an independent account service.
- The [original TizenTube userscript](https://github.com/reisxd/TizenTube/blob/main/mods/userScript.js)
  imports page patches, styling and playback enhancements. Account/feed behavior
  belongs to YouTube's TV application.

The local APK and decompiled references stay in ignored `imports/` and
`analysis/`. They are not shipped or committed as 777tv source.

## Implementation

777tv adds a native YouTube tab beside On-Demand. Native Android input and
recycled cards provide Home, search and Shorts; the existing Media3 ExoPlayer
and compact controls play videos. No TizenTube APK, Cobalt/Chromium runtime,
website UI, WebView or second player is bundled.

A small Kotlin client reads YouTube's current TV configuration and published
JSON responses. It supports modern TV lockups, older renderer shapes, ordinary
feed continuations and the Shorts reel/sequence protocol. Shorts load metadata
in bounded batches with at most three requests concurrently. Feed state retains
at most three pages, 120 cards per page; decoded responses are discarded.

Public media resolution uses the maintained NewPipeExtractor v0.27.1 library,
initialized only on explicit playback, on one cancellable worker. It resolves
HLS or separate video/audio for ExoPlayer, with a progressive fallback. This
keeps media signature handling out of the screen code. See third-party notices
for its license and corresponding source. Signed-in restricted or private video
playback is not claimed: public media resolution is separate from TV account
access. Videos requiring unsupported authorization show an error and Retry.

Google sign-in follows YouTube TV device pairing: the user completes Google's
approval on another device using the returned URL and case-sensitive code.
777tv never asks for a Google password. It dynamically reads the TV client's
configuration rather than embedding account credentials. Pairing handles pending,
slow-down, decline and expiry; leaving the sheet cancels requests. Access/refresh
tokens use a separate Android Keystore AES-GCM store, excluded from backup.
Refreshing is serialized; revoked sessions require sign-in again. Sign-out clears
personal recommendation pages without changing on-demand add-ons or IPTV data.

Signed-out Home uses public TV Music, Gaming and News feeds, interleaved into
Discover and separate topic rows. When a locally recorded video has been watched
for at least 30 seconds, public watch-next also supplies a related-video row.
Account-personalized recommendations require successful Google approval. YouTube watch progress is local and separate from
movie/show progress; this feature does not claim to synchronize YouTube watch
history or implement subscription changes, posting comments, likes or casting.

While a YouTube video plays, Related opens a horizontal row below the video.
Comments open a full-height panel on the right, with the same active video
fitted into the remaining space. Switching panels preserves the player and
decoder; closing restores the full video surface.
Down from the hidden controls or bottom transport buttons opens Related (Shorts
retain Up/Down clip navigation when their controls are hidden). Selecting a
related video replaces the current player route. Back dismisses the sheet before
leaving the video. Comments are read-only: TV watch-next supplies the comments
panel cursor, and subsequent pages/replies use their separate continuations.
Only opening Comments loads the comment text. Lists use recycled views, keep at
most 120 comments, and release artwork when the sheet closes. Requests cancel
when leaving the player; optional metadata failures never stop playback.

The thin seek line has a translucent replay-interest graph when YouTube supplies
one. WEB watch-next supplies current `MARKER_TYPE_HEATMAP` entity mutations;
older `heatMarkerRenderer` responses are also understood. These are replay
intensities, not personal viewing history or exact viewer counts. The public WEB
request never receives the TV account bearer. Missing data leaves the graph
hidden. A cached Canvas path draws at most 256 time-aligned samples, using the
existing controls' layout and auto-hide timer, without an additional focus target
or a playback rendering effect.

## Validation scope

Protocol tests cover parsing, promotion/duplicate filtering, bounded responses,
configuration origins, authentication persistence/renewal/revocation, pairing
responses, cancellation and authorization stripping on redirects. Generated TV
fixtures cover native search/focus, explicit playback, portrait Shorts, return
navigation, account pairing, cancellation, sign-out, failures and section changes.
Watch-panel fixtures also cover reply versus page cursors, optional graph data,
thin controls/auto-hide, remote access to both actions, related-video replacement,
failed metadata/retry and closing a request in progress.

The opt-in live probe checks actual search, Shorts, code issuance, media access,
related videos, comments and replay markers. Issuing a device code is not evidence that a real user completed Google
approval. Account completion is tested against generated fixtures; an actual
account needs its owner to approve the code shown on the TV.

The base 0.15.0 candidate passed a focused physical TCL check before the user's
subsequent instruction to stop TV testing: native video playback and seek,
portrait Shorts, and code issuance/cancellation. No real account was approved.
The 0.16.0 and 0.16.1 candidates were held off the physical TV at the user’s
request. Het subsequently authorized installing 0.17.0 when completed; it was
installed in place and a previously started live probe completed. The latest
instruction holds physical TV testing again. No further TV tests or installation
should run without new authorization. The release verification records the
installation and existing local test log; no report was pulled after the hold.

YouTube's TV endpoints and response formats can change. The client exposes
recoverable failures; the pinned extractor can be updated independently of UI.


## TV layout (0.17.0)

The visual reference is YouTube’s TV application, which the inspected TizenTube
APK loads. The official [YouTube for Android TV listing](https://play.google.com/store/apps/details?id=com.google.android.youtube.tv)
shows its horizontal Home shelves, an expanding icon rail, search field above
an on-screen keyboard with recent searches, and portrait Shorts. 777tv follows
those arrangements while keeping its own typography, rounded controls and
neutral colors. There is no oversized promotional hero. The global 777tv tabs
remain available above the YouTube section.

Home keeps server-provided shelf names and order. A bounded parser retains at
most ten shelves and 120 video references, discarding the JSON response. A row’s
horizontal cursor never becomes the outer Home cursor. Nested RecyclerViews
share a small card pool; only visible shelves own views. Focus restoration keeps
the shelf and selected video across playback. The rail expands over the content
without resizing or shifting it. Focus outlines enclose thumbnails only.

The remote keyboard is one native Canvas view, with letters, numbers, punctuation,
space, delete, clear and search. Its current key has a spoken accessibility label;
the system keyboard remains available for Unicode entry and alternative input.
Delete handles Unicode code points. Explicit search keeps five recent queries
in memory for this app session, cleared by sign-out. Clearing search leaves the
search screen open. Focusing results collapses the keyboard for a full-height
browsing area; Up from the first row restores it. Search still debounces requests
and cancels stale responses.

Artwork is decoded for its displayed frame with a pixel-accounted 8 MiB budget
(4 MiB on low-memory devices). Replacing an image in an already measured, fixed
frame invalidates its pixels without remeasuring the browsing hierarchy. Normal
layout requests remain enabled for parent resizing and wrap-content artwork.


## Guest recommendations and account feeds (0.18.0)

The live anonymous TV `default`/`FEwhat_to_watch` feed currently returns a sign-in
nudge. Public `FEtopics_music`, `FEtopics_gaming` and `FEtopics_news` return video
cards without an account or cookie jar. The native client requests these with
at most two concurrent calls and no account bearer, discards response trees,
and retains bounded card metadata. This follows public TV browse routes also
used by [SmartTube’s media service](https://github.com/yuliskov/MediaServiceCore/blob/master/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/browse/v2/BrowseApiHelper.kt).
A live Kotlin check returned 43 videos across Discover and three topic shelves.
No Google account was created or authorized by that check.

The current TV hero cards put titles, channel metadata, artwork and duration
inside their thumbnail headers. The parser now handles these alongside legacy
and modern lockups, still excluding playlists, channels, menus and promotions.
Signed-in Home retains its personalized response instead of mixing public
fallback content into the account feed.

Subscriptions use `FEsubscriptions`. Library has History, Watch Later and Liked
videos. Signed-in views use their account feed endpoints and continuations;
signed-out History reads the existing maximum of 50 local progress records.
Account-only pages offer a direct sign-in action and make no anonymous private
feed requests. Sign-out clears personal page caches; Clear watch history also
invalidates local history and its suggestion seeds. Actual private feed contents
still require the account owner to complete Google’s approval; generated fixtures
cover those account transitions and pages.

## Player presentations (0.18.0)

- Shorts retain the portrait picture even when movie sizing is set to Zoom.
  Their controls show title/creator, a read-only progress line and compact actions
  beside the picture. Center toggles playback; Up/Down changes clips even with
  controls visible. Clips loop. A cancellable warmup retains only the next clip’s
  resolved URL/metadata for up to one minute, with no second decoder or buffer.
  Leaving playback, memory pressure or another explicit selection clears it.
- Regular YouTube keeps the thin seek controls, related videos, comments and
  optional replay graph. A separate quality action selects Auto or actual
  supported ExoPlayer video tracks. Picture remains a separate control.
- Movies and episodes retain the thin unboxed transport, accelerated seeking,
  episode identity and audio/subtitle selection. Source selection determines
  their movie-file quality; YouTube-only actions stay out of this presentation.
- Live TV has a brief channel/now-next overlay and no seek, pause or skip controls.
  Schedule metadata is optional, loads only for an applicable visible overlay,
  and never blocks playback. Back leaves fullscreen without an extra HUD-dismiss
  step. Picture controls remain available through the remote menu.

Regular movie buffering targets 24 MiB (16 MiB on low-memory devices). Shorts use
8/6 MiB and a 500 ms startup threshold. Artwork keeps the same pixel-accounted
8/4 MiB cache. No additional video engine, browser runtime or native library is
included, and neutral playback retains direct hardware decoding.

## Playback startup (0.18.1)

The normal public-video path calls the pinned, unmodified NewPipe library’s
`YoutubeStreamHelper.getVisionOsPlayerResponse` and accepts a matching, playable
HLS response. This needs the anonymous visitor and player requests, omitting the
full extractor’s WEB metadata and watch-next work. Captions come from the same
player response; menus, comments and replay information keep loading separately.
Unsupported or failed responses retain the full extractor fallback. Cancellation
still interrupts its active socket on the single resolver worker.

The model keeps at most three already-selected URLs for 60 seconds. Retry bypasses
these entries; sign-out, history clearing and memory pressure release them. A
next-Short request already resolving is adopted on explicit selection; a warmup
still waiting in its 600 ms delay is cancelled so it cannot delay Play. This retains
one speculative URL, without another media buffer or decoder and without resolving
videos merely because a card gains focus.

Media3’s OkHttp data source shares DNS/TLS/HTTP2 connection reuse with the API
client. Streaming has connect/read timeouts without a total-video duration limit;
private headers are removed on redirects to another origin. Source preparation
starts before constructing the transport and media session, overlapping their
main-thread work with network loading. Initial playback thresholds are 250 ms for
Shorts, 350 ms for Live TV and 500 ms for regular YouTube/on-demand. Existing steady-state
buffer durations, byte budgets and rebuffer thresholds remain intact.

`YouTubeStartupProbeTest` compares both resolver paths against the same public
videos in alternating order on the Mac. `PlaybackStartupBenchmarkTest` records
explicit Play to the actual rendered first frame and active playback for all four modes using the
same generated bandwidth-limited HLS and delayed Stalker portal on one worker.
These measurements compare 777tv implementations; no matched TizenTube or physical
TCL performance comparison is claimed. The TCL deployment/testing hold remains active.
