# 777tv 0.14.1 — Episode scrolling

Normal episode Left/Right uses RecyclerView's native focus and scrolling, as
streams already do. The previous per-key stopScroll and deferred idle/visibility
wait dropped repeats during fast input. Those checks now apply only to entry,
Back and picker restoration. Fixed selector width and height let season-label
updates redraw without remeasuring the row during a scroll.

The same fixture and benchmark on the same worker delivered 18 keys per profile.
Episode progress improved from 8 rapid/6 held moves to 18 for both; streams stayed
at 18. Input IPC affects the recorded timing, so progress and matching delivery
counts are the primary comparison. Focus stayed in the row and no additional
stream/metadata requests occurred. The TCL separately moved from S1E1 to S2E12
with 18 rapid inputs; 84 sampled UI frames had no jank and a 9ms p95 duration.

Checks and artifacts are recorded in `artifacts/verification-0.14.1.json`.

## 0.14.0 name and startup preferences

The launcher label, header, About screen, icon and TV banner use 777tv. The
application ID stays the same for an in-place upgrade. Provider storage has no
factory profile or provisioning path. The saved profile on the TCL was explicitly
deleted at the user's request; future installs begin with an empty profile list.

On-Demand startup initially focuses the search field without opening its
keyboard. Leaving the field hides any existing keyboard, preventing a leftover
IME from intercepting OK on a movie poster. UI checks wait for scroll/layout and
IME state to settle before pressing OK.

Checks and artifacts are recorded in `artifacts/verification-0.14.0.json`.

## 0.13.2 configurable startup screen

On-Demand is the startup default. Settings → Appearance → Startup screen offers
On-Demand and Live TV; changing it stays in Settings and takes effect next launch.
The new saved key distinguishes these explicit choices from the old persisted
Live TV default, so upgrading also adopts On-Demand. Other preferences, profile
selection and history are unchanged. Explicit Live TV startup still opens the
saved default profile, with the profile-list fallback when none is available.

The startup checks are included in `artifacts/verification-0.14.0.json`.

## 0.13.1 continuous episode browsing

Episode navigation uses one virtualized chronological row across all available
seasons. Left/Right crosses season boundaries without replacing the row or
loading streams. The fixed-width season selector follows focus, including
Specials and nonconsecutive season numbers. OK retains explicit episode
selection; show details and sources continue to describe the selected episode.
The season picker scrolls to its chosen season, and Down/Right enters that
season's episode. Saved focus, browsed season and scroll position survive
recreation. The top Search tab is renamed On-Demand, including its accessible
label and directional return from the search field.

Patch checks and artifacts are recorded in `artifacts/verification-0.13.1.json`.

## 0.13.0 playback controls and unified series browsing

ExoPlayer is the only player. Its selector, stored engine preference, mpv adapter,
JNI wrapper, native playback libraries and build downloads are removed. Legacy
engine keys are ignored; existing provider credentials, default profile, history
and every remaining preference retain their existing storage. The requested
STBEmu migration was canceled before any profile was opened or copied; the
existing Fleet IPTV profile remains unchanged.

The 48dp playback overlay has no filled card or rounded panel. A subtle bottom
gradient keeps its thin line, compact glyphs and time labels readable; remote
focus and 44dp targets remain. Immediate seeking, paused intent, five-second
inactivity hiding, audio/subtitle choices and caption clearance are retained.
Live TV continues to have no transport timeline. The sliders button opens
Picture controls directly, without a speed/fit menu. Timeline holds accelerate
through 1×, 2×, 4× and 8× steps at 0.5, 1.25 and 2.5 seconds while release, reversal and focus loss reset
the hold. Source limits clamp the cursor, and single skip taps keep their
configured interval. Poster focus frames only the artwork; titles stay below
the border, including after recycled-view reuse.

Shared button and bar corners increase from 12dp to 16dp. Compact transport
focus highlights increase from 8dp to 12dp, without adding a background panel.

Picture controls expose brightness, contrast, saturation and Reset for the
current SDR playback. Neutral settings never call setVideoEffects, preserving
ExoPlayer's direct MediaCodec Surface output. The first non-neutral adjustment
switches to an optional matrix pipeline while preserving position, play intent,
speed and track selections. Subsequent changes publish complete immutable
matrices to the render thread. Playing changes do not retain extra full-resolution video frames. Paused
changes coalesce for 150ms and refresh between exact positions bounded within
one millisecond of the paused anchor. A fresh matrix registration invalidates
the paused input graph before that refresh; playing updates keep the same matrix.
Resume/release cancels pending refreshes, and a newer user seek supersedes them. Reset recreates the direct path instead of retaining an empty
GPU pipeline. HDR/protected formats retain normal hardware rendering; unsupported
adjustments report their reason and restore the actual applied slider values.
A processing failure returns once to direct playback. Picture controls are also
available from Live Options while a preview plays, and the fullscreen Menu key.

