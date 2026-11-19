# 777tv

A native Android TV app for YouTube, on-demand movies and shows through Stremio
add-ons, plus optional Stalker IPTV profiles. Kotlin, native Android views, RecyclerView
and Media3 ExoPlayer; no JavaScript UI or alternate playback engine.

## Development candidate

**0.25.0 · version code 33 · Android 7.0 / API 24 or later**

This branch contains the native YouTube integration and continuing TizenTube
functional review, with independent app and section themes. Version 0.25.0
improves native scrolling, retains nearby cards and artwork, and reuses episode
and stream presentation data. YouTube keeps its own player; IPTV and On-Demand
retain their existing controls. See the [navigation review](docs/navigation-performance-0.25.md).
This candidate is **not deployed to the TCL**. The user has requested a hold;
development and validation use the Mac and worker emulators. The TV's last
installed version remains 0.24.0; its foreground session and data are preserved.
The application ID remains `xyz.billybishop4.fleet.tv` for in-place updates.
The app and launcher are named **777tv**.

[Candidate verification](releases/0.25.0/verification.json) records the APK checksum,
source/build checksums and validation scope.
[SHA256SUMS](releases/0.25.0/SHA256SUMS) identifies the candidate APK. The original
production import remains recorded in [0.14.1](releases/0.14.1/production.json).
Signing keys, credentials, device exports, imported APKs and local build outputs
are excluded from Git.

## Features

- Ten coordinated **themes**, with Classic preserving the current default.
  On-Demand, YouTube and Live TV can each override or inherit the app palette.
  Cinema uses black backgrounds with violet accents, Tube uses solid black/white/red, and Aurora uses
  charcoal teal/mint. Focus, saved selection and inheritance remain distinct.
  Static native backgrounds and tinted controls add no blur, animated wallpaper
  or decoder restart. See the [theme guide](docs/themes.md) and
  [reviewed native screenshots](releases/0.22.1/README.md).

- A **YouTube** section beside On-Demand: a collapsible left rail, native TV
  keyboard, live query suggestions and platform voice search. Home preserves real TV recommendation
  shelves. Guest Home tries the public Home feed first, with bounded Music,
  Gaming and News fallback shelves when YouTube requires sign-in. Slow guest requests
  have bounded deadlines and retain completed topic shelves. Search puts
  regular video shelves first, followed by one horizontal portrait Shorts row.
  Channel pages provide the real available video tabs and subscriptions. Watched
  thumbnail markers use supplied resume data or server percentages without inventing seek positions.
- Google TV code/QR sign-in, account Subscriptions, History, Watch Later, Liked
  videos and browseable playlists. Explicit Like, Dislike, Subscribe, Save,
  remove and private-playlist creation wait for server confirmation and refresh
  affected lists. Signed-in watched spans can update Google history after a real
  frame; paused-history policy, account changes and seeks are respected.
  Signed-out history remains local. Clearing this TV's history leaves Google
  history intact. Restricted/private media authorization is not claimed.
- Link a phone through YouTube's **Link with TV code** flow. While 777tv is open,
  phone commands use the existing native player for video selection, queues,
  pause, seek, volume, captions and speed. Disconnect retires pending handoffs;
  a new local selection retires the old phone queue. This is a TV-code receiver,
  not certified Chromecast or outbound casting to another TV.
- Regular YouTube has a full-width thin red timeline, centered playback controls,
  channel/Description/Subscribe and direct Like/Dislike/Comments/Save/CC/Settings.
  Held seeking accelerates a storyboard/time preview and commits one decoder seek
  on release. Newer media/phone commands supersede the pending local gesture.
  Caption and speed preferences are independent of On-Demand. YouTube live uses
  actual DVR seekability; IPTV retains its transport-free presentation. See the
  [player review](docs/youtube-player-0.24.md).
- Regular YouTube supports Next, a conditional Previous control, chapter navigation,
  a bounded explicit queue and saved
  Autoplay preference. Actual watch-next metadata loads after a rendered frame
  and is shared with Related; the next media URL/player loads only when chosen
  or when enabled autoplay reaches the end. Google account actions and a real
  phone pairing still require an account-owner-led acceptance check. See the
  [current parity review](docs/youtube-parity-0.23.md) and
  [implementation investigation](docs/tizentube-investigation.md).
- Read-only YouTube comments, replies and horizontal related videos during
  playback, with direct remote reply navigation. A faint **Most replayed** graph appears above the thin seek line
  when YouTube provides replay data. Comments load on opening; the player keeps
  playing while browsing related videos below it or comments beside it.
- Compact YouTube Home Search, remembered rail/grid focus, row-edge TV navigation
  and keyboard Back that keeps search results. Comments, replies and related
  videos retain their reading positions; loaded content receives focus directly.
  Settings opens its selected category in one step. Shared dialogs and background
  Live work retire with their screen, while catalog snapshots and fixed artwork
  avoid unnecessary rebuilds and layout work. See the [team UI review](docs/ui-review-0.19.0.md).
