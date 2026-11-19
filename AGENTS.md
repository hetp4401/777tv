# 777tv Android TV workflow (formerly Fleet)

- Het subsequently requested "Now push to tcl" after whole-app QA. The verified
  0.25.0/code 33 APK is installed in place on the TCL; the pulled installed APK
  matches SHA-256 `83f3effac99f942acf0632ce144138d122c0888da2cb40ecb96fdd3b39f769c6`.
  Package UID, foreground state and standby were preserved. The TV was asleep;
  no launch/wake, preference/profile edits, data clear, test installation or
  interactive playback/performance probe occurred. This supersedes the earlier
  deployment hold for this update. Preserve the user's current TV session/data;
  ordinary fixture/reset suites must never run on this configured device.
  Deployment evidence is private in `.tools/navigation025/deployment/` and the
  sanitized record is in `releases/0.25.0/verification.json`.
- Independent whole-app QA of 0.25.0 is complete: 275 unique fixture scenarios,
  one real anonymous YouTube Home/library probe (51 recommendations/seven shelves),
  245 JVM passes/3 opt-in skips, lint zero errors/107 warnings. QA used no TCL;
  the later authorized installation is recorded above.
  QA fixed two runtime-confirmed bugs: Playback now lets nine global media keys
  continue through existing dispatch while YouTube panels retain native D-pad;
  StreamChoices caches semantic labels from the canonical committed Stream,
  with an identity guard, so stale holders cannot poison refreshed-source focus.
  Preserve these guards and the metadata/renewal/headers/Play/Back regression.
  The final release APK SHA-256 is
  `83f3effac99f942acf0632ce144138d122c0888da2cb40ecb96fdd3b39f769c6`.
  All 68 production and three root build hashes match selected snapshots; the
  main APK was identical across jobs. Two harness corrections use a newer test
  APK with that same main APK. Native legacy dialog/Shorts readiness failures,
  fixture model-replacement/counter errors and worker interruptions are retained
  in `releases/0.25.0/verification.json`, with successful stricter reruns. Do not
  claim speculative dialog/queue product fixes. Fixture portal_links is process-
  cumulative: assert measured deltas, preserving absolute baselines. Fixture
  account tests must sign in through the active model, rather than replacing it
  inside a coalesced route change. See `docs/qa-review-0.25.md`. Private evidence,
  signed test-build chunk runner and independent verdict stay in
  `.tools/navigation025/qa/`; no signing keys/data were uploaded. Real Google-owner,
  phone pairing/provider playback and physical-TV acceptance remain untested.
- 0.25.0/code 33 is the verified build now installed on the TCL. Native
  lists use 70–140 ms scrolling; nearby cards and pixel-accounted artwork stay
  within bounded caches (8 MiB low-RAM / 16 MiB normal). Keep stream identity
  indexes tied to the committed adapter until its async diff completes. Current
  series/list presentation caches clear on close; cached Live holders refresh
  preview/favorite state on reattach without tuning. YouTube retains a temporary
  page-local focus owner during deferred shelf moves, plus matching-release and
  scope/account/input/lifecycle guards for pending OK. The corrected ten-row
  baseline reproduced focus escaping to top navigation; both candidate workers
  reach all seven navigation destinations. Initial 10×24 Home fixtures exceeded
  the production 120-video cap and yielded five rows; discard those vertical
  results. Use 5×24 horizontally, 10×12 vertically, verify adapter capacity and
  wait for the intended focus plus completed layout. Final scrolls animate even
  with worker ValueAnimator scales disabled; discard the instant-scroll prototype.
  `releases/0.25.0/verification.json` records 245 JVM passes/3 opt-in skips,
  lint zero errors/107 warnings, 275 fixture passes and one real-service pass. All 68 production
  hashes and three root build-config hashes match selected snapshots/release
  inputs. The historical isolated navigation sample grew about 4.4 MiB in PSS;
  the final broader QA process has different warmup/prior work. Software timings do not
  prove physical-TV smoothness. See `docs/navigation-performance-0.25.md`.
  Private QA evidence stays in `.tools/navigation025/`; those tests used emulators.
