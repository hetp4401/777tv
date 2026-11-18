# Physical-device release probe

This opt-in probe targets the signed, unminified release already installed on a
user-authorized TV. It uses a separate test package and never clears app data,
changes preferences/add-ons, provisions IPTV profiles, signs out, or submits
Like/Subscribe/playlist mutations. Normal authorized playback can update local
and Google watch history. The existing tested movie resume entry is restored.
Do not run the ordinary fixture instrumentation suite against a configured TV.

Public YouTube and configured on-demand services are real network dependencies.
Only the explicitly labeled live-media stage uses generated HLS; it does not
establish real Stalker authentication or channel playback. Google owner approval
and actual phone pairing need separate user-led checks. Screenshots and raw
reports can contain private viewing information: keep them out of Git and public
reports.

From the project root, after sourcing `scripts/android-env.sh`, build only the
probe. The existing release and test APK must have the same signing certificate:

```sh
./gradlew -I scripts/device_probe/probe.init.gradle :app:assembleReleaseAndroidTest
adb -s "$DEVICE_SERIAL" install -r -t app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
```

For the controlled live stage, start the loopback server in another terminal
(FFmpeg is needed to generate media), then forward its port:

```sh
python3 scripts/device_probe/live_fixture.py
adb -s "$DEVICE_SERIAL" reverse tcp:8893 tcp:8893
```

Explicitly opt in and select the probe class. An optional single-word stage
prefix, such as `-e stage theme`, `-e stage episodes` or `-e stage regular`, limits
the workload. The theme stage uses actual picker selections, checks all ten
presets and three independent sections, then restores the original preferences:

```sh
adb -s "$DEVICE_SERIAL" shell am instrument -w -r -e physicalTv true \
  -e class xyz.billybishop4.fleet.tv.TclDeviceExperienceProbe \
  xyz.billybishop4.fleet.tv.deviceprobe/androidx.test.runner.AndroidJUnitRunner
```

The report is written after each stage to the target app's external files as
`tcl021-device-probe.json`; screenshots use the `777tv-tcl021-` prefix in Downloads.
Verify the report's installed version and selected stage before importing it;
a failed instrumentation launch can leave a previous report. The protocol
records final-source playback, source alternatives and errors rather than
silently treating a stale report or failed link as a pass.

Startup records first rendered video frame and active playback separately;
neither measures audible-audio latency. FrameMetrics uses each frame's actual
deadline and records callback losses, not a nominal 16.7 ms threshold as a jank
count. It does not measure displayed video FPS or physical remote latency.
Steady-state video counters exclude startup/seek transitions. Live window
positions can reset as the playlist advances. PSS includes shared pages and can
fluctuate; explicitly forced-GC samples are labeled separately. Cache and encoded
buffer limits are not whole-process memory limits.

Stop the local media server and remove only the temporary test package and
forwarding when finished; leave the release and its data installed:

```sh
adb -s "$DEVICE_SERIAL" reverse --remove tcp:8893
adb -s "$DEVICE_SERIAL" uninstall xyz.billybishop4.fleet.tv.deviceprobe
```
