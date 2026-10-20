# SDD ledger — plan: docs/superpowers/plans/2026-10-20-android-car-mirror.md

Worktree: /Users/ouyangzhenjun/project/AI_Chatgtp/android-car-mirror/.worktrees/implementation
Starting commit: f90f917
Baseline: no application code or tests yet; documentation-only repository.

## Preflight task consistency

| Task(s) | Produce / consume or internal check | Finding |
| --- | --- | --- |
| 1 | Endpoint parser, MainActivity, Android build against specified tests | Consistent; the build scaffold belongs to the first testable unit. |
| 2 | AdbGateway consumes Endpoint and extends MainActivity | Consistent; MainActivity shared with Task 1. |
| 3 | ScrcpySession consumes AdbGateway.openService and push | Consistent; depends on Task 2's AdbChannel. |
| 4 | VideoPacketReader/Decoder produce video geometry | Consistent with Task 3's video channel. |
| 5 | TouchController consumes VideoGeometry and control output | Consistent with Tasks 3 and 4. |
| 6 | MirrorActivity consumes ScrcpySession, VideoDecoder, TouchController | Consistent; AndroidManifest shared with Task 1. |
| 7 | Device validation consumes completed app and may modify any file after verified failures | Consistent; docs and APK acceptance are explicit. |
| 1 ↔ 2 | Endpoint and MainActivity | Interface `Endpoint.parse` and UI connection action carry forward. |
| 1 ↔ 6 | AndroidManifest | Task 6 adds projection Activity; no conflicting permission requirement. |
| 2 ↔ 3 | AdbGateway and AdbChannel | Push and localabstract streams cover server startup. |
| 3 ↔ 4 | video AdbChannel | `InputStream` powers packet reader. |
| 3 ↔ 5 | control AdbChannel | `OutputStream` powers control writer. |
| 4 ↔ 5 | VideoGeometry | Geometry carries content rectangle and source size. |
| 3–5 ↔ 6 | ScrcpySession, VideoDecoder, TouchController | MirrorActivity owns their lifecycle. |
| 1–6 ↔ 7 | APK and true device test | Task 7 verifies the integrated result. |

Ruling: Worktree created manually because the Codex native worktree tool returned `Not a git repository` for this chat's outer directory — nested Android Git repository uses `.worktrees/implementation` — if the app later attaches this nested project, worktree management must remain manual.

Task 1: complete (commits f90f917..7793bae, review clean). Controller independently ran `./gradlew testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL, 37 tasks up-to-date.

Ruling: Task 2 must avoid constructing long `shell:<command>` service strings with `libadb-android:3.1.1` because its documented `A_OPEN` allocation bug affects destinations around 104 bytes; use a short `shell:` interactive service for arbitrary user commands and cap/validate any fixed service destination — if this diagnosis is wrong, the command channel may still fail on long or multibyte input and will need a later fix.

Ruling: Task 2 source inspection narrowed the `A_OPEN` allocation bug to multibyte destination text, rather than long ASCII alone; keep ASCII-only bounded service names and stream arbitrary shell text through `shell:` — if this is wrong, service opening may fail on a different input class and needs a transport patch.

Task 2: fix round 1/5 (3 addressed, 0 open — 4096-byte underlying writes, sync FAIL/large-payload coverage, scratch report untracked; commits dfedf20..a4d3d43).
Task 2: complete (commits 7793bae..a4d3d43, review clean). Controller independently ran `./gradlew testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL, 39 tasks up-to-date.

Ruling: Task 3 may make a narrow AdbSession change so a `localabstract:scrcpy_` socket refusal during server startup does not destroy the shared ADB transport; Task 3 must still time out and report a failed scrcpy session, and other ADB service failures retain their error handling — if the refusal is actually a dead transport, retry may run until its startup deadline before reporting the failure.

Task 3: complete (commits a4d3d43..0e0cac0, review clean). Controller independently ran `./gradlew testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL, 39 tasks up-to-date.
Task 3: deferred minor — uploaded per-session server JAR can remain when push succeeds but process launch does not; test against device during Task 7 and avoid deleting unrelated remote files.

Task 4: fix round 1/5 (1 addressed, 0 open — AVC SPS/PPS split into csd-0/csd-1; commits 7b7afbe..8a5a05b).
Task 4: complete (commits 0e0cac0..8a5a05b, review clean). Controller independently ran `./gradlew testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL, 39 tasks up-to-date.

Task 5: fix round 1/5 (1 addressed, 0 open — release active pointers with current dimensions after rotation; commits 1844caf..0a13faa).
Task 5: complete (commits 8a5a05b..0a13faa, review clean). Controller independently ran `./gradlew testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL, 39 tasks up-to-date.

Task 6: review fixes applied — explicit disconnect now clears the shared connection state and device-page status immediately; projection stream failures probe the ADB transport before changing the shared state, while decoder failures leave ADB state intact; `onPause` detaches the decoder from the Surface and `onResume` reattaches when valid. Added three connection-owner tests covering explicit disconnect, broken transport, and healthy transport. `./gradlew testDebugUnitTest assembleDebug --offline`: BUILD SUCCESSFUL, 56 unit tests. Final Task 6 review remains with the controller.
