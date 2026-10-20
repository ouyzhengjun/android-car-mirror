# Task 2 implementation report

Status: DONE_WITH_CONCERNS

## Implementation

- Added `AdbGateway`, `AdbChannel`, `CommandResult`, connection states, asynchronous session connection/replacement/disconnection and main-thread state callbacks.
- Added app-private persistent RSA-2048 PKCS#8 key and X.509 certificate, generated with Bouncy Castle. Existing identities load on subsequent sessions.
- Wrapped libadb-android 3.1.1, added Conscrypt TLS support and matching Bouncy Castle dependencies, JitPack repository and AndroidX flag.
- Gateway supports short interactive `shell:`, sync SEND/DATA/DONE push and `localabstract:` streams. Owned channels close idempotently and unregister on close; disconnect and command read timeout close streams.
- Arbitrary shell command text travels over the stream, never in the A_OPEN destination. Service names are ASCII only and capped at 96 UTF-8 bytes.
- MainActivity now connects, displays authorization guidance and state/errors, disconnects, executes commands off the UI thread and shows command output/exit status/timeout. Supports `adb shell ...`, `adb devices`, `adb connect [IPv4:port]`, `adb disconnect`, plus direct shell command text.

## RED / GREEN evidence

- `/tmp/task2-red.log`: requested focused test command initially failed at compile because AdbSession/AdbChannel/ConnectionState/CommandResult did not yet exist (21 missing symbol errors). This establishes the missing API rather than a runtime assertion RED.
- `/tmp/task2-red-stream.log`: after the initial implementation, `commandStreamFailureReportsError` failed with an assertion: stream errors were thrown to the command caller but did not update the session listener. Fixed session error dispatch and transport/channel cleanup.
- `/tmp/task2-red-service.log`: `rejectsMultibyteServiceNamesBeforeCallingLibrary` failed before the ASCII destination guard. Added the guard and reran.
- `/tmp/task2-red-open.log`: `commandTimeoutIncludesServiceOpen` failed with a Future timeout before moving service opening inside the bounded command job. It passes after the fix.
- Additional persistence coverage was added after implementation; it is supplementary coverage, not claimed as initial TDD evidence.
- Initial dependency build failed because AndroidX was not enabled, then because different BC artifact families duplicated classes. Enabled AndroidX and aligned bcpkix with libadb's bcprov-jdk15to18:1.81. All resolved failures were rerun.

## Verification

- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_HOME=/Users/ouyangzhenjun/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug`: BUILD SUCCESSFUL, 39 actionable tasks (see final run log for execution/cache split), final run `/tmp/task2-final.log`.
- ADB tests: 10 passing, covering authorization state/replacement, short shell/exit code, read timeout closure, disconnect closure, failed authorization cleanup, command failure listener state, multibyte service rejection, sync framing and credential persistence.
- Full unit suite: 15 tests passed (10 AdbSessionTest + 5 EndpointTest), zero failures/errors/skips. XML evidence is in app/build/test-results/testDebugUnitTest/.
- `git diff --check`: clean. Java formatted with google-java-format 1.22.0.
- Build warning: native libraries libconscrypt_jni.so and libspake2.so could not be stripped and are packaged intact; build succeeds.

## Self-review and concerns

1. libadb's public constructor creates `new Socket(host, port)` without an injectable preconnected socket. While authorization uses a 30-second timeout, initial TCP connect follows the OS timeout and cannot be strictly canceled before the library constructor returns. User can disconnect/retry immediately; stale eventual transports close and cannot replace the new session. An unreachable address can retain a worker until the OS connect completes. This is a real limitation, not a claimed strict connect timeout.
2. Shell timeout includes service opening and command I/O; timeout cancels the service-open worker and closes any acquired channel, including one arriving after cancellation.
3. Source-level correction to the earlier ruling: `AdbProtocol.generateOpen` allocates `destination.length()+1` bytes then encodes UTF-8. The demonstrated allocation defect is multibyte text, not long ASCII by itself. The implementation nevertheless keeps fixed services bounded and routes all command text through short `shell:`.
4. No real car/device authorization, shell echo behavior, transfer or reconnect was exercised in this task; Task 7 must validate against hardware. API-21 runtime TLS/native library loading also awaits device validation.
5. Connection state detects command/open failures; an idle peer disconnect is observed on the next service/command operation rather than by continuous polling. No polling was added.

## Changed files

- app/build.gradle
- settings.gradle
- gradle.properties
- app/src/main/java/com/example/carmirror/MainActivity.java
- app/src/main/java/com/example/carmirror/adb/{AdbGateway,AdbChannel,CommandResult,ConnectionState,AdbSession,AdbKeyStore,LibAdbTransport}.java
- app/src/test/java/com/example/carmirror/adb/AdbSessionTest.java
- this report