The first device test exposed a severe cost in Media3's optional replayable
frame cache on the TCL's 4K stream. That custom renderer/cache bridge is removed;
the app uses the stock ExoPlayer renderer and optional mutable SDR matrix only.
Generated pixel integration checks cover actual grayscale/brighter paused frames,
reset to direct output and retained pause intent/position. Physical TCL results
are recorded separately from software-rendered emulator results.

Series browsing now uses a fixed show poster/title/selected-episode overview
above a dynamic season/episode strip and fixed-size stream cards. Explicit OK
selection updates inline streams without another page or metadata request.
Eight bounded show snapshots retain only each show's current episode links;
links revalidate after five minutes and metadata after fifteen minutes on entry.
Immediate Player Back retains source identity and focus; renewed links use
semantic/index fallback, and refresh completion respects newer user input.

Search uses the verified official Cinemeta manifest/catalog resources directly.
Its home rows follow declared catalogs and expose Type/Catalog plus advertised
Genre or Year options. Typed results stay grouped by catalog/type; discover
filters hide while typing, preserving query text and the previous discovery
selection. Required opaque history catalogs are not requested without their
inputs. Full configured URLs stay distinct and encrypted; view/state keys are
opaque hashes. Continue/Recent/My list remain, and the large hero stays removed.

Final checks and artifacts are recorded in `artifacts/verification-0.13.0.json`.

---

## Historical 0.11.1 verification

## 0.11.1 display timing and physical TCL comparison

The Android display mode's refresh rate now initializes mpv's
`display-fps-override`, matching the pinned upstream mpv-android setup. A
display listener updates it through the native queue and is removed on release.
The TCL now reports approximately 60 Hz to mpv. Content frame-rate requests
remain seamless-only hints; video synchronization defaults remain unchanged.

The same working 41.9 GB 3840×2160 HEVC Cars source was tested with each engine.
mpv used non-copy MediaCodec hardware decoding, reported zero decoder drops and
about eight seconds buffered without cache starvation, but dropped 114 output
frames over roughly fifteen seconds. Supplying display timing did not resolve
this GPU-output stutter. ExoPlayer used `c2.mtk.hevc.decoder` on the same edition;
the user's ExoPlayer selection is restored. A separate 52.6 GB source returned
HTTP 502 before playback, independently confirmed with a bounded range request.
That source failure is distinct from the mpv rendering issue. Resolved media
URLs, account settings and physical-TV captures remain private on the Mac.

The final exact production source passed 45 local unit tests, Android lint,
the ARM release build and 15 remote native playback scenarios covering both
engines. The preceding display-bridge build passed another 28 browse/player
scenarios. The unchanged UI/add-on/profile baseline passed all 73 scenarios;
this is separate baseline coverage, not a claim that all 73 reran against the
final display-mode getter. Independent customer review found no source blockers
and retains the unresolved physical mpv smoothness limitation. Details, source
fingerprints, installation evidence and checksums are in
`artifacts/verification-0.11.1.json`.

## 0.11.0 customer feedback update

The large recommended-movie banner is removed. Search starts with its input and
ordinary compact shelves; Continue watching, recent titles and My list remain.
Opening a movie loads its horizontal streams beneath the details immediately.
Selecting a series episode still opens its streams directly. Metadata and movie
stream requests have independent loading/error state, cancellation and retry, so
a stream outage leaves the movie details useful and a late response cannot
replace another episode's choices.

The player strip is now **56dp** high, with a two-dp seek line and a focused
thumb instead of a large outlined timeline. Directions and skip buttons seek
immediately; there is no pending OK confirmation. Held inputs coalesce within
150ms. The five-second hide timer measures actual interaction and respects play
intent, allowing controls to hide while buffering without reopening on each
state change. Paused/finished playback retains its controls. Both engines use
the same controls and caption placement.

