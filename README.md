# 777tv

A native Android TV app for YouTube, on-demand movies and shows through Stremio
add-ons, plus optional Stalker IPTV profiles. Kotlin, native Android views, RecyclerView
and Media3 ExoPlayer; no JavaScript UI or alternate playback engine.

## Development candidate

**0.18.0 · version code 23 · Android 7.0 / API 24 or later**

This branch contains the native YouTube and source-specific player update.
The latest user instruction holds TCL testing and deployment. Version 0.18.0
is validated locally and on worker emulators; it has not been installed
on the physical TV. The release record tracks the exact validation scope.
The application ID remains `xyz.billybishop4.fleet.tv` for in-place updates.
The app and launcher are named **777tv**.

[Candidate verification](releases/0.18.0/verification.json) records the APK checksum,
source/build checksums and validation scope.
[SHA256SUMS](releases/0.18.0/SHA256SUMS) identifies the candidate APK. The last
published production snapshot remains recorded in [0.14.1](releases/0.14.1/production.json).
Signing keys, credentials, device exports, imported APKs and local build outputs
are excluded from Git.

## Features

- A **YouTube** section beside On-Demand: horizontal recommendation shelves, a
  collapsible left rail, a native TV search keyboard and portrait Shorts. Home
  preserves signed-in recommendation rows, with 777tv colors and corners.
  Signed-out Home also works: public Music, Gaming and News suggestions populate
  Discover, with a related-video row based on locally watched videos.
  Subscriptions and Library add account history, Watch Later and Liked videos;
  signed-out history uses this TV’s saved playback records. Google TV code/QR
  sign-in personalizes the account feeds. The same ExoPlayer plays videos;
  no browser engine or second player is bundled.
  Public videos play signed out; private/restricted video playback and Google
  watch-history synchronization are not claimed. Details and APK investigation:
  [YouTube integration](docs/tizentube-investigation.md).
- Read-only YouTube comments, replies and horizontal related videos during
  playback. A faint **Most replayed** graph appears above the thin seek line
  when YouTube provides replay data. Comments load on opening; the player keeps
  playing while browsing related videos below it or comments beside it.
- Four native player presentations: Shorts have portrait framing, a read-only
  progress line, looping playback and direct Up/Down clip navigation. Regular
  YouTube has quality, comments and related-video actions; movies and episodes
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
  optional SDR brightness, contrast and saturation controls.
- Optional Stalker Live TV: categories on the left, channels in the middle, and
  preview plus available schedule on the right. First OK previews a channel;
  a second OK opens fullscreen. Live playback has no seek bar.
- Add, edit, select a default or delete IPTV profiles, with required and optional
  device identity fields, connection testing, favorites and hidden categories.
  **No IPTV profiles are installed by default.**
- Encrypted provider/add-on configuration using Android Keystore, bounded
  artwork and channel caches, recycled lists and one shared player.

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

The candidate passes 108 ordinary JVM checks, 145 unique worker emulator
scenarios and Android lint. Two opt-in public YouTube checks verify real
signed-out recommendations, search, Shorts, media,
comments, replay data and device-code issuance without account approval.
Worker Android TV suites cover navigation, account fixtures, all four player
presentations, seeking, picture adjustments, failure recovery and bounded memory.
Exact counts, source scopes, resolved development failures and physical-device
checks are recorded in the candidate verification.

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
| `releases/0.18.0/` | Candidate verification and APK checksum |

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency notices.
