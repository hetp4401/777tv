# 777tv 0.25.0 preview

Native TV navigation uses shorter scrolling, bounded adjacent-card and artwork
retention, and cached episode/stream presentation. Rapid YouTube shelf changes
keep input on the page until the intended card attaches; pending OK respects
release, cancellation and the current source/account. Cached Live rows refresh
preview/favorite state without tuning. The existing themes and player controls
are preserved.

245 JVM passes, three opt-in skips, lint with no
errors and 126 unique Android TV fixture scenarios. The corrected seven
navigation profiles reach every intended candidate destination on two workers.
All 68 production hashes match the selected snapshots and signed release
inputs. The normal artwork cache cap increases from 8 to 16 MiB, and from 4 to
8 MiB on low-RAM devices. No dependency or player was added.

This candidate is **not installed on the TCL**. The user has held deployment and
testing until asked; the last known installed version remains 0.24.0/code 32.
Worker software-rendered timings do not establish physical-TV frame pacing.

[Navigation review](../../docs/navigation-performance-0.25.md) explains the
implementation, measured tradeoffs and corrected workload. [Verification](verification.json)
records exact validation and checksums. [SHA256SUMS](SHA256SUMS) identifies
`777tv-0.25.0-preview.apk`.