Hardware decoding remains the default request. The user reported stutter in
mpv while ExoPlayer was smooth; their selected engine is preserved. mpv keeps
the existing 16/24 MiB live and ordinary-movie compressed forward caps, with a
bounded **48 MiB** cap for explicit 4K/8K/UHD or sources at least 16 GB. Its
twelve-second target avoids filling the cap for low-bitrate files. A content
frame-rate hint follows playback speed and permits only seamless refresh changes;
it is advisory, not proof that the TV switched modes. Obsolete native seeks and
stale/not-yet-applied poll results are rejected by generation. Repeated skips use
fast keyframes, then one exact final settle without changing a newer pause intent.
Closing a player cancels that settle before the next attachment.

Credential-free diagnostics identify engine, opaque source identity, decoder,
resolution, frame rate, buffered duration and drops. They never include titles,
stream URLs or headers. TCL logs recorded MediaTek HEVC hardware decoding at
3840×2160; software-rendered x86 workers may legitimately use software fallback.
Fixture tests record the actual path and check native buffer limits, accepted
24/36 fps hints at 1×/1.5×, real seek positions, held inputs, release/recreation,
and focused/buffering auto-hide. Emulator results do not establish physical
TCL 4K smoothness or equivalence with an arbitrary production stream.

The 0.11.0 baseline passed 45 unit tests, lint and all 73 Android TV scenarios.
Those baseline receipts and the final 0.11.1 delta checks are recorded together
in `artifacts/verification-0.11.1.json`.

## Previous 0.10.0 verification record

Scope: Fleet on-demand playback and Stalker-only Live TV, preserving the iOS
Fleet theme and the two main sections. IPTV profiles support add/edit/default/delete.

## Current 0.10.0 review

The current build uses direct Stremio add-ons for on-demand content. Cinemeta
provides the default catalog/search/details and the supplied GDrive manifest
provides stream links. Earlier Frame session, conversion and fallback behavior
described in the historical sections below has been removed.

| Customer friction or correctness finding | Current behavior |
| --- | --- |
| Scrolling channels immediately changed the playing preview. | Focus only browses. First OK tunes inline; the second OK on that playing channel expands the same player. Live TV has no seek bar. |
| Search entry was hard to find or focus returned to a poster. | A dedicated native input remains visible, supports the TV keyboard and explicit Search/Enter, and receives focus from the Search tab or Up from the first content row. Pending focus cannot override newer input. |
| The on-demand panel was too tall and source cards used too much space. | A single slim bottom strip holds transport, scrubber and track/settings controls. Movie/show/episode context sits above a compact horizontal stream row. |
| Old Original/Compatible labels belonged to the Frame app. | Sources come directly from enabled add-ons. Rows show add-on, quality, supplied size and format; long-press shows full details. No conversion modes remain. |
| People need to install and manage other add-ons. | Settings → On demand → Manage add-ons accepts HTTP(S)/Stremio manifest URLs and offers enable, disable, refresh and confirmed removal/reset. GDrive and Cinemeta are installed by default. |
| Several configurations could share one add-on ID/name. | The complete configured manifest URL identifies an installation; safe numbered labels distinguish same-name configurations. URL paths/query strings remain intact and encrypted. |
| Installing one URL while typing another could erase the new draft. | An operation snapshots its submitted URL and clears only that unchanged draft after success. Newer input and its focus survive. |
| Empty results mixed with failed requests looked like a successful empty library. | Empty-plus-failure results show actionable errors. Working partial results stay available. Missing catalog/search/metadata/stream capabilities link directly to Manage add-ons. |
| Headers or distinct header-dependent links could be lost. | Stream identity includes URL and headers. ExoPlayer receives the original request headers; mpv appends each header as a separate option to preserve comma/backslash values. |
| Two Back handlers, delayed focus and accessibility-idle waits made controls unpredictable. | One Back dispatcher cancels scrub, hides controls, then exits. Physical key tests avoid accessibility-idle delays during the updating playback HUD. |
| Series resume could retain the last browsed episode instead of the saved unfinished one. | Fresh title entry chooses the newest valid unfinished episode; ordinary Back restores the explicit episode/season selection. Invalid/completed/disabled resume falls back to the first episode. |
| Paused playback, replay or subtitle controls could lose their intent across lifecycle changes. | Both engines retain paused intent/position, replay after EOF, expose audio/subtitle selection, and move captions above the strip while it is visible. |

The independent customer review is read-only and covers remote focus, unnecessary
actions, empty/error recovery, source context, compact layout, and the same basic
player behavior in both engines. Current test jobs use generated media only;
final job IDs, counts and limits are recorded in `artifacts/verification-0.10.0.json`.

### Current verification

The final remote run passed all **69 Android TV scenarios** with no failures or
skips. The same production source passed **42 unit tests**, Android lint, and the
ARM release build. A separate native header/control run passed both scenarios in
both engines. Generated multi-track media verified actual audio selection,
subtitle Off and cue delivery; screenshots show captions above the short strip
when controls are visible and below when they hide.

