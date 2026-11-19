# 777tv 0.26.0 preview

The transparent YouTube sidebar uses individual rounded highlights and expands
beside the video rows. Vertical remote navigation
scrolls continuously through rows and follows held input and reversals. Seeking
uses fixed seconds and a smooth hold acceleration independent of video length.
Shorts slide vertically with one decoder. YouTube keeps its picture/artwork
during loading and uses a small delayed spinner without a buffering text card.
Back or changing sections cancels pending videos before they can reopen playback.
Signed-out Home uses YouTube TV's actual feed and session context without
locally invented recommendation shelves. Ready video actions recover initial
focus after their window opens, while preserving newer remote selections.

The selected native regression scope, signatures and exact source/APK checksums
are in [verification.json](verification.json). [SHA256SUMS](SHA256SUMS) identifies
`777tv-0.26.0-preview.apk`. The [motion review](../../docs/youtube-motion-0.26.md)
describes the behavior, and the [QA review](../../docs/qa-review-0.26.md) explains
test scope, reproduced failures and corrected test inputs. Physical TV and real
Google/provider acceptance are recorded separately from generated worker tests.

The signed 0.26.0/code 34 build is installed on the TCL. Its installed APK was
pulled and matched to the checksum above. UID, foreground and standby were
preserved; the app was not opened and no configuration, profiles or test packages
were changed. This update did not run an interactive physical-TV probe.