- Four native player presentations: Shorts have portrait framing, a read-only
  progress line, looping playback and direct Up/Down clip navigation. Regular
  YouTube has quality, comments and related-video actions, without Picture controls; movies and episodes
  keep the thin transport with audio/subtitle selection. Live TV shows channel
  information and available guide data without transport or seeking controls.
  Shorts warm at most one upcoming URL without a second player or media buffer.
- On-Demand opens by default; Settings → Appearance → Startup screen can select
  Live TV or YouTube instead. Compact catalog shelves, grouped search, continue watching,
  recently watched titles and My list.
- Stremio manifest installation, configured add-ons, catalogs, search, metadata
  and direct HTTP(S) stream links, including add-on request headers.
- Movies load their streams on opening. Series keep the poster and episode
  description above continuous episode and fixed-size stream rows. Left/Right
  crosses season boundaries; OK selects an episode and loads its streams.
- ExoPlayer with hardware decoding by default. Slim, unboxed playback controls,
  auto-hide, accelerating held-key seeking, audio/subtitle selection, resume and
  optional SDR brightness, contrast and saturation controls for movies and IPTV.
  YouTube omits Picture controls in regular, Shorts and live presentations.
  Single-tap seek intervals stay predictable; held Left/Right accelerates earlier
  through larger steps, resetting on release, reversal or timeline focus loss.
- Optional Stalker Live TV: categories on the left, channels in the middle, and
  preview plus available schedule on the right. First OK previews a channel;
  a second OK opens fullscreen. Live playback has no seek bar.
- Add, edit, select a default or delete IPTV profiles, with required and optional
  device identity fields, connection testing, favorites and hidden categories.
  **No IPTV profiles are installed by default.**
- Encrypted provider/add-on configuration using Android Keystore, bounded
  artwork and channel caches, recycled lists and one shared player.
- Native TV lists use short, distance-based scrolling with no input throttle.
  Adjacent cards are retained within bounded pools; the pixel-accounted artwork
  LRU is capped at 8 MiB on low-RAM devices and 16 MiB otherwise. Episode indexes
  and prepared stream labels belong to the current screen and are cleared when
  it closes. Scrolling and focus never start a decoder or tune a Live channel.
- Faster explicit playback: YouTube resolves playback data without waiting for
  unrelated thumbnails or watch-next. Three recent URL records expire after
  60 seconds; a running next-Short request is reused when selected. All sources
  share reusable HTTP connections and start loading alongside controls. Initial
  buffers are 250 ms for Shorts, 350 ms for Live TV and 500 ms otherwise; ongoing buffering budgets
  and rebuffer protection are retained. Stalker reuses a briefly authenticated
  session and renews it after expiry, rejection, identity changes or explicit refresh.
  Rejected YouTube media URLs receive one full-extractor recovery attempt;
  the recovered source survives background/foreground transitions.

New installations include these public add-ons:

| Add-on | Manifest | Purpose |
| --- | --- | --- |
| Cinemeta | `https://v3-cinemeta.strem.io/manifest.json` | Catalogs, search and title details |
| GDrive | `https://gdrive.billybishop4-workers.xyz/gdrive/manifest.json` | Stream links |

Other add-ons can be installed in Settings → On demand → Manage add-ons using an
HTTP(S) or `stremio://` manifest URL. Configure an add-on on its own page first
when required, then install the resulting manifest URL. Non-HTTP sources such
as magnets need an external resolver and are not played by this app.

Live TV supports Stalker portals only. Portal URL and registered MAC are required;
name, serial number, Device ID, Device ID 2, signature, hardware/software versions,
PIN, User-Agent, username, password and timezone are optional. Supply identity
values only when required by the provider. Portal VOD, catch-up, account sync and
background/PiP playback are not implemented.

## Build

Use Python 3.11+ and Java 17 or later on Apple Silicon macOS or Linux x86_64.
The bootstrap script downloads a checksum-verified toolchain; the Gradle wrapper
pins Gradle 8.13. The app compiles against Android SDK 36.

```sh
python3 scripts/bootstrap.py
source scripts/android-env.sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Accept Android SDK licenses if prompted. Output:
`app/build/outputs/apk/release/app-release.apk`.

Release builds currently use the machine's local Android development key for
sideloading. Rebuilding elsewhere produces a different signing identity and APK
checksum; updating an existing installation requires its original signing key.
Keys are not included. `-PfleetUnsigned=true` produces an unsigned release APK
for separate signing.

Install on an explicitly selected emulator or TV:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell am start -n xyz.billybishop4.fleet.tv/.MainActivity
```

## Tests and verification