- Prior 0.24.0/code 32 was installed in place on the TCL. The installed APK was pulled
  and matched to the signed candidate; its UID and the user's foreground Stremio
  session were preserved. No app launch, interactive playback test, preference/
  profile edit, data clear or instrumentation installation was performed on the
  TV for 0.24.0. Preserve the current foreground session. This release changes
  only YouTube playback: full-width timeline, three control groups, direct
  account/CC/settings/Description actions and optional storyboard seeking.
  On-Demand and IPTV retain their controls; Shorts retains its portrait UI.
  Keep deferred seek commit/cancel intent, explicit media/phone reconciliation,
  close-at-EOF guards, account-scope notification idempotence and ACK precedence.
  YouTube speed/captions are separate from movie preferences. Canvas icons need
  explicit cached ColorFilters for dark glyphs on white focus; Drawable's default
  setTintList does not tint these custom drawables. Sprite requests are lazy,
  debounced and canceled/cleared on hide/close; use the shared bounded cache.
  `releases/0.24.0/verification.json` records 245 JVM passes/3 opt-in skips,
  lint zero errors/113 warnings and 112 unique native passes with scoped reruns.
  All 67 production hashes match the selected test snapshots and release inputs.
  The record explicitly retains superseded combined-run action failures; the
  final complete account suite passed. Avoid slow accessibility absence checks
  before activating an auto-hiding HUD: use native inspection and require shown
  focus. Clear the sprite cache before tests that require a first network fetch.
  Private evidence stays in `.tools/youtube024/`. The player guide and generated
  screenshots are in `docs/youtube-player-0.24.md` and `releases/0.24.0/`.
- Prior 0.23.0/code 31 was installed on the TCL with its UID/data, two add-ons, zero
  profiles and black/violet On-Demand Cinema preference preserved. Three agents
  improved YouTube suggestions, watched-thumbnail progress, chapters, Previous,
  replies, guest deadlines and rejected-source recovery. Keep the current-shelf
  identity/adapter-position/video guard before restoring focus: old query rows
  can remain attached for one frame with reused video IDs. Preserve display-only
  watched percentages separately from exact resume positions, first-frame gating
  of optional metadata/warmup, and retained recovered sources after backgrounding.
  `releases/0.23.0/verification.json` records 236 JVM passes/3 opt-in skips, lint
  zero errors, 135 unique native passes and five signed-release TCL stages. All
  63 production hashes match. Phone cancellation tests must wait for dialog input
  focus; chapter request counts exclude the completed Home recommendation seed.
  Actual public suggestions were 670 ms, regular first frame 2.59 s and next Short
  1.19 s; steady video windows had zero drops/rebuffers. Warm search had 1 actual
  deadline overrun/93 frames, so do not claim zero UI jank. Private reports/screens
  stay in `.tools/youtube023/`; probe/downloads removed, On-Demand reopened after
  checks. Preserve the user's current TV foreground session.
  `docs/youtube-parity-0.23.md` explicitly retains Google/extension/owner-acceptance
  limits; this is improved native coverage, not 1:1 TizenTube equivalence.
- Latest user clarification makes the whole On-Demand area black with accents,
  not just stream cards. 0.22.1/code 30 introduced this on the TCL; Cinema is
  selected only for On-Demand. Its backdrop is true black, cards/fields neutral
  charcoal, with retained violet selection/focus/action accents. App/YouTube/
  Live/background choices were preserved through the actual settings UI.
  `releases/0.22.1/verification.json` records 218 JVM passes, lint0 and the updated
  native section journey with all 63 source hashes matched. Keep real TV artwork/
  history captures private in `.tools/cinema-black0221/`.
- Prior release was 0.22.0/code 29, installed in place on the TCL November 18,
  2026, with the original appearance and other app data preserved. Theming has
  a Classic app/upgrade default plus independent area overrides and mixed solid/
  static-gradient presets. Latest user direction: On-Demand Cinema violet/blue-
  black, YouTube Tube black/white/red, Live Aurora charcoal/teal/mint. These are
  the reviewed preview recipe; do not silently replace saved preferences.
  A dedicated visual-design agent refined and approved the final native gallery.
  Keep Tube focus white, saturated red selection readable, and Live backgrounds
  restrained. Focus previews; OK applies; inheritance differs from explicit Classic.
  See `docs/themes.md` and `releases/0.22.0/verification.json`: 218 JVM tests,
  31 native scenarios, all 63 production hashes matched; ten real TCL theme
  selections and independent section palettes passed. Temporary device probe
  and diagnostic downloads removed. Private captures stay in `.tools/theme022/`.
- Prior user instruction authorized physical TCL deployment/testing. On
  November 18, 2026, 0.21.0 was installed in place, then 0.21.1/code 28 added a
  verified fix for numeric YouTube reply labels. Nine final signed-release
  physical stages pass; see `releases/0.21.1/verification.json`. The release is
  left open on On-Demand; its UID/data, preferences, add-ons and zero profiles
  were preserved. Temporary instrumentation, downloads, reverse port and local
  media server were removed. No current TV hold; check later user steering.
  Keep private screenshots/raw reports in `.tools/tcl021/`, outside Git.
  Use `scripts/device_probe/README.md` for the dedicated opt-in release probe;
  never run fixture suites that reset configuration on a configured TV.
  No default IPTV profiles; leave STBEmu unchanged. Real Google-owner/phone
  acceptance and real-IPTV playback were not performed. Controlled live tests
  used generated HLS without adding a profile. Preserve existing resume data.
