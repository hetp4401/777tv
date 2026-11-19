# YouTube navigation, seeking and loading, 0.26.0

The expanded sidebar now reveals its labels beside the video rows. Its full
width is measured once; clipping and a matching content translation preserve
the 12 dp gap without rebuilding the shelves during the animation. Browsing
returns to the narrow icon rail. The first title and thumbnail remain readable.
The rail itself is transparent. Only individual selected or focused icons have
rounded pills, including at the collapsed width. Solid and gradient section
backdrops remain visible between icons; there is no tall rectangular backplate.

Vertical remote navigation uses one frame-driven scroll that can change its
destination during a held key or reversal. Fixed shelf extents allow movement
through rows that have not attached yet. The existing logical cursor, temporary
focus owner, matching-release OK and source/account/lifecycle guards remain.
Each frame has a bounded distance; repeated input changes the target rather
than jumping the list to it.

## Fixed-time seeking

Left/Right advances the configured number of seconds, regardless of video
duration. A hold pauses for 300 ms after that first step, then continuously
ramps from 20 to 120 media seconds per real second over five seconds. Default
10-second gestures travel 28.9 seconds at one second, 72.9 seconds at two
seconds and 220.9 seconds at four seconds. Remote repeat frequency does not
change these distances. Release, reversal and superseding commands retire the
old gesture. Direct touch/accessibility absolute positioning still uses the
selected point on the timeline.

YouTube previews the time/storyboard and performs one decoder seek on release,
retaining the original play/pause intent. Movies retain their bounded decoder
seek cadence while their cursor updates each frame. IPTV remains unseekable;
Shorts retains its read-only progress line. No seek increment depends on a
percentage of the video's length.

## Quiet playback loading and Shorts

YouTube playback uses a black viewport with bounded existing artwork until the
current attachment renders a video frame. A small unboxed white spinner appears
after 650 ms if still waiting. Later buffering retains the video surface and
delays the spinner by 900 ms, so short stalls do not flash an indicator. There
is no Connecting/Buffering text card for YouTube. Real failures retain Retry and
Back. On-Demand and IPTV keep their existing loading presentation.

A committed Shorts queue change slides vertically in the requested direction.
The old decoder, session and background work release before the next player
starts. Its last surface can remain for a nominal 210 ms transition; there is
no second decoder, video-frame capture or transition bitmap. The next clip
prepares immediately, without waiting for the animation. If the outgoing
attachment has not produced a real frame, it skips the retained-frame animation.
Replacement, Back and backgrounding remove the old surface and reset the new
view's transform. Artwork and URL caches retain their existing bounds.
Shorts artwork occupies the centered portrait viewport rather than briefly
stretching a landscape thumbnail across the screen.

## Signed-out Home

TizenTube opens YouTube TV and uses its Home response. The native client now
does the same TV Home browse with the bootstrap's actual client context and
visitor identity. It preserves the server's shelves, order and continuation.
The previous locally seeded “Because you watched” and Music/Gaming/News fallback
shelves are removed. Local watch history remains available in Library but does
not manufacture or reorder Home recommendations.

One memory-only TV session retains at most 16 allowlisted anonymous cookies
within an 8 KiB serialized header budget. Media, tracking and suggestions do not
share that jar; Google account storage and reporting rules remain unchanged.
Stale responses cannot replace a newer visitor identity. The retained bootstrap
context is limited to 32 Ki characters, and the Home request has a bounded
deadline.

Fresh-session probes with the inspected TizenTube device profiles currently
received YouTube's sign-in/search nudge, rather than video
shelves. The app preserves that upstream result and offers Search/Retry. Using
the same feed does not guarantee identical recommendations across sessions,
locations or Google experiments, or guarantee that an unsigned visitor receives
recommendations.

## Reference boundary

The inspected TizenTubeCobalt v2.0.3 APK opens YouTube TV in Cobalt. The official
[TizenTube animation enhancement](https://github.com/reisxd/TizenTube/blob/f99945a84ff767bf14ad8c1146e8e3fd49a3d628/mods/ui/ui.js#L52-L61)
enables full/list/scroll animations conditionally; its
[configuration](https://github.com/reisxd/TizenTube/blob/f99945a84ff767bf14ad8c1146e8e3fd49a3d628/mods/config.js#L22)
does not enable that override universally. The open-source mods do not define
the base loading spinner or Shorts transition timing; YouTube TV supplies those
at runtime. The native timings above are our design choices, not a claim of
pixel-identical behavior measured in TizenTube.

Exact source/APK hashes, selected native scenarios and acceptance limitations
are recorded in the [versioned verification](../releases/0.26.0/verification.json).
Worker software rendering can verify intermediate geometry and cancellation;
it does not establish physical TCL frame pacing or its compositor's retention
of the last SurfaceView buffer after codec release.
