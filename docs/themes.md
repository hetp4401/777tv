# App and section themes

Classic preserves the existing black-and-white appearance and remains the default
for new installs and upgrades. Settings → Appearance offers an **App theme** and
independent **On-Demand theme**, **YouTube theme** and **Live TV theme** overrides.
“Use app theme” is separate from explicitly selecting Classic: inherited sections
follow later app-theme changes, while explicit choices remain independent.
Settings and configuration pages use the app palette. Content pages, search,
details, sources and players use their area's palette; YouTube livestreams retain
the YouTube palette. Media images, video output, subtitles, QR pixels and semantic
LIVE/error indicators keep their original meaning.

| Preset | Pairing | Default backdrop |
| --- | --- | --- |
| Classic | Ink and silver | Solid |
| Cinema | Black with violet-blue accents | Solid |
| Tube | Black, white and red | Solid |
| Aurora | Charcoal teal and mint | Subtle dark gradient |
| Midnight | Blue and indigo | Subtle dark gradient |
| Ocean | Cyan and sea glass | Subtle dark gradient |
| Rose | Blush and plum | Subtle dark gradient |
| Ember | Apricot and burgundy | Subtle dark gradient |
| Sand | Champagne and sage | Solid |
| Moss | Olive and forest | Solid |

Each section's picker places its recommended preset first: Cinema for On-Demand,
Tube for YouTube and Aurora for Live TV. Focus previews a swatch without changing
preferences. OK applies once; a check marks the saved choice and the strongest
outline marks remote focus. **Background → Theme default** follows each preset's
solid or gradient design; **Solid** disables gradients while retaining coordinated
surface, text, selection and control accents.

The design review retains consistent rounded shapes and type hierarchy. Tube uses
neutral white focus outlines and saturated red selection/progress accents; its
selected surface is dark enough for readable red labels. Aurora's restrained
charcoal/teal background keeps the channel preview dominant. Native dialogs and
selection controls share the chosen palette. Contrast checks cover ordinary and
secondary text, focused/pressed controls, selection labels and accent-filled
buttons across all presets.

The implementation shares immutable palette data, creates static native drawables
on theme/area changes, and rebuilds visible UI with its navigation/focus state
preserved. It clears old colored recycled views, retains the shared artwork cache,
and keeps any active decoder and playback position. No wallpaper bitmaps, blur,
background animation, web UI or new rendering library is added. The existing
artwork and encoded-video buffer budgets are unchanged.

[Release verification](../releases/0.22.1/verification.json) separates automated
fixture scenarios from opt-in signed-release device checks. Gallery captures use
generated content and an unsaved fixture portal; no IPTV profile ships with the
app. Private TCL captures and raw device reports stay outside Git.