- Current feedback: search must present full videos first and separate Shorts
  rows, and Home/search should closely follow TizenTube. YouTube players must
  omit Picture controls, and held seeking must accelerate much faster. Continue
  independent reviewers toward functional TizenTube parity and keep changes
  native, responsive and bounded in memory.
- 0.21.0 uses real TV Home/search shelves, with full videos before one Shorts
  row, browseable playlists, confirmed mutation cache invalidation, Next/Previous,
  a bounded local queue and saved Autoplay. Preserve transactional replacement:
  failed/cancelled sources keep the previous queue and decoder. Phone ownership
  persists through failed handoffs and releases on explicit Disconnect. Optional
  watch-next starts after a real frame, shares metadata with Related, and handles
  account changes/late EOF without advancing a paused video. YouTube has no
  Picture entrypoint; on-demand/IPTV retain it. Keep explicit tap seek intervals
  and early, stronger held-key acceleration with release/reversal/focus resets.
- 0.20.0 adds platform voice search, creator video tabs, explicit authenticated
  rating/subscription/playlist actions, account-scoped history reporting after a
  rendered frame, and foreground phone control using YouTube TV-code pairing.
  Account/media/phone UI checks use generated fixtures; read-only anonymous
  public API probes are recorded separately. Do not send
  explicit real account mutations (Like/Subscribe/Save/delete/sign-out) to validate
  these flows. User-authorized normal playback may use its usual watch history. Google
  account-owner approval and actual phone/TV pairing require a separate user-led
  check. Keep anonymous phone identity separate from account credentials, cancel
  pending remote playback on Disconnect, and preserve commands during handoff.
- 0.19.0 is the team UI review: compact YouTube Home Search shortcut, TV grid/rail
  focus, preserved comments/related positions, immediate hidden-arrow seeking,
  settings entry, background Live filtering and shared-dialog lifecycle fixes.
  Keep input-generation guards and cancelled/duplicate key-release protection.
  That release improved supported YouTube TV flows; its missing voice/channel,
  account-action, phone-control and history capabilities are addressed in 0.20.0.
- 0.18.0 work adds guest recommendations, account/library browsing, and distinct
  Shorts, YouTube, on-demand and Live TV presentations. Keep ExoPlayer as the only
  engine, native views, bounded caches/buffers, no seek/transport UI for live TV,
  and no frame-history graph or movie transport panel for Shorts.
- 0.18.1 optimizes explicit Play to first frame: playback-only public YouTube
  requests with the full extractor as fallback, three URL records for 60 seconds,
  adoption of in-progress Shorts warmups, shared HTTP connections and short initial
  buffers with unchanged rebuffer protection. Stalker reuses one authenticated
  session for up to 60 seconds, bound to the complete provider identity; expiry,
  rejection and explicit refresh reauthenticate. Tokens stay in memory.
- 0.16.0 adds a native YouTube tab beside On-Demand, read-only comments/replies,
  related videos and an optional replay graph. Its API/parser, account flow
  and ExoPlayer resolver are described in `docs/tizentube-investigation.md`.
  Keep Google tokens encrypted and out of logs, bundles and reports. Normal UI
  suites use generated YouTube fixtures. `YouTubeProductionProbeTest` requires
  explicit `youtubeLive=true`; it never provisions IPTV profiles or supplies
  Google account credentials. Preserve existing account approvals and app data.
- Earlier authorization covered local Mac development/builds/tests and direct
  deployment to the TCL; the latest explicit testing authorization above takes precedence. Preserve watch history, add-ons and other app data. ExoPlayer is now the only player; no engine
  setting remains. The app is named 777tv; its application ID stays unchanged for
  in-place updates. The user subsequently requested deleting the saved IPTV
  profile on the TCL and shipping no default profiles. The profile was deleted;
  do not restore or provision one unless explicitly asked. Leave STBEmu unchanged.
  Existing remote runs can finish; use local or remote tools as useful.
- `remote/README.md` documents the three `fleet-tv-dev` build/test replicas.
  Use native Node fetch for Shellbox/Fleet access; no browser is needed.
- Use generated media and Stalker fixtures remotely. Never upload real IPTV
  credentials, `.tools/stalker-private.json`, signing keys, `.env` or the private
  worker-access configuration in source archives, logs or app artifacts.
- Keep the existing `shellbox` app and its sessions intact. Development uses
  the separate, password-protected `fleet-tv-dev` provider app.
