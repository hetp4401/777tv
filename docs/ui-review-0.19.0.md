# 0.19.0 UI and efficiency review

The review covered every 777tv section: YouTube Home/Search/Shorts/library,
YouTube comments and related videos, movie/episode browsing and playback,
Live TV, profiles, add-ons and settings. Three domain reviewers implemented
confirmed findings; an independent reviewer assessed the flows as a TV viewer.
Root coordinated shared-view changes, builds and generated-media emulator checks.
No TCL contact, testing, installation or deployment is part of this update.

## Reference and design

The visual reference is the official [YouTube for Android TV listing](https://play.google.com/store/apps/details?id=com.google.android.youtube.tv&hl=en_US),
retrieved on November 18, 2026, plus the prior [TizenTube investigation](tizentube-investigation.md).
Its compact Home Search affordance, horizontal shelves, recent-search keyboard,
grid/rail navigation and right-hand comments inform the native layout. 777tv
retains its branding, global section tabs, neutral colors and rounded controls.
No website runtime, copied YouTube assets or second player is bundled.

## Confirmed findings and resulting behavior

| Area | Finding | Result |
| --- | --- | --- |
| YouTube Home | Full-width search, a separate submit button and repeated heading dominated the view. | One small Home Search pill opens the existing keyboard; shelves remain prominent. |
| YouTube grid | Left could cross into the previous row and repeated movement restarted smooth scrolling. | Row edges behave like TV grids; Left opens the rail, Right stays in its row, and native movement keeps input responsive. |
| Navigation | Rail/global-tab return lost the selected video or targeted a hidden search field. | Visible targets have stable IDs and return to the remembered video. |
| Search | Back discarded the search view; the keyboard had an inert slot. | Back closes the expanded keyboard while retaining the query/results; navigation skips the inert position. |
| Recovery | Retry could leave focus on a disappearing button. | Recovered content receives focus; empty guest recommendations have a working Refresh action. |
| Watch panels | Comments, replies and related videos reopened at their beginning and required an extra Down. | Separate lightweight bookmarks preserve position/focus; loaded content and appended pages receive focus directly. |
| Seeking | A hidden-control arrow press only revealed Play; holding required release and another action. | Left/Right focuses the timeline and seeks immediately; the same hold accelerates and releases cleanly without changing pause intent. |
| Playback input | Android can deliver a release after consuming its initial press during a focus-mode change. | Narrow Shorts/Related/ordinary-Up recovery distinguishes observed presses, stale entries, duplicates and cancelled releases. |
| Live TV | External session commands could pause a presentation that has no pause UI. | The live-facing player omits pause capability; raw engine audio-focus/lifecycle control remains available and foreground playback resumes. |
| Guide | A gap before the next programme hid available future information. | Known upcoming programmes remain visible. |
| Settings | Right could enter the previously selected page and OK required another focus step. | Rail focus selects its matching category; OK/Right enters its first setting and Left returns to the rail. |
| Async focus | Installation, dialog dismissal or sign-in cancellation could override newer user input. | Posted restoration checks the input generation. |
| Lifecycle | Some dialogs and Live filter work outlived their screen. | Shared dialogs retire with their screen; Live owns direct dialogs and cancels/restarts background filtering. |
| Artwork | The same URL suppressed a larger-size request; fixed poster loads could remeasure the hierarchy. | Request identity includes display dimensions; fixed poster frames redraw without unnecessary parent measurement. |
| On-demand | Unrelated state updates rebuilt shelf rows and repeatedly hashed catalog scope. | Unchanged row snapshots and catalog scopes are reused. |

## Validation and performance scope

[Release verification](../releases/0.19.0/verification.json) records the exact
production files, APK, JVM/lint results, worker sources, native scenarios and
resolved development failures. Scenarios use generated media, portal responses,
add-ons and account approvals. Actual Google account-owner approval remains
outside that scope.

The independent customer reviewer viewed 26 captured layouts across every
section and found no blocking visual defect. Neighboring-season episode labels
could include season numbers as optional polish. That visual approval is distinct
from the directed input and playback scenarios recorded in verification.

The before/after workload runs on the same worker with identical measurement
code. Its 18-key path traverses a four-column grid, respecting row edges instead
of assuming Right wraps. Both rapid and held trials must reach the same video.
Artwork pixel memory must stay within 8 MiB, while encoded buffering retains the
existing movie 24/16 MiB and Shorts 8/6 MiB budgets. No second decoder or media
buffer is added. Samples distinguish input delivery, memory budgets, process
PSS and software-rendered timing; they do not establish universal FPS or PSS gains.

The standalone comparison retains 8,316,672 artwork bytes in both builds.
The held 18-key path takes 3.04 → 2.56 seconds, while rapid taps take
2.93 → 3.14 seconds; both reach the required video. The 120-step scroll's
two-trial median is 6.43 → 6.06 seconds. Retained process PSS is
154,270 → 154,715 KiB, so this review does not claim reduced whole-process RAM.
Its single generated YouTube readiness sample is 1.02 → 1.58 seconds and is
not a repeated startup comparison. The separate four-source startup case
records 16 continuous, rebuffer-free trials, including a 954 ms YouTube
playable median on its own worker. Raw samples and worker identities remain
in verification; these workloads do not benchmark TizenTube or the TCL.

## Remaining YouTube capabilities

This release improves supported YouTube TV flows. Voice search, creator/channel
pages, Subscribe/Like/Save mutations, casting and Google watch-history upload
are not implemented. Signed-in History comes from Google; signed-out History
uses the existing device-local records. Comments remain read-only. The UI does
not display nonfunctional controls for those missing capabilities, and complete
YouTube feature parity is not claimed.