The independent customer review passed the horizontal stream chooser, obvious
Search input, distinct add-on configurations/capabilities, compact player,
caption placement, focus indications and recovery actions. Final navigation
checks include returning from add-on management after restoring defaults, fresh
series detail resume versus Continue watching's direct source shortcut, and
preserving the chosen header-dependent source after playback.

An intermediate run exposed missing test synchronization around modal dialogs
and deferred screen renders, a Continue shortcut tested as a normal poster, and
history leaking into a clean-home test. Tests now wait for the actual destination,
scope the intended shelf, use the modal confirmation buttons, and isolate history.
Their exact source/header/focus/resume assertions remain; the full corrected suite
passed. An earlier native header test exposed an invalid libmpv option form and
unsafe cleanup before native initialization. The corrected initialized list-command
path was verified with commas, a trailing backslash and a subsequent header in
actual HTTP requests before installation on the TV.

Both 6,000-channel workloads passed. Scrolling attached seven native row views;
repeated tuning attached six. Process PSS was approximately 119.1 MiB before
and 119.5 MiB after eight mpv channel changes, and 87.3 MiB after repeated UI
navigation. These are software-rendered emulator samples, not a measured speedup
or a physical-TV frame-rate claim. The sample predates only the tested request-header
setup correction; its scrolling/tuning code is unchanged.

The exact GDrive manifest and a Cinemeta search returned HTTP 200. A sample GDrive
source returned a 1,024-byte HTTP 206 range with a Matroska header. That checks the
live HTTP path; native decode scenarios use generated media and do not prove every
production stream or codec. Source archives retain the native libraries' corresponding
source and omit credentials, signing keys and prebuilt binaries.

## Historical findings addressed (0.6.0–0.9.0)

The following sections retain the earlier implementation and measurement record;
their Frame-specific settings and session behavior are not present in 0.10.0.

| Priority | Finding | Result |
| --- | --- | --- |
| High | Fatal playback errors could leave the player/session heartbeat running behind the error overlay. | Failure now stops reads, releases the Fleet lease, stops heartbeats and clears keep-awake. Retry creates a fresh attachment. |
| High | Encrypted provider read failures appeared as an empty list; adding a provider could overwrite unreadable saved credentials. | Live TV reports the failure and saving is blocked until an explicit, confirmed provider-storage reset. |
| Medium | Cancelling an API coroutine did not immediately disconnect its blocking HTTP operation. | Cancellation now disconnects the socket and cancels queued work. Responses are bounded after gzip decompression; redirects are limited and credential headers stay within their origin. |
| Medium | One failed catalog request discarded the other, successful shelf. | Shelves load independently. Working content stays visible, and Retry clears the partial cache and reloads both. |
| Medium | Malformed saved-title/history JSON could crash startup; duplicate external IDs could crash lazy layouts. | Noncritical bad data falls back safely without crashing, and external title/episode/channel keys are deduplicated. |
| Medium | Settings were available only from Search and contained only a server address. | A shared top-bar entry exposes categorized on-demand, Live TV, appearance and storage controls. There remain exactly two main sections. |
| Medium | The native video view could retain focus behind the playback error UI. | It is detached on error; Retry receives focus. Confirmation dialogs initially focus Cancel. |
| Medium | Provider configuration had no edit/test flow or removal confirmation. | Drafts are retained in memory across settings navigation, connection tests ignore display-name-only edits, and removal requires confirmation. |
| Medium | A shared edit draft could be reused by Add provider, potentially updating the old provider. | Add and Edit draft modes are separated; the TV regression checks settings round-trips and switching from Edit to Add. |
| Medium | Large channel lists performed parsing/key hashing during UI work. | Provider decoding runs off the main thread and each channel identity is computed once. |
| Medium | Channel browsing reset its filters when returning from playback. | Group, query and favorite-only state are preserved; favorites use opaque stable keys and survive URL query-token renewal. |
| Medium | There were no bounded live reconnects or configurable stream/playback defaults. | Live retries are capped and can be disabled. Stalker lists refresh and create_link resolves a fresh temporary stream for a reconnect; on-demand conversion fallback remains separately bounded. |
| Medium | Live playback supplied a zero start position, selecting the beginning of a live window. | Live sources now use the player's default live-edge position; a live HLS fixture verifies this separately from on-demand playback. |

