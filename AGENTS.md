# 777tv Android TV workflow (formerly Fleet)

- Latest user instruction authorizes physical TCL deployment/testing. On
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
