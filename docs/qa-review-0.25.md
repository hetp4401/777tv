# Whole-app QA · 0.25.0

Independent QA inventoried every Android test, reproduced failures, reviewed the
fixes and reconciled the results. **All 275 fixture scenarios passed**, with no
missing or duplicate cases. A separate anonymous check against real YouTube
also passed: 51 recommendations across seven shelves, native Library and Home.

The final build passes 245 JVM tests, with three opt-in skips. Lint reports zero
errors and 107 warnings. All selected jobs used the same main APK; all 68
production hashes and three root build configurations match the signed release
inputs. Workers verified their APK pairs and 153 source checksums. Two corrected
test setups use an updated test APK with the same application.

| Area | Covered journeys |
| --- | --- |
| On-Demand | Catalogs, search/keyboard, add-ons/recovery, movies, seasons/episodes, stream headers, renewed links, source selection, resume and Back |
| Live TV | Generated Stalker profiles/identity fields, categories, favorites, hidden categories, explicit preview/fullscreen, guide, recovery and 6,000-channel scrolling/tuning |
| YouTube | Home, grouped video/Shorts search, rail/grid/shelves, voice integration, channels, playlists, fixture accounts/history and phone commands |
| Players | Four presentations, transport/auto-hide, accelerating seek, storyboard/replay data, captions/audio, movie picture controls, errors, queues, media keys and decoder handoffs |
| App state | Settings, themes, startup, persistence/recreation, background/foreground, memory pressure and account changes during pending navigation |

QA confirmed and fixed two product issues:

- **Media commands inside YouTube panels.** Next, Previous and skip keys were
  blocked while Related, Comments or Description was open. Four tests reproduced
  this. Supported media keys now continue through the existing dispatcher while
  the panel keeps directional navigation. Final tests verify queue identity, one
  transition, source identity and playing/paused intent.
- **Focus after stream refresh.** An attached holder could cache old metadata
  after a committed update. Later link renewal/reordering selected Alpha at the
  old ordinal instead of the chosen Beta. Labels now use the canonical committed
  stream with an identity guard. The reproducer passes through renewed-source
  selection, current headers, explicit Play and Back without autoplay.

All seven navigation destinations are reached with zero added channel-link
requests per profile, Live setup/input and the whole measured journey. The
fixture counter started and ended at 15 because earlier explicit tuning tests
share the process; absolute counts are preserved alongside the zero deltas.

Native captures were independently reviewed across all three sections and
YouTube's HUD, seek preview and Description. Focus is readable, poster borders
leave text outside, the timeline stays thin, controls fit, and the side pane
preserves the video. No visual blocker was found. Colored bars/shapes are test
media and artwork.

Earlier failures remain in the record. Legacy dialog and Shorts tests lacked
explicit window/input or attached-player readiness checks. Stronger versions
include actual key delivery, frame/player identity and focus traces, and pass
with the feature code unchanged. Their original precise failure conditions
were not fully observed; no speculative dialog or queue fix was made. The
cumulative-counter assertion and new account test's fixture model replacement
were corrected, then the actual scenarios passed. No test was dropped from
the coverage inventory.

Worker dependency downloads and replacements caused separate infrastructure
failures. Verified locally compiled APKs allowed the affected checks to continue;
signing keys and application data were never uploaded.

QA completed on Mac/worker emulators during the earlier deployment hold. The
user then authorized installation of **0.25.0/code 33 on the TCL**. Its installed
APK was checksum-matched, with UID, foreground state and standby preserved.
No app launch or interactive physical-TV probe occurred. Physical frame pacing,
audible output and remote/microphone hardware remain outside this QA.
Real Google-owner approval, account mutations/history acceptance, actual phone
pairing and private provider playback also remain outside fixture checks.
The real-service probe covers anonymous Home/library only.

[Verification](../releases/0.25.0/verification.json) identifies every selected
case, checksum, corrected failure and validation limit. The
[navigation review](navigation-performance-0.25.md) describes resource budgets
and the scope of software-rendered measurements.