| Medium | A stale total_items value caused a complete bulk channel list to be fetched again as hundreds of pages. | Bulk-list size and page capacity distinguish complete responses; short final pages also terminate safely. |
| Medium | Device identity overrides were unavailable. | Basic/advanced profile fields match the reference; optional identities remain empty unless explicitly supplied and all profile data stays encrypted. |
| Medium | Profiles could not be selected as the persistent default. | Default selection survives restart; deleting it selects the next profile or returns to empty setup. |
| Medium | The long identity form needed deliberate keyboard and focus behavior. | Native EditText controls open the keyboard on activation; the activity pans around it and Done dismisses it. Tests cover entering a MAC through the TV keyboard. |

## Settings and customization

- **On demand:** server, refresh, resume, stream mode and size order, conversion
  fallback, playback speed and seek interval. Audio language, subtitle defaults
  and video fit apply to both on-demand and Live TV where tracks are available.
- **Live TV:** named Stalker profiles, default selection, confirmed deletion,
  buffering, reconnect limit and channel order.
  Channel browsing includes search, groups and favorites. Live speed stays at 1×.
- **Appearance:** startup section, poster size, continue watching, recent history,
  and My list visibility, retaining the black/gray/white Fleet iOS presentation.
- **Data & storage:** confirmed history clearing, settings reset and provider
  reset. A settings reset does not remove providers or history.

Preferences save automatically; server edits have an explicit Save action.
Provider URLs, credentials and custom headers are encrypted with Android
Keystore. Corrupt provider data is not silently replaced. Preferences are
excluded from backup/transfer because their encryption key is device-bound.

## Validation

The 0.6.0 baseline results are recorded in `artifacts/verification-0.6.0.json`.
Category visibility validation is recorded separately below.
No TCL connection, installation or testing was performed for 0.6.0 under the
current restriction. The supplied real account was verified using ExoPlayer on
the local emulator during 0.5.0 development; that earlier result does not prove
real-portal mpv compatibility. Current tests use generated H264/AAC loopback
media and Stalker fixtures, so the single-connection account can remain in use.

Coverage includes Stalker required/optional fields, all advanced identity/version
values, MAC cookies and bearer authentication, optional second-step login,
bounded reauthentication, pagination, fresh playback links and exclusion of
portal tokens/cookies from media CDN requests. TV coverage includes encrypted
advanced-field persistence, default-profile persistence across activity restart,
confirmed deletion and default fallback, channel groups/favorites/search,
on-demand playback/resume/settings, live-edge playback, reconnect exhaustion,
error cleanup/focus, partial catalog failure and lifecycle cleanup.

The opt-in provider test uses Android TV Media3 and records real rendered frames,
video dimensions, codec information and selected audio for three sequential
channels. Fixture tests use generated video and no production films.

## Limits

Catch-up, portal VOD and account synchronization remain outside this build.
The new guide displays actual portal schedule data or an explicit unavailable
state. Its fallback response is bounded by the HTTP response limit, parsed off
the UI thread and retained only for a small per-channel cache.
Optional PIN behavior and firmware-specific fields depend on the portal; the
provided account does not require a PIN or username login. Compatibility with
other portals, unavailable language tracks and unsupported codecs is not proved
by one account. The preview uses the existing local development signing key.

## Native UI and memory changes

- Replaced Compose with Android views and RecyclerView; no JavaScript runtime.
- Diffing and channel filtering run in background workers. Stale query work is
  canceled; channel search text and hashes are precomputed once.
- Channel data is reused on return from playback and released when its profile
  is deleted. Adapter view counts stay bounded for the 6,000-channel fixture.
- Display-sized artwork uses a 16 MiB memory cache (8 MiB on low-RAM devices)
  and a 64 MiB disk cache.
  Recycling cancels image work and clears callbacks to retired screens.
- List item animations are disabled, screen restoration is bounded, and media
  sessions/codecs are released when playback stops or leaves the foreground.
- Native profile fields include every editable setting shown in the MySTB
  screenshots, with Portal URL/MAC required and optional identity overrides.

## Three-pane layout and native engine review

- Categories, channel rows and preview/guide remain visible together. Independent
  RecyclerViews bound channel/category view counts. Guide rows accept D-pad
  focus, allowing the schedule to scroll below the preview.
- Focus tuning is debounced; stale guide/filter work is cancelled. Channel/link
  and guide operations are serialized to avoid competing authentication tokens.
- Fullscreen resizes the same video host without detaching its surface. Back retains that player,
  channel selection and scroll/filter state instead of opening another stream.