0.23.0 passes 236 ordinary JVM tests, Android lint and 135 unique native scenarios
against the same 63 production source files as the signed release. Three opt-in
JVM probes are skipped. Five signed-release TCL stages cover real suggestions,
Home/search, regular video, Shorts and repeated playback/memory cycles.
The [release report](releases/0.23.0/README.md) records timings, source hashes,
resolved test assumptions and remaining parity limits.

[Theme patch results](releases/0.22.1/README.md) record screenshots, source hashes
and contrast review. [0.22.0](releases/0.22.0/README.md) retains physical theme-switch
timings. The preceding
[0.21.1 playback report](releases/0.21.1/README.md) retains its separate media,
frame-deadline and memory scope. The [dedicated device probe](scripts/device_probe/README.md)
preserves configured app data; ordinary fixture suites must not run on a configured TV.

Candidate verification records native navigation/account/player, startup and
bounded-memory results against the exact production source files. Normal
suites use generated accounts, media and phone commands. They never mutate a
personal Google account, install IPTV profiles or contact the TCL. Opt-in public
YouTube probes are separately labeled; anonymous TV-code protocol checks do not
prove a real owner-approved account or phone session.

Independent reviewers use TizenTube's actual TV flows as the reference. The
native app aims for the same everyday functionality while retaining 777tv's
visual design. It does not claim every YouTube feature: comments are read-only,
private/restricted media playback and Google account switching are not covered,
and no certified Cast receiver or outbound casting is bundled.

In a paired Mac check, median public YouTube source resolution fell from 811 ms
to 153 ms. A matched worker test measures Play until both a video frame and
active playback are available, with resume disabled and constrained bandwidth:

| Source | Before | 0.18.1 |
| --- | ---: | ---: |
| Shorts | 1.18 s | 1.03 s |
| YouTube | 1.47 s | 1.10 s |
| On-demand | 1.24 s | 0.87 s |
| Live TV | 1.32 s | 0.95 s |

These are four-trial medians per source on the same software-rendered emulator,
using generated media; neither build rebuffers during the controlled observation.
Real public YouTube source timings are measured separately. These results compare
777tv builds; they are not a TizenTube comparison. The later physical TCL
measurements have a separate workload and are recorded above.

Artwork stays within an 8 MiB cache budget (4 MiB on low-memory devices), counting
actual hardware-bitmap pixels. Encoded movie buffering targets 24/16 MiB;
Shorts target 8/6 MiB with a shorter startup buffer. The matched 0.16.1 checkpoint
reduced encoded movie buffering from 48.3 to 25.1 MiB and movie-process PSS from
156.4 to 130.2 MiB. Those historical measurements do not establish a universal
frame-rate gain for the new layouts.

The same-worker navigation comparison delivered 18 keys per row. Episodes
previously advanced 8 rapid and 6 held moves; both now advance all 18, matching
streams. A bounded physical TV sample crossed a season boundary with 18 rapid
moves and recorded 84 frames, no janky frames and a 9 ms p95 frame time. These
measurements describe those workloads, not a universal frame-rate guarantee.

For local instrumentation, install FFmpeg and start the fixture server in a
separate terminal. It generates test media and serves fixture add-ons and a
Stalker portal on loopback port 8878:

```sh
python3 scripts/fixture_server.py
```

Run an Android TV emulator; fixtures use the emulator's `10.0.2.2` host route.
Select the relevant test classes explicitly:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=xyz.billybishop4.fleet.tv.BrowseNavigationTest,xyz.billybishop4.fleet.tv.SearchCatalogTest
```

Other instrumentation classes cover playback controls, rendered video, picture
adjustments, settings, profiles, live navigation, recovery and memory/performance.
`PortalPlaybackTest` and `ProvisionProfileTest` are opt-in private device tools;
they require explicitly supplied local JSON and must not be run against real
accounts as part of an ordinary fixture suite.

Optional remote Linux build/emulator workers are documented in
[remote/README.md](remote/README.md). Their access configuration is private and
is not needed for local builds. [REVIEW.md](REVIEW.md) retains development reviews;
historical artifact paths in that file refer to untracked local evidence.

## Source layout

| Location | Purpose |
| --- | --- |
| `app/src/main/java/xyz/billybishop4/fleet/tv/` | App, screens, models, add-on/Stalker clients, storage and player |
| `app/src/main/res/` | Native layouts, styles and vector artwork |
| `app/src/test/` | JVM protocol, parsing and robustness tests |
| `app/src/androidTest/` | Android TV navigation, rendering, playback and performance tests |
| `scripts/` | Toolchain bootstrap, fixtures and optional remote client |
| `remote/` | Optional build/test worker service and pinned toolchain |
| `releases/0.21.0/` | Prior candidate emulator verification and APK checksum |
| `releases/0.21.1/` | Previous physical playback measurements and checksum |
| `releases/0.22.0/` | Original themed release and full native regression record |
| `releases/0.22.1/` | Black On-Demand patch and exact verification |
| `releases/0.23.0/` | YouTube parity improvements, exact native coverage and TCL measurements |

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency notices.
