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

The signed-out Home preserves YouTube's sign-in/search empty state rather than
inserting unrelated trending videos. Personalized recommendations require
successful user approval. YouTube watch progress is local and separate from
movie/show progress; this feature does not claim to synchronize YouTube watch
history or implement subscriptions, posting comments, likes or casting.

While a YouTube video plays, Related and Comments open a compact native sheet.
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
The 0.16.0 comments/related/replay additions are being validated off the physical
TV. Do not install or test that candidate on the TCL until the user authorizes it.

YouTube's TV endpoints and response formats can change. The client exposes
recoverable failures; the pinned extractor can be updated independently of UI.