- Live ExoPlayer controllers are disabled. mpv never inflates live controls;
  both engine interfaces reject seeking and remote rewind/fast-forward/skip.
- Movie controls remain available. The mpv Media3 bridge supports play, pause,
  seeking, playback speed, progress reports and Android media sessions.
- JNI access uses one worker queue because the upstream JNI bridge has one
  process-wide handle. Creation, surface operations, commands and destruction
  are ordered; events return to the main thread only for UI state updates.
- mpv uses the upstream mobile fast profile and MediaCodec with a software
  fallback. HTTPS verification uses Android's trusted CA certificates. Scripts,
  external config, terminal bindings and native OSD are disabled.
- Native compressed buffers are bounded at 16 MiB forward for the default
  live setting, 24 MiB for longer buffering or movies, and zero back for live
  media (4 MiB back for movies); application image caches remain bounded.
- Both engines release sessions/codecs and stop reads on background or failure.
  Native failures have bounded retries, then explicit focused Retry controls.
- The local test harness refreshes its resumed Activity reference so recreation
  does not leave assertions observing a retired Activity. Profile tests wait for
  the matching rendered profile heading before activating channel rows.
- Upstream native libraries are digest-verified; licenses and source provenance
  are packaged separately from private account credentials.

The mpv failed-URL regression initially exposed a native file-descriptor abort
when its built-in web extractor attempted helper subprocesses. Disabling user
scripts alone did not disable those built-ins. Fleet now explicitly disables
ytdl and all unused built-in scripts; the failed-URL, retry-limit and manual
recovery scenarios pass with the updated configuration. It also preserves
manual pause across temporary audio-focus loss, accepts delayed audio focus,
and retries an initially denied focus request when its window gains focus.

Emulator-only frame/memory details and limits are in README.md and the raw
`native06-local` / `native06-local-host` artifacts. No 60-fps or physical-TV
performance guarantee is inferred from these measurements.

## Category visibility (0.7.0)

Categories → Edit opens a native remote-friendly checklist. Checked categories
are visible; Done dismisses the editor and Show all restores every category for
the current profile. Changes save automatically and survive restart. Category
choices belong to each profile, including when two portals use the same names.

Hidden categories are excluded from the sidebar and from All channels, search
and Favorites. Hiding the selected category returns browsing to All channels.
Hiding everything is allowed: All channels and Edit remain accessible, and the
empty state explains that all categories are hidden. Existing playback continues.
New provider categories are visible by default. Hidden names absent from a
refreshed channel list remain available in the editor for restoration. Deleting
a profile or resetting provider storage clears its category choices; resetting
playback/appearance preferences preserves them.

Validation for this change is recorded in `artifacts/verification-0.7.0.json`.
Testing uses generated local Android TV fixtures; the TCL remains untouched.

## Remote development and testing

Build/test compute now runs on the three private `fleet-tv-dev` replicas.
The existing Shellbox app and sessions remain unchanged. Native Node fetch
uploads source and collects checksummed artifacts; signing keys and production
IPTV credentials stay on the Mac. Default commands are in `remote/README.md`.

The remote release build, 35 unit tests, lint, 24 Android TV scenarios and a
separate 6,000-channel scenario passed. Android TV tests ran on two different
KVM workers. Remote software graphics limit frame-time measurements; the large
catalog sample recorded 86.56% janky frames, so these checks establish functional
behavior without establishing physical TV smoothness. Full evidence is in
`artifacts/verification-remote-development.json`.

## Fluidity and memory work (0.8.0)

- A channel row is one canvas-backed native view instead of seven nested views.
  Reused BoringLayout objects handle simple single-line labels; StaticLayout
  handles complex scripts and bidi text. Text shaping runs at bind/size changes.
  Drawing allocates no per-frame text layouts. Focus, selection, accessibility,
  long-press favorites, ellipsis and correctly proportioned logos remain supported.
- Channel rows retain six lightweight cached views for quick reverse scrolling;
  poster grids and horizontal shelves retain two, and the home list one. A first
  candidate shrank every cache and rebuilt StaticLayouts; measurement exposed
  extra churn, so channel caches and reusable simple text layouts were adjusted.
- Channel deduplication/category preparation happens once off main per source
  list. Unfiltered browsing shares the original list; alphabetical order is
  computed lazily and reused. Stable-ID cleanup no longer allocates a full
  catalog-sized hash set on the main thread.
- Channel focus tuning uses one cancellable delayed callback. Category updates
  reuse their backgrounds and update selection without rebinding every category.
