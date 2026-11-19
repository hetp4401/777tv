# YouTube player review, 0.24.0

This change applies to the YouTube player inside 777tv, including YouTube live
streams. IPTV Live TV and On-Demand keep their existing controls, layout and
preferences. Shorts keep their portrait presentation and direct clip navigation.

The layout follows the current [official YouTube TV player
demonstration](https://www.youtube.com/watch?v=u2YVh9h_ipo) and
[announcement](https://support.google.com/youtube/thread/394085818?hl=en),
cross-checked against the saved TV response and
[TizenTube's UI modifications](https://github.com/reisxd/TizenTube/blob/main/mods/ui/customUI.js).
777tv continues to render native Android views around ExoPlayer; it does not load
the YouTube TV website or claim identical Google service behavior.

## Playback and remote behavior

- A non-clickable title and metadata sit at the top. The thin red timeline spans
  the width above three control groups: channel/Description/Subscribe on the
  left, Previous/Play/Next in the center, and Like/Dislike/Comments/Save/CC/Settings
  on the right. The Play button stays centered when Previous is unavailable.
- White circular focus and a short action label keep selection clear. Up reaches
  the timeline; Down opens Related. Back cancels a seek preview, then hides the
  controls, then leaves playback. Controls auto-hide while video is playing.
- Holding Left/Right accelerates the visible cursor. It temporarily pauses
  playback and commits one decoder seek on release, preserving the original
  play/pause intention. Explicit media keys and newer phone commands supersede
  an unfinished gesture. Canceled gestures and closing playback discard it.
- Seek previews use actual YouTube storyboard contact sheets when the playback
  response supplies them. Time and chapter text remain available without an
  image. Contact sheets load only while seeking, reuse the bounded artwork cache,
  and crop with a display matrix instead of allocating per-frame bitmaps.
- CC toggles the actual default/last subtitle track. Settings contains quality,
  audio, subtitle language, speed and Autoplay. YouTube caption and speed choices
  are stored separately from On-Demand preferences. Picture controls are absent.
- Description and Comments use the existing side panel. Related retains its
  horizontal shelf. Description, channel metadata, chapters and recommendations
  share the post-frame watch-next request. No extra player or startup RPC is added.
- YouTube live streams use these controls and actual ExoPlayer seekability.
  Non-DVR streams disable the timeline. IPTV still has its non-pausable live
  facade and transient channel/guide overlay without transport controls.

## Account state and resource use

Direct actions reuse the existing account-scoped mutation controller. Buttons
change their selected state after server confirmation; failed writes retain the
previous state and can be retried. Restored credentials, account changes,
same-account notifications and late metadata cannot overwrite acknowledged
actions. Save opens the playlist chooser directly. Channel is enabled only when
valid channel metadata is available. Authentication and playlist windows are
allocated only when explicitly opened.

The player keeps one hardware-decoded ExoPlayer and its existing SurfaceView.
There is no extra video decoder, browser runtime, picture-processing pass or
animated background. Preview work is debounced, limited to one active sheet
request, and canceled on hide/close. Sprite and avatar drawable references are
released when their owner closes.

The broader integration and its service/account limitations remain documented
in the [0.23 parity review](youtube-parity-0.23.md). Test counts, source hashes,
APK identity and the actual device-verification scope are recorded in the
versioned release verification rather than inferred from this design review.
