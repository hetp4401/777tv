# 777tv 0.25.0 preview

Native TV navigation uses shorter scrolling, bounded adjacent-card and artwork
retention, and cached episode/stream presentation. Rapid YouTube shelf changes
keep input on the page until the intended card attaches; pending OK respects
release, cancellation and the current source/account. Cached Live rows refresh
preview/favorite state without tuning. The existing themes and player controls
are preserved.

Independent whole-app QA also fixed media Next/Previous/Skip inside YouTube
panels and stale source focus after metadata/link renewal. Verification includes
**275 unique fixture scenarios**, a successful real anonymous YouTube Home/library
probe, 245 JVM passes and three opt-in skips. Lint has zero errors/107 warnings.
All seven navigation destinations are reached with zero added channel tunes.
All 68 production hashes and three root build configurations match selected
snapshots and signed release inputs. The artwork cap increases from 8 to 16 MiB,
and from 4 to 8 MiB on low-RAM devices. No dependency or player was added.

This candidate is **not installed on the TCL**. The user has held deployment and
testing until asked; the last known installed version remains 0.24.0/code 32.
Worker software-rendered timings do not establish physical-TV frame pacing.

[Navigation review](../../docs/navigation-performance-0.25.md) explains the
implementation and measured tradeoffs. [Whole-app QA](../../docs/qa-review-0.25.md)
describes coverage, confirmed fixes and corrected test setups. [Verification](verification.json)
records exact validation and checksums. [SHA256SUMS](SHA256SUMS) identifies
`777tv-0.25.0-preview.apk`.
