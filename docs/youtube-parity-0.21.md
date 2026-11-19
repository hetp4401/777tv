# Native YouTube functional review

Reference: the [TizenTube Cobalt APK](https://github.com/reisxd/TizenTubeCobalt/releases/tag/v2.0.3)
and its [YouTube TV userscript](https://github.com/reisxd/TizenTube/blob/main/mods/userScript.js).
The APK inspection and public-response investigation are recorded in
[tizentube-investigation.md](tizentube-investigation.md). The TV website supplies
TizenTube's feeds and account behavior; 777tv presents those responses using
native Kotlin views and its existing ExoPlayer.

## Everyday flows

| Flow | Native behavior |
| --- | --- |
| Home | Actual TV recommendation shelves; guest Home first, bounded public topic fallback when YouTube requires sign-in. No duplicated Discover shelf. |
| Search | TV keyboard, suggestions/history and platform voice with keyboard fallback. Full-video shelves precede one canonical Shorts shelf, including pagination. |
| Shorts | Portrait cards, looping playback, Up/Down clips, comments and account actions; no movie seek controls or Picture button. |
| Regular playback | Thin auto-hide transport, rapidly accelerating held seek, quality/audio/captions, Related and comments; no Picture controls. |
| Channels | Available Home/Videos/Shorts/Live tabs, pagination and confirmed subscription changes. Back preserves the previous browse context. |
| Accounts | Owner-approved Google TV code/QR sign-in, refresh/revocation, sign-out and encrypted tokens. No password collection. |
| Actions | Like/Dislike/remove, Subscribe/Unsubscribe, Save/remove, Watch Later and private playlist creation. Server acknowledgment precedes selected state and affected-list invalidation. |
| Library | Account History, Watch Later, Liked videos and actual playlist collections/videos; local History when signed out. |
| History sync | Watched spans begin after a rendered frame and use account-scoped tracking; no seek-gap replay, paused-policy override or credential forwarding to third-party hosts. |
| Phone control | YouTube's TV-code linking, foreground command receiver and status updates, bounded queue and one native player. Local selection retires an old phone queue. |
| Next/queue | Actual watch-next candidates after first frame; explicit queue and previous trail bounded to 20 items, saved Autoplay option. Metadata is shared with Related; no next media download or second decoder before selection/end. |

## Resource and lifecycle rules

The app does not embed Cobalt, Chromium, WebView or the original APK. Artwork
stays within 8 MiB (4 MiB on low-memory devices); the encoded movie buffer targets
24/16 MiB and Shorts 8/6 MiB. Feed pages, channel tabs, playlist collections,
commands and response parsing have explicit bounds. Hidden watch panels are lazy.
One shared watch-next response retains lightweight cards rather than raw JSON.

Voice cancellation, account epochs, sheet ownership, navigation generations and
phone handoffs reject late results. Personal actions require explicit UI input;
automated checks use generated accounts. Optional history, replay, queue metadata
and phone work do not delay the first video frame.

## Verification scope and remaining limits

Exact counts, source hashes, candidate checksum and resolved failures belong in
[candidate verification](../releases/0.21.0/verification.json). Independent
reviewers check browse/focus behavior, player modes and a customer journey. An
earlier 0.20 suite exposed a Python fixture collision affecting Shorts, outdated
panel navigation, a test observing the previous live player, a live remote-release
gap and one real startup rebuffer. Those failures remain recorded; a later passing
run must establish the final-source result.

Public anonymous probes can establish channel/feed schemas and TV-code issuance;
they do not establish an owner-approved account mutation, actual phone app pairing
or successful Google watch-history synchronization. Those require a user-led
acceptance check. Comments are read-only. Account switching, paid/private media,
background/PiP, certified Chromecast discovery/receiver behavior and outbound
casting are not implemented. This is close everyday functional coverage, not a
claim of complete YouTube parity.

The user subsequently authorized physical TCL deployment and testing. Version
0.21.0 was installed in place, then 0.21.1 corrected bare numeric reply labels
found in actual comments. Signed-release hardware playback, navigation and
memory results are in the [TCL report](../releases/0.21.1/README.md). The dedicated
probe preserved configuration; no real IPTV profile or Google-owner acceptance
is inferred from generated live playback and public YouTube checks.
