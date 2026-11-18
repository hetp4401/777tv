# Remote Android TV development

The `fleet-tv-dev` Fleet provider app has three private Linux development
replicas. Existing Shellboxes remain separate. Each development container has
two CPUs, a 7 GiB memory limit and access to `/dev/kvm` for its Android TV
emulator. The workflow below runs compilation, lint, unit tests, fixture
generation and emulator tests remotely, with local APK signing. Local Mac
development/builds/tests remain authorized. The latest instruction holds TCL
testing and deployment; these workers only run generated-media emulator checks.

Run from the project root using Node 18+ and Python 3.11+:

```sh
./scripts/remote.sh doctor
./scripts/remote.sh pipeline --sign
```

The pipeline takes a small source snapshot and runs these jobs concurrently:

| Replica | Job | Result |
| --- | --- | --- |
| 1 | `build` | ARM64/ARMv7 release APK, signed locally with the existing development key |
| 2 | `unit` | JVM unit tests and Android lint |
| 3 | `ui` | KVM Android TV API 36 x86_64 emulator, ExoPlayer and generated fixtures |

Individual jobs can use any replica:

```sh
./scripts/remote.sh build --worker 2 --sign
./scripts/remote.sh unit --worker 1
./scripts/remote.sh ui --worker 3
./scripts/remote.sh ui --worker 3 --classes xyz.billybishop4.fleet.tv.LocalPerformanceTest
./scripts/remote.sh status JOB_ID
./scripts/remote.sh collect JOB_ID --sign
```

Default UI coverage includes navigation, settings/profiles, Stalker fixture
playback, ExoPlayer, preview/fullscreen layout, live controls,
failure recovery, category visibility, memory-pressure recovery, history-cache
consistency, Unicode rows and logo recycling, Search/IME focus, compact horizontal
sources, add-on installation/configuration/recovery, series resume and native
on-demand controls (current count is recorded in the versioned verification report). Separate performance workloads cover scrolling
and repeated tuning with 6,000 generated channels. Performance samples on a remote
software-rendered emulator measure that environment; they do not predict the
TCL TV's frame rate.

Commands use Node's native `fetch`; no browser is needed. Receipts and logs are
saved under `.tools/shellbox-remote/`. Downloads, checksummed reports and APKs
are collected under `artifacts/remote/JOB_ID/`. UI APKs stay on the workers.
Small source snapshots are retained locally by digest in
`.tools/shellbox-remote/sources/`; concurrent clients snapshot into separate files.
`--sign` briefly runs the Mac's `apksigner` and verifies the signature. It does
not launch Gradle or an emulator locally.

Accepted jobs run independently of the client. If the Mac disconnects, check
the saved job ID with `status` and retrieve its artifacts with `collect`.
While a client is watching, loss of a worker causes one retry on another
replica. Build/test failures are reported as failures. Each worker serializes
its jobs and cleans up its emulator and fixture server when tests finish.

The source snapshot excludes `.tools`, artifacts, imported APKs, prebuilt
native libraries, keystores and `.env`. The workers have no production IPTV
credentials and only play generated test media. They never connect to the
physical TCL TV.

Worker URLs and their bearer token live in
`.tools/shellbox-remote/worker-access.json` (mode 0600), or a file selected by
`FLEET_REMOTE_CONFIG`. Keep this file private. Source upload, job submission,
status and artifact downloads all require the token. Fleet app changes use a
separate deployment password; mapping KVM initially requires Fleet owner
authorization. The container has no Docker socket, drops Linux capabilities,
and sets `no-new-privileges`.

`compose.yaml`, `Dockerfile` and `server.py` describe the deployed worker app.
The server executes the uploaded `remote/job.py`, so changes to job steps only
need a fresh source sync. A server or image change requires updating the Fleet
app, which interrupts active jobs. `/work` and `/cache` use named volumes;
these survive container restarts on the same host but can disappear when Fleet
replaces the host. The Mac source and downloaded results remain the durable
copies. Missing SDK, JDK, Gradle and emulator caches are
rebuilt automatically. No persistent source backup depends on a worker.

## Recorded verification

Current production is 777tv 0.14.1, using ExoPlayer only. The current source/APK
checksums and focused regression results are in
[`releases/0.14.1/production.json`](../releases/0.14.1/production.json).
The following measurements describe earlier development builds; the artifact
paths refer to local, untracked evidence.

The first remote pipeline passed the ARM release build, all 35 JVM tests,
Android lint and all 24 default Android TV scenarios. A second replica also
passed the 6,000-channel performance scenario with eight mpv channel changes;
five channel rows were attached. The APK was signed on the Mac, verified with
the existing signing certificate and checked for 16 KiB ZIP alignment.
Job IDs, checksums and report paths are in
[`artifacts/verification-remote-development.json`](../artifacts/verification-remote-development.json).

The performance sample used a two-core x86_64 emulator with SwiftShader.
App PSS was approximately 136 MiB before channel changes, 118 MiB afterward
and 110 MiB after playback stopped. Its frame-jank reading was 86.56%; software
graphics on these workers limits animation measurement. Physical TV smoothness
remains unverified. No TCL connection or production IPTV stream was used.

Unauthenticated job submission, incorrect source checksums and unknown source
IDs were rejected. Artifact collection was repeated successfully from a saved
job receipt. All three replicas reported working KVM access. Automatic retry
after host replacement is implemented but has not been verified by destroying
a running worker.

For current before/after UI performance and memory measurements, see
[`REVIEW.md`](../REVIEW.md) and `artifacts/verification-0.8.0.json`.
The 0.9.0 UI/navigation validation is in `artifacts/verification-0.9.0.json`.
The matched benchmark command is:

```sh
./scripts/remote.sh ui --worker 1 --classes xyz.billybishop4.fleet.tv.UiPerformanceTest,xyz.billybishop4.fleet.tv.LocalPerformanceTest
```