- On-demand catalog/search/detail/stream JSON parsing runs off the UI thread.
  Catalog and detailed-title caches are capped at 12 and 32 entries (previously
  24 and 128). Low-memory signals clear inactive lists, repository caches and
  recycled posters while preserving current browsing/playback and preferences.
- History parsing is cached against its stored JSON, so heartbeat updates do
  not deserialize the same history repeatedly. Other Store writers invalidate
  the cache. History and My list retain title summaries rather than complete
  series episode catalogs, matching their persisted format.
- mpv position ticks reuse timeline/video-size/playback-parameter objects.
  Bursts of native state events share a poll. Preview bounds reuse scratch
  objects. The default live demuxer cap is 16 MiB, with 24 MiB retained for
  longer live buffers and movies. Exo Live uses a minimal player layout that
  never inflates the unused seek-controller hierarchy; subtitles remain available.
- Artwork limits are 16 MiB normally and 8 MiB on low-RAM devices. Rebinding
  to an empty image cancels any previous request. Logo callbacks are guarded
  against recycled rows, with aspect ratio preserved.

The controlled before/after samples use the same worker, API 36 x86_64 image,
two emulator cores, SwiftShader and generated 6,000-channel catalog. UI-only
scrolling keeps focus outside the list so video decoding cannot contaminate it.
Both samples execute 180 identical scroll steps and six Search/Live round trips.

| Measurement | 0.7.0 baseline | 0.8.0 candidate |
| --- | ---: | ---: |
| Views in six attached channel rows | 42 | 6 |
| Managed bytes allocated during scrolling | 720,992 | 622,688 |
| UI draw-recording p95 | 0.313 ms | 0.168 ms |
| Layout/measure p95 | 0.103 ms | 0.110 ms |
| Total frame p95 | 209.905 ms | 209.604 ms |
| App process CPU during scrolling | 1,276 ms | 1,258 ms |
| PSS after six navigation round trips | 86.0 MiB | 84.5 MiB |
| PSS after repeated mpv tuning | 117.0 MiB | 117.5 MiB |

These are single matched samples, not statistically established speedups.
Overall frame time remains limited by software graphics. Playback PSS did not
decrease materially; startup PSS varied with garbage collection. Cache limits
and retained-data reductions address larger real catalogs, but this fixture
does not establish a large overall RAM reduction or physical-TV smoothness.
Raw samples, intermediate-candidate results and final checks are preserved in
`artifacts/verification-0.8.0.json`. The physical TCL and production IPTV account
remain untouched.

## Professional UI and navigation update (0.9.0)

Three team reviews covered Live TV, browsing/profile management, and settings/forms.
The integration keeps the native low-allocation renderer and the two main tabs.

| Previous friction | Resulting behavior |
| --- | --- |
| Live toolbar exposed Favorites, Refresh, Profiles and category Edit together. | One Options entry contains secondary operations; Favorites belongs to the category sidebar. |
| Activating a category left focus in the sidebar, requiring another move. | One OK enters the first filtered channel after its row is laid out. Re-selecting Favorites keeps it selected. |
| Empty lists, missing guide data and loading looked similar. | Local channel loading/error/empty states have specific messages and only relevant recovery actions; empty preview cannot receive focus. |
| Category labels were repeated on every row inside that category. | Rows omit redundant group subtitles in category views; the guide uses quiet text and separators. |
| Main-tab presses could reset a query or recreate playback. | Active-root re-selection preserves state/player; root Live Back no longer opens an unrelated profile screen. |
| Back could focus a recycled row representing a different channel. | Focus is restored by stable item/context identity and scroll position, including after activity recreation. Fresh input cancels deferred focus. |
| A nested menu opened category checkboxes in touch mode, consuming the first OK merely to establish focus. | Native sheets and category choices establish keyboard-ready focus on opening. The first OK activates the selected item. |
| Search model text could clear while the visible field retained the old query. | Model-to-field synchronization is guarded to preserve typing/selection and avoid duplicate search requests. Empty results are cached too. |
| Home repeated featured/recent items and nested a button inside an actionable card. | Featured is one actionable card; duplicate featured/continue items are omitted from redundant shelves. |
| A profile row exposed several equally prominent buttons. | One open target plus an options menu; first-profile Save opens its channels directly. |
| Setting values required cycling through every intermediate choice. | Native single-choice dialogs support direct selection and Cancel; toggles take one press. |
| Shared playback settings repeated across pages, and raw connection configuration dominated entry. | Playback is the initial settings page; On demand, Live TV, Appearance and Storage are distinct rail categories. |
| Advanced fields pushed Save/Cancel below the viewport. | Profile actions stay in a fixed header; required fields lead, advanced groups are lazy and optional, and validation focuses the relevant input. |
| Configuration error banners offered an unrelated Retry. | Settings/forms own inline feedback; editing clears stale validation, and Save server appears only for unsaved changes. |
| Profile edits/deletes left stale navigation destinations or drafts. | History updates to the edited provider, deleted credentials leave the draft, and Back falls back to valid profile management. Playback reset preserves the default profile. |
| A single season added an unnecessary button; filtered streams could appear as a dead end. | Single-season heading is not a focus stop; multiple seasons use one picker and filtered sources offer a local Show all action. |

