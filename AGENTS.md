# 777tv Android TV workflow (formerly Fleet)

- Latest instruction: do not install or test on the physical TCL until Het says
  to do so again. Keep current YouTube work and testing local or on worker emulators.
  This supersedes the earlier TV testing authorization below.
- 0.16.0 adds a native YouTube tab beside On-Demand, read-only comments/replies,
  related videos and an optional replay graph. Its API/parser, account flow
  and ExoPlayer resolver are described in `docs/tizentube-investigation.md`.
  Keep Google tokens encrypted and out of logs, bundles and reports. Normal UI
  suites use generated YouTube fixtures. `YouTubeProductionProbeTest` requires
  explicit `youtubeLive=true`; it never provisions IPTV profiles or supplies
  Google account credentials. Preserve existing account approvals and app data.
- Het now authorizes local Mac development/builds/tests and direct deployment
  to the TCL for testing. Preserve watch history, add-ons and other app data. ExoPlayer is now the only player; no engine
  setting remains. The app is named 777tv; its application ID stays unchanged for
  in-place updates. The user subsequently requested deleting the saved IPTV
  profile on the TCL and shipping no default profiles. The profile was deleted;
  do not restore or provision one unless explicitly asked. Leave STBEmu unchanged.
  Existing remote runs can finish; use local or remote tools as useful.
- `remote/README.md` documents the three `fleet-tv-dev` build/test replicas.
  Use native Node fetch for Shellbox/Fleet access; no browser is needed.
- For the current Android TV updates, Het's later "Install it on the tcl now"
  authorizes installation and focused checks on the physical TCL. Preserve app
  data and saved profiles. Run automated fixture/performance suites on workers.
- Use generated media and Stalker fixtures remotely. Never upload real IPTV
  credentials, `.tools/stalker-private.json`, signing keys, `.env` or the private
  worker-access configuration in source archives, logs or app artifacts.
- Keep the existing `shellbox` app and its sessions intact. Development uses
  the separate, password-protected `fleet-tv-dev` provider app.
