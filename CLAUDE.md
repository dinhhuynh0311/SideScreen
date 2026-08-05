# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Side Screen turns an Android tablet into a second macOS display. Two apps talk over one TCP
socket (default port 54321; 8888 up to 0.7.1): `MacHost/` (Swift, SwiftPM, menu-bar app)
captures a virtual display and streams H.265/H.264; `AndroidClient/` (Kotlin, Gradle) decodes to
a Surface and sends touch back.
USB mode goes over `adb reverse` (loopback); wireless mode goes over LAN with a token handshake.

## Commands

```bash
# Mac build + tests — tests need Xcode's toolchain; the default CommandLineTools
# has no XCTest and `swift test` fails with "no such module 'XCTest'".
swift build --package-path MacHost                     # debug
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --package-path MacHost
DEVELOPER_DIR=... swift test --package-path MacHost --filter CodecLimitsTests
swiftlint lint --config MacHost/.swiftlint.yml --strict # CI gate

# Android (needs JDK 17; scripts default JAVA_HOME to Android Studio's bundled JBR)
AndroidClient/gradlew -p AndroidClient assembleDebug
AndroidClient/gradlew -p AndroidClient testDebugUnitTest
AndroidClient/gradlew -p AndroidClient testDebugUnitTest --tests "com.sidescreen.app.AuthHandshakeTest"
cd AndroidClient && ktlint "app/src/main/java/**/*.kt"  # CI gate (glob is relative to AndroidClient)

# End-to-end dev loop
./scripts/dev-test.sh        # build Mac → SideScreen.app bundle → launch (keeps TCC permissions)
./scripts/install.sh         # build+install both sides onto a connected device
./scripts/setup-usb.sh       # adb reverse tcp:8888 — stale, app defaults to 54321

# Release
./scripts/bump-version.sh [major|minor|patch]   # edits VERSION only
./scripts/release.sh                            # lint, push, tag → release.yml builds artifacts
```

CI (`build-mac.yml`, `build-android.yml`) builds and lints but does **not** run tests — run them
locally before pushing.

`VERSION` is the single source of truth: `build.gradle.kts`, `build_mac.sh`, and `release.yml`
all read it at build time. Never hardcode a version elsewhere. Tags are bare (`0.11.1`, no `v`).

## Wire protocol — the part that bites

`StreamingServer.swift` (`WireMessage`) and `StreamClient.kt` (`MESSAGE_*`) must be edited
together. Both sides ship independently, so **old clients meet new hosts and vice versa**. Rules
baked into the existing types:

- An unknown message type is consumed **one byte at a time** by the other side. So a new
  client→server type must either carry no payload (types 8, 9) or have every payload byte's high
  bit set (type 11, 7-bits-per-byte encoding) — otherwise old hosts desync the stream.
- Server→client messages that old clients don't know cause a **disconnect**. Type 10
  (`codecSelected`) is therefore sent only to clients that first opted in with type 9.
- Capability messages (9 `clientAvcOnly`, 11 `clientDecoderLimits`, 12 `clientSupportsAudio`) must
  arrive **before** type 8 (`clientSupportsFrameMetadata`), which is what triggers
  `finishProtocolStartup` on the host. Type 13 (`audioFrame`) follows the type-10 rule: sent only
  to clients that opted in with type 12.
- `finishProtocolStartup` ordering is load-bearing: codec is announced, then `onCodecNegotiated`
  runs synchronously (it swaps the encoder and clamps `displayWidth/Height`), then the display
  config is sent with already-clamped dimensions.
- New clients must be sent a keyframe first — `waitingForSyncFrame` drops P-frames until an IDR.

Wireless auth (`HandshakeCodec.swift` / `AuthHandshake.kt`): `[SSWA][token 32][name_len][name]` →
`[SSWR][status]`, validated constant-time in `WirelessAuth`. Loopback connections skip auth
entirely — that's how USB mode works. Non-loopback with no active token is rejected.

## Mac architecture

`AppDelegate` is the hub: owns `VirtualDisplayManager` → `ScreenCapture` → `VideoEncoder` →
`StreamingServer`, plus settings, menu bar, and the touch→CGEvent gesture state machine
(bottom half of the file).

- `VirtualDisplayManager` uses the **private `CGVirtualDisplay` API**, bridged via
  `Sources/CGVirtualDisplayBridge.h` + `module.modulemap`. That's why `Package.swift` carries
  `unsafeFlags` — don't remove them, the build breaks.
- `ScreenCapture` uses ScreenCaptureKit with a CGDisplayStream fallback, and handles the messy
  parts: display sleep/wake restarts guarded by `streamGeneration`, an IOPM assertion to block
  idle sleep while streaming, and keyframe replay of the last cached frame when the screen is idle.
- `CodecLimits` clamps resolution for AVC-only or limited-decoder clients, aspect-preserved and
  16-px aligned.
- Logs go to `/tmp/sidescreen.log` via `debugLog()`. Android's `DiagLog` mirrors this into the
  app's private files dir (many tablets suppress logcat).

Changing capture settings generally means tearing down and rebuilding the virtual display —
check how `AppDelegate` sequences stop/start before adding a new setting.

`MacHost/CaptureTest/` and `MacHost/StreamTest/` are standalone throwaway SwiftPM packages for
poking at capture APIs and encoder behaviour in isolation; they aren't part of the app build.

## Conventions

Conventional commits (`feat:`, `fix:`, `perf:`, `chore:`), branches `feature/…`, `fix/…`,
`docs/…`. User-visible changes get a CHANGELOG entry under the version heading (Keep a Changelog
format), and protocol changes that need both apps updated must say so explicitly there — users
update the two sides at different times.