Private profile fields disable platform saved view-state/autofill; only form
geometry/focus identifiers enter activity-state parcels. Provider values remain
in the existing encrypted store or the in-memory draft. Dialog focus restoration
validates the original item and parent before focusing a recycled control.

TV coverage expands to 48 scenarios: the existing 28 plus settings/forms (4),
Live navigation (5), browse navigation (6) and navigation/data consistency (5).
The suite exercises real D-pad actions, checkbox/picker cancellation, offscreen
restoration, default-profile changes, first-profile onboarding, inline validation,
empty-search caching and stream-filter recovery. The separate 6,000-channel
workloads check bounded views, rendering and repeated mpv tuning.

The harness now scopes search-result actions to the visible grid, waits for
native dialog callbacks, and distinguishes an existing inline preview from a
new fullscreen attachment. Timeout diagnostics run before cleanup and capture
fixture-only UI state, so later profile cleanup cannot mask the failed screen.
Full results and artifact hashes are recorded in `artifacts/verification-0.9.0.json`.
Functional emulator results do not establish physical TV smoothness or codec
compatibility for an untested production stream. No TCL or production IPTV
connection is part of this verification.

## Native YouTube candidate (0.16.0)

The official TizenTube Cobalt APK was inspected before implementing the native
feature; findings and source links are in `docs/tizentube-investigation.md`.
777tv uses recycled native cards and its existing ExoPlayer instead of shipping
the Cobalt runtime. The candidate has no native libraries or WebViews. Public
media signature resolution uses the pinned NewPipeExtractor dependency, created
only for explicit playback.

| Viewer action | Reviewed behavior |
| --- | --- |
| Move through Home, search or Shorts. | Focus frames the thumbnail, leaving text outside. Focusing never resolves or starts a stream. Search is native and debounced. |
| Sign in. | A native code/QR sheet uses Google's returned URL. Pending, cancel, decline, expiry and sign-out are recoverable; account tokens are encrypted separately from add-ons and IPTV. |
| Press Down while watching an ordinary video. | A compact native related-video sheet opens over the same player. Shorts retain hidden-controls Up/Down navigation. |
| Choose a related video. | The player route is replaced, so Back does not step through an accumulating chain of old players. |
| Open Comments. | Text loads on demand into recycled rows. Page cursors and reply cursors stay separate. Reading/replies keep playback running. Back returns from replies; Close dismisses the entire sheet. |
| Reveal or seek with the playback controls. | The existing slim, unboxed strip and hold acceleration remain. Optional replay intensities draw as one cached Canvas path above the seek line. Absent data and uncovered time ranges stay clear; controls still auto-hide. |
| Close a sheet or leave playback. | Pending sheet requests cancel; visible artwork is released. Optional metadata failures do not stop the video. |

The 95 ordinary JVM checks and Android lint pass. The opt-in public YouTube
probe also passed actual search, Shorts, device-code issuance, public stream
access, related videos, comments and replay-marker parsing. Actual Google owner
approval was not performed; account completion uses generated fixtures.

Worker emulator checks exercise the above actions and existing on-demand,
Live TV and settings behavior. The candidate verification under
`releases/0.16.0/verification.json` records every case, source/archive hash and
the scope of reruns. A timing-dependent test assertion was corrected to wait
for the new player after a related-video route changes; its assertions still
require a different, playing player and the correct Back destination.
The expanded run also exposed a real cancellation race: scroll pagination
cancelled a video being opened. Continuations now preserve explicit playback
resolution. A regression selects a video and starts pagination concurrently;
the same test/fixtures compare the original behavior with the corrected model.

The base 0.15.0 candidate was checked on the physical TCL before the user's
later instruction to hold TV testing. The 0.16.0 candidate is not installed or
tested there. No physical-TV memory or frame-rate improvement is claimed for
the new YouTube feature. IPTV defaults remain empty and existing data is kept.
