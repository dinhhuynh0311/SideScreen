# MatePad Pro 12.2" Profile + Audio Passthrough Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Huawei MatePad Pro 12.2" a first-class SideScreen target (native resolution preset, panel-matched refresh rate, measured frame-rate ceiling) and stream Mac system audio to the tablet so it works as an audio output device.

**Architecture:** The Mac host captures system audio through ScreenCaptureKit's existing audio tap (`SCStreamConfiguration.capturesAudio`, already present but set to `false`), converts planar Float32 to interleaved Int16, and sends it over the *existing* video TCP connection using two new message types that follow the protocol's established capability-opt-in convention. The Mac's default output device is muted while streaming so the tablet behaves like a real output device, with no CoreAudio driver or installer. The Android client plays the PCM straight into an `AudioTrack` whose own buffer serves as the jitter buffer.

**Tech Stack:** Swift 5.9 / SwiftUI / ScreenCaptureKit / Network.framework / CoreMedia (macOS host); Kotlin / AudioTrack / MediaCodec (Android client); XCTest and JUnit4 for tests.

**Spec:** `docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md`

## Global Constraints

- macOS floor is **13.0** (`MacHost/Package.swift`) — `SCStreamConfiguration.capturesAudio` is a 13.0 API and is already the stated reason for that floor. Do not raise the floor.
- Android `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`, `jvmTarget = "11"` (`AndroidClient/app/build.gradle.kts`). `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY` is API 26 — exactly at the floor, no guard needed.
- PCM wire format is fixed: **48000 Hz, 2 channels, signed 16-bit, interleaved, little-endian**.
- New wire message types are **12** (`clientSupportsAudio`, client→server, payload-free) and **13** (`audioFrame`, server→client, `[length: 4 BE][pcm]`). Type 13 must be sent **only** to clients that sent type 12, because old clients disconnect on unknown message types.
- Client-side capability messages (types 9, 11, and the new 12) MUST be sent **before** type 8, which can trigger the server's early protocol finish.
- The Mac app is **not** sandboxed (`MacHost/SideScreen.entitlements`), so `Process`/`osascript` and CoreAudio need no extra entitlement.
- The MatePad panel is **2800×1840** (measured via `adb shell wm size`), not the 2800×1864 published spec. Refresh modes are 60/120/144 Hz.
- Swift lint config is `MacHost/.swiftlint.yml`; Kotlin uses ktlint via `AndroidClient/.editorconfig` (trailing commas expected in multi-line argument lists).
- Existing `debugLog(...)` is the host logging function; `Log.i/e(TAG, ...)` and `diagLog(...)` are the client's.

---

### Task 1: MatePad display profile — preset + 144 Hz

Adds the panel-native resolution as a real preset (it currently only exists as a hand-typed custom value) and exposes the panel's 144 Hz mode. Logical `1400x920` with HiDPI doubles to exactly 2800×1840, so nothing resamples anywhere in the pipeline.

**Files:**
- Modify: `MacHost/Sources/SettingsWindow.swift:429` (frame-rate options), `MacHost/Sources/SettingsWindow.swift:1254-1272` (`resolutionGroups`)
- Test: `MacHost/Tests/SideScreenTests/MatePadPresetTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces: preset string `"1400x920"` present in `DisplaySettings.resolutionGroups` under group name `"MatePad Pro 12.2"`, and `144` present in the frame-rate button row. No new symbols.

- [ ] **Step 1: Write the failing test**

Create `MacHost/Tests/SideScreenTests/MatePadPresetTests.swift`:

```swift
import XCTest
@testable import SideScreen

final class MatePadPresetTests: XCTestCase {
    /// Guards against a typo in the preset (e.g. the published-spec 1864 or a
    /// transposed digit). The MRDI-W09 panel is 2800x1840 and HiDPI doubles the
    /// logical size, so an exact 2x is what avoids resampling.
    func testMatePadPresetDoublesToPanelResolution() throws {
        let group = DisplaySettings.resolutionGroups.first { $0.name == "MatePad Pro 12.2" }
        let logical = try XCTUnwrap(group?.resolutions.first, "MatePad preset group missing")
        let parts = logical.split(separator: "x").compactMap { Int($0) }
        XCTAssertEqual(parts.count, 2, "preset must be WIDTHxHEIGHT")
        XCTAssertEqual([parts[0] * 2, parts[1] * 2], [2800, 1840])
    }

    func testMatePadPresetIsReachableFromAllResolutions() {
        XCTAssertTrue(DisplaySettings.allResolutions.contains("1400x920"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MacHost && swift test --filter MatePadPresetTests`
Expected: FAIL — `XCTUnwrap` throws "MatePad preset group missing", because no such group exists yet.

- [ ] **Step 3: Add the preset group**

In `MacHost/Sources/SettingsWindow.swift`, inside the `static let resolutionGroups: [ResolutionGroup] = [` array, add this as the **first** entry so it appears at the top of the list:

```swift
        // Panel-native for the MatePad Pro 12.2" (MRDI-W09): 2800x1840 measured,
        // not the 2800x1864 published spec. HiDPI doubles this logical size to
        // land exactly on the panel, so nothing resamples.
        ResolutionGroup(name: "MatePad Pro 12.2", ratio: "HiDPI 2800×1840", resolutions: [
            "1400x920"
        ]),
```

- [ ] **Step 4: Add 144 Hz to the frame-rate options**

In `MacHost/Sources/SettingsWindow.swift:429`, change:

```swift
                                    ForEach([30, 60, 90, 120], id: \.self) { rate in
```

to:

```swift
                                    ForEach([30, 60, 90, 120, 144], id: \.self) { rate in
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd MacHost && swift test --filter MatePadPresetTests`
Expected: PASS, 2 tests.

- [ ] **Step 6: Build to confirm the SwiftUI change compiles**

Run: `cd MacHost && swift build 2>&1 | tail -5`
Expected: `Build complete!` with no errors.

- [ ] **Step 7: Commit**

```bash
git add MacHost/Sources/SettingsWindow.swift MacHost/Tests/SideScreenTests/MatePadPresetTests.swift
git commit -m "feat(mac): MatePad Pro 12.2 native preset + 144 Hz option

Logical 1400x920 with HiDPI doubles to exactly 2800x1840, the measured
MRDI-W09 panel size, so no stage of the pipeline resamples. The panel also
runs 144 Hz, which was missing from the frame-rate options.

Test guards the preset against a typo (the published spec says 2800x1864;
the panel reports 1840)."
```

---

### Task 2: Spike — does the SCK audio tap survive output-device mute?

The entire "Mac goes silent, tablet is the speaker" behaviour rests on one unverified assumption: that ScreenCaptureKit's audio tap sits *ahead* of the hardware output device, so muting the device does not silence the tap. This task answers that before any production code depends on it.

This is a throwaway spike. Nothing here gets committed to the repo.

**Files:**
- Create (scratchpad, not the repo): `/private/tmp/claude-501/-Users-huynhquocdinh-dev-personal-SideScreen/4c03b6cf-0d43-4cf8-84fc-a993d06883f2/scratchpad/audiospike/main.swift` and `Package.swift`
- Modify: `docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md` (record the finding)

**Interfaces:**
- Consumes: nothing.
- Produces: a recorded yes/no that sets the default value of `muteMacWhileStreaming` in Task 6. No code.

- [ ] **Step 1: Create the spike package**

```bash
SPIKE=/private/tmp/claude-501/-Users-huynhquocdinh-dev-personal-SideScreen/4c03b6cf-0d43-4cf8-84fc-a993d06883f2/scratchpad/audiospike
mkdir -p "$SPIKE/Sources/audiospike"
cat > "$SPIKE/Package.swift" <<'EOF'
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "audiospike",
    platforms: [.macOS(.v13)],
    targets: [.executableTarget(name: "audiospike", path: "Sources/audiospike")]
)
EOF
```

- [ ] **Step 2: Write the spike**

Create `$SPIKE/Sources/audiospike/main.swift`:

```swift
import Foundation
import ScreenCaptureKit
import CoreMedia
import AVFoundation

// Prints the RMS of captured system audio once per second. Run it while sound
// is playing, then mute the Mac and watch whether RMS collapses to zero.
final class Probe: NSObject, SCStreamOutput, SCStreamDelegate {
    private var sum: Double = 0
    private var count: Int = 0

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio else { return }
        var size = 0
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: &size, bufferListOut: nil, bufferListSize: 0,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0,
            blockBufferOut: nil) == noErr else { return }
        let raw = UnsafeMutableRawPointer.allocate(
            byteCount: size, alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        let abl = raw.assumingMemoryBound(to: AudioBufferList.self)
        var block: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sb, bufferListSizeNeededOut: nil, bufferListOut: abl, bufferListSize: size,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &block) == noErr else { return }
        for buf in UnsafeMutableAudioBufferListPointer(abl) {
            guard let data = buf.mData else { continue }
            let n = Int(buf.mDataByteSize) / MemoryLayout<Float>.size
            let p = data.assumingMemoryBound(to: Float.self)
            for i in 0..<n { sum += Double(p[i] * p[i]); count += 1 }
        }
    }

    func report() {
        let rms = count > 0 ? (sum / Double(count)).squareRoot() : 0
        let muted = (try? shell("get volume settings")) ?? "?"
        print(String(format: "RMS %.6f  samples %d  | %@", rms, count, muted.trimmingCharacters(in: .whitespacesAndNewlines)))
        sum = 0; count = 0
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        print("stream stopped: \(error)")
    }
}

func shell(_ script: String) throws -> String {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    p.arguments = ["-e", script]
    let pipe = Pipe()
    p.standardOutput = pipe
    try p.run()
    let d = pipe.fileHandleForReading.readDataToEndOfFile()
    p.waitUntilExit()
    return String(data: d, encoding: .utf8) ?? ""
}

let probe = Probe()
Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let display = content.displays.first else { fatalError("no display") }
        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.sampleRate = 48000
        config.channelCount = 2
        config.excludesCurrentProcessAudio = true
        config.width = 640
        config.height = 480
        config.minimumFrameInterval = CMTime(value: 1, timescale: 5)
        let stream = SCStream(
            filter: SCContentFilter(display: display, excludingWindows: []),
            configuration: config, delegate: probe)
        try stream.addStreamOutput(probe, type: .audio, sampleHandlerQueue: .global())
        try stream.addStreamOutput(probe, type: .screen, sampleHandlerQueue: .global())
        try await stream.startCapture()
        print("capturing — play audio now")
    } catch {
        print("setup failed: \(error)"); exit(1)
    }
}
Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in probe.report() }
RunLoop.main.run()
```

- [ ] **Step 3: Start looping audio, then run the spike unmuted**

In one shell, start continuous sound:

```bash
while :; do afplay /System/Library/Sounds/Submarine.aiff; done
```

In another, unmute and run the probe for ~8 seconds:

```bash
osascript -e 'set volume output muted false'
cd "$SPIKE" && timeout 8 swift run audiospike
```

Expected: `output muted:false` and **non-zero** RMS (roughly 0.001-0.2 depending on volume). If RMS is 0.000000 here, audio is not reaching the tap at all — check System Settings → Privacy & Security → Screen Recording for the terminal, and that the `afplay` loop is audible.

- [ ] **Step 4: Run the spike muted — this is the actual question**

```bash
cd "$SPIKE" && (sleep 4; osascript -e 'set volume output muted true') & timeout 9 swift run audiospike; osascript -e 'set volume output muted false'
```

Expected if the assumption holds: RMS stays non-zero after the `output muted:true` line appears.
Expected if it does not: RMS collapses to `0.000000` once muted.

- [ ] **Step 5: Record the finding in the spec**

Edit the "Sequenced first: the mute/tap spike" section of `docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md`, replacing the paragraph beginning "Before any of Part C is built" with the measured outcome. Use one of these two, verbatim, matching what you observed:

If the tap survived mute:

```markdown
**Spike result (verified):** The SCK audio tap continues to deliver non-silent
samples while the default output device is muted — RMS stayed non-zero after
`set volume output muted true`. Auto-mute is therefore viable, and
`muteMacWhileStreaming` defaults to **on**.
```

If the tap went silent:

```markdown
**Spike result (verified):** The SCK audio tap goes silent when the default
output device is muted — RMS collapsed to zero after
`set volume output muted true`. Auto-mute is NOT viable. Ship mirror-only:
audio plays on both Mac and tablet, `muteMacWhileStreaming` defaults to
**off** and is a manual control the user drives knowing it silences the
tablet too.
```

- [ ] **Step 6: Clean up the spike and commit the finding**

```bash
rm -rf "$SPIKE"
git add docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md
git commit -m "docs: record SCK audio tap vs output-device mute spike result"
```

---

### Task 3: PCM conversion and audio packet framing (pure functions)

The two pieces of audio logic worth testing, isolated from CoreMedia and Network.framework so they can be unit-tested: planar Float32 → interleaved Int16 conversion, and the length-prefixed wire packet.

**Files:**
- Create: `MacHost/Sources/AudioPCM.swift`
- Test: `MacHost/Tests/SideScreenTests/AudioPCMTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `AudioPCM.interleaveInt16(channels: [[Float]]) -> Data` — clamps to [-1, 1], little-endian Int16, ragged input truncated to the shortest channel.
  - `AudioPCM.audioPacket(messageType: UInt8, pcm: Data) -> Data` — `[type][Int32 big-endian length][pcm]`.

- [ ] **Step 1: Write the failing tests**

Create `MacHost/Tests/SideScreenTests/AudioPCMTests.swift`:

```swift
import XCTest
@testable import SideScreen

final class AudioPCMTests: XCTestCase {
    func testInterleavesChannelsInOrder() {
        // L = [0, 1.0], R = [-1.0, 0]  ->  L0 R0 L1 R1
        // 0 = 0x0000, -32767 = 0x8001, 32767 = 0x7FFF, little-endian.
        let d = AudioPCM.interleaveInt16(channels: [[0, 1.0], [-1.0, 0]])
        XCTAssertEqual(Array(d), [0x00, 0x00, 0x01, 0x80, 0xFF, 0x7F, 0x00, 0x00])
    }

    func testClampsSamplesOutsideUnitRange() {
        // Without clamping these would wrap and produce audible clicks.
        let d = AudioPCM.interleaveInt16(channels: [[1.5, -1.5]])
        XCTAssertEqual(Array(d), [0xFF, 0x7F, 0x01, 0x80])
    }

    func testRaggedChannelsTruncateToShortest() {
        // 1 frame x 2 channels x 2 bytes. Reading past the short channel
        // would be an out-of-bounds crash.
        let d = AudioPCM.interleaveInt16(channels: [[0, 0, 0], [0]])
        XCTAssertEqual(d.count, 4)
    }

    func testNoChannelsProducesNoData() {
        XCTAssertTrue(AudioPCM.interleaveInt16(channels: []).isEmpty)
    }

    func testMonoIsPassedThroughUninterleaved() {
        let d = AudioPCM.interleaveInt16(channels: [[1.0, 0]])
        XCTAssertEqual(Array(d), [0xFF, 0x7F, 0x00, 0x00])
    }

    func testAudioPacketPrefixesTypeAndBigEndianLength() {
        let p = AudioPCM.audioPacket(messageType: 13, pcm: Data([0xAA, 0xBB]))
        XCTAssertEqual(Array(p), [13, 0x00, 0x00, 0x00, 0x02, 0xAA, 0xBB])
    }

    func testEmptyPcmStillProducesWellFormedHeader() {
        let p = AudioPCM.audioPacket(messageType: 13, pcm: Data())
        XCTAssertEqual(Array(p), [13, 0x00, 0x00, 0x00, 0x00])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd MacHost && swift test --filter AudioPCMTests`
Expected: FAIL to compile — "cannot find 'AudioPCM' in scope".

- [ ] **Step 3: Write the implementation**

Create `MacHost/Sources/AudioPCM.swift`:

```swift
import Foundation

/// Wire-format helpers for audio passthrough. Deliberately free of CoreMedia
/// and Network.framework types so the conversion and framing can be unit
/// tested without a live capture session.
///
/// Format is fixed by the protocol: 48 kHz, 2 channels, signed 16-bit,
/// interleaved, little-endian.
enum AudioPCM {
    /// Interleaves planar Float32 channels into signed 16-bit little-endian PCM.
    ///
    /// Samples outside [-1, 1] are clamped, not wrapped — ScreenCaptureKit can
    /// hand back values slightly past full scale, and wrapping them inverts the
    /// waveform, which is an audible click rather than mild distortion.
    /// Ragged channels truncate to the shortest, so a short final buffer cannot
    /// read out of bounds.
    static func interleaveInt16(channels: [[Float]]) -> Data {
        guard let first = channels.first else { return Data() }
        let frameCount = channels.dropFirst().reduce(first.count) { min($0, $1.count) }
        guard frameCount > 0 else { return Data() }

        var out = Data(capacity: frameCount * channels.count * 2)
        for frame in 0..<frameCount {
            for channel in channels {
                let clamped = max(-1.0, min(1.0, channel[frame]))
                let sample = Int16(clamped * 32767.0)
                withUnsafeBytes(of: sample.littleEndian) { out.append(contentsOf: $0) }
            }
        }
        return out
    }

    /// `[type][length: Int32 big-endian][pcm]` — the same length-prefixed shape
    /// the video frame types use, so the client's read loop stays uniform.
    static func audioPacket(messageType: UInt8, pcm: Data) -> Data {
        var packet = Data(capacity: pcm.count + 5)
        packet.append(messageType)
        withUnsafeBytes(of: Int32(pcm.count).bigEndian) { packet.append(contentsOf: $0) }
        packet.append(pcm)
        return packet
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd MacHost && swift test --filter AudioPCMTests`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add MacHost/Sources/AudioPCM.swift MacHost/Tests/SideScreenTests/AudioPCMTests.swift
git commit -m "feat(mac): PCM interleave + audio packet framing for passthrough

Planar Float32 to interleaved Int16 LE with clamping (SCK can return values
past full scale; wrapping them is an audible click), plus the length-prefixed
wire packet. Kept free of CoreMedia types so both are unit-testable."
```

---

### Task 4: Host — capture system audio and send it

Turns on the SCK audio tap, extracts PCM from the sample buffers, and adds the two wire message types. Audio is gated on the client having advertised support, exactly as `codecSelected` is.

**Files:**
- Modify: `MacHost/Sources/ScreenCapture.swift` (`configureStream` around :336-349, `StreamOutput` at :810-816, plus a new extraction helper and an audio queue)
- Modify: `MacHost/Sources/StreamingServer.swift` (`WireMessage` at :4-24, `handleConnection` at :136-141, `processInputBuffer` at :352-445, plus `sendAudio`)

**Interfaces:**
- Consumes: `AudioPCM.interleaveInt16(channels:)`, `AudioPCM.audioPacket(messageType:pcm:)` from Task 3.
- Produces:
  - `StreamingServer.sendAudio(_ pcm: Data)` — no-ops unless the client sent type 12 and the connection is ready.
  - `StreamOutput.onAudioReceived: ((CMSampleBuffer) -> Void)?`
  - `ScreenCapture.configureStream(..., captureAudio: Bool)` — new trailing parameter, defaulting to `false` so existing call sites are unaffected.
  - `ScreenCapture.onAudioPCM: ((Data) -> Void)?` — set by `AppDelegate` in Task 6.

- [ ] **Step 1: Add the wire message types**

In `MacHost/Sources/StreamingServer.swift`, add to the `private enum WireMessage` block:

```swift
    /// Client→server, payload-free: "this device can play streamed audio".
    /// Sent BEFORE type 8, like types 9 and 11, because type 8 can trigger the
    /// server's early protocol finish.
    static let clientSupportsAudio: UInt8 = 12
    /// Server→client, `[length: 4 BE][pcm]`. 48 kHz stereo Int16 LE. Sent ONLY
    /// to clients that sent clientSupportsAudio — old clients disconnect on
    /// unknown message types, so this must never be sent unsolicited.
    static let audioFrame: UInt8 = 13
```

- [ ] **Step 2: Add the connection flag, queue, and send path**

In `StreamingServer`, next to `private var clientIsAvcOnly = false`, add:

```swift
    private var clientSupportsAudio = false
```

Next to the other queue declarations (`frameQueue`, `receiveQueue`, `networkQueue`), add:

```swift
    private let audioQueue = DispatchQueue(label: "audioSendQueue", qos: .userInitiated)
```

In `handleConnection`, alongside `clientIsAvcOnly = false`, add:

```swift
        clientSupportsAudio = false
```

In `processInputBuffer`'s `switch`, add this case before `default:`:

```swift
            case WireMessage.clientSupportsAudio:
                // Payload-free opt-in, same convention as types 8 and 9.
                consumeInputBytes(1)
                if !clientSupportsAudio {
                    clientSupportsAudio = true
                    debugLog("Client supports audio passthrough")
                }
```

Add this method next to `sendFrame`:

```swift
    func sendAudio(_ pcm: Data) {
        guard let connection = connection, !isStopped, connectionReady,
              clientSupportsAudio, !pcm.isEmpty else { return }

        // ponytail: audio shares the video TCP connection. A large keyframe
        // ahead of an audio packet delays it ~10ms on USB (300KB at ~300Mbps),
        // absorbed by the client's AudioTrack buffer. Give audio its own
        // connection if dropouts show up on WiFi.
        audioQueue.async {
            let packet = AudioPCM.audioPacket(messageType: WireMessage.audioFrame, pcm: pcm)
            connection.send(content: packet, completion: .contentProcessed { _ in })
        }
    }
```

In `stop()`, alongside `frameQueue.sync {}` and `receiveQueue.sync {}`, add:

```swift
        audioQueue.sync {}
```

- [ ] **Step 3: Route audio sample buffers in StreamOutput**

In `MacHost/Sources/ScreenCapture.swift`, replace the `StreamOutput` class body (currently at :810-816) with:

```swift
class StreamOutput: NSObject, SCStreamOutput {
    var onFrameReceived: ((CMSampleBuffer) -> Void)?
    var onAudioReceived: ((CMSampleBuffer) -> Void)?

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        switch type {
        case .screen:
            onFrameReceived?(sampleBuffer)
        case .audio:
            onAudioReceived?(sampleBuffer)
        default:
            // .microphone (macOS 15+) and any future types are not ours.
            break
        }
    }
}
```

- [ ] **Step 4: Enable the audio tap in the stream configuration**

In `ScreenCapture.swift`, change the `configureStream` signature to take a new trailing parameter. Find the declaration whose body contains `let config = SCStreamConfiguration()` (around :336) and add `captureAudio: Bool = false` as its last parameter.

Then replace `config.capturesAudio = false` with:

```swift
        config.capturesAudio = captureAudio
        if captureAudio {
            config.sampleRate = 48000
            config.channelCount = 2
            // Without this the tap records our own stream back into itself.
            config.excludesCurrentProcessAudio = true
        }
```

And after the existing `try scStream.addStreamOutput(streamOutput!, type: .screen, ...)` line, add:

```swift
        if captureAudio {
            try scStream.addStreamOutput(streamOutput!, type: .audio,
                                         sampleHandlerQueue: audioSampleQueue)
        }
```

Add the queue and callback as properties on `ScreenCapture`:

```swift
    private let audioSampleQueue = DispatchQueue(label: "audioSampleQueue", qos: .userInitiated)
    /// Set by AppDelegate to forward captured PCM to the streaming server.
    var onAudioPCM: ((Data) -> Void)?
```

- [ ] **Step 5: Extract PCM from the audio sample buffers**

Add to `ScreenCapture`, next to the other private helpers:

```swift
    /// Planar Float32 channels out of an SCK audio sample buffer.
    ///
    /// ponytail: allocates an array per channel per callback (~21ms at 48kHz,
    /// 1024 frames). Measurable but trivial next to video encode. Convert
    /// straight into a reused buffer if audio ever shows up in a profile.
    private func planarFloatChannels(from sampleBuffer: CMSampleBuffer) -> [[Float]]? {
        var listSize = 0
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: &listSize,
            bufferListOut: nil,
            bufferListSize: 0,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: 0,
            blockBufferOut: nil) == noErr else { return nil }

        let raw = UnsafeMutableRawPointer.allocate(
            byteCount: listSize,
            alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        let listPtr = raw.assumingMemoryBound(to: AudioBufferList.self)

        // blockBuffer must stay alive for as long as we read mData.
        var blockBuffer: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: listPtr,
            bufferListSize: listSize,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer) == noErr else { return nil }
        _ = blockBuffer

        return UnsafeMutableAudioBufferListPointer(listPtr).map { buffer -> [Float] in
            guard let data = buffer.mData else { return [] }
            let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
            return Array(UnsafeBufferPointer(
                start: data.assumingMemoryBound(to: Float.self), count: count))
        }
    }
```

Ensure `ScreenCapture.swift` imports what this needs — add `import AVFoundation` if `AudioBufferList` / `UnsafeMutableAudioBufferListPointer` do not resolve (they come from CoreAudioTypes, re-exported by AVFoundation and CoreMedia).

- [ ] **Step 6: Wire the audio callback where the frame handler is configured**

In `configureFrameHandler` (around :836), alongside the existing `streamOutput?.onFrameReceived = ...` assignment, add:

```swift
        streamOutput?.onAudioReceived = { [weak self] sampleBuffer in
            guard let self = self,
                  let channels = self.planarFloatChannels(from: sampleBuffer) else { return }
            let pcm = AudioPCM.interleaveInt16(channels: channels)
            guard !pcm.isEmpty else { return }
            self.onAudioPCM?(pcm)
        }
```

- [ ] **Step 7: Build and run the full test suite**

Run: `cd MacHost && swift build 2>&1 | tail -5 && swift test 2>&1 | tail -15`
Expected: `Build complete!`, and all tests pass including Tasks 1 and 3's.

- [ ] **Step 8: Commit**

```bash
git add MacHost/Sources/ScreenCapture.swift MacHost/Sources/StreamingServer.swift
git commit -m "feat(mac): capture and send system audio (wire types 12/13)

Enables the ScreenCaptureKit audio tap, extracts planar Float32 from the
audio sample buffers, and adds the two message types. Type 13 is sent only
to clients that advertised type 12, matching how codecSelected is gated —
old clients disconnect on unknown types.

Audio shares the video TCP connection; the head-of-line delay a large
keyframe can add is ~10ms on USB, absorbed by the client's AudioTrack
buffer."
```

---

### Task 5: Host — mute the Mac's output while streaming

Makes the tablet behave as the output device rather than a second speaker. Saves the prior mute state and restores it on stop and on quit.

**Files:**
- Create: `MacHost/Sources/MacAudioMute.swift`
- Test: `MacHost/Tests/SideScreenTests/MacAudioMuteTests.swift`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `MacAudioMute.parseMuted(from output: String) -> Bool?`
  - `MacAudioMute.isMuted() -> Bool?`
  - `MacAudioMute.setMuted(_ muted: Bool)`

- [ ] **Step 1: Write the failing tests**

Create `MacHost/Tests/SideScreenTests/MacAudioMuteTests.swift`:

```swift
import XCTest
@testable import SideScreen

final class MacAudioMuteTests: XCTestCase {
    /// The exact shape `osascript -e 'get volume settings'` returns.
    func testParsesRealOsascriptOutputUnmuted() {
        let out = "output volume:50, input volume:75, alert volume:100, output muted:false\n"
        XCTAssertEqual(MacAudioMute.parseMuted(from: out), false)
    }

    func testParsesRealOsascriptOutputMuted() {
        let out = "output volume:0, input volume:75, alert volume:100, output muted:true\n"
        XCTAssertEqual(MacAudioMute.parseMuted(from: out), true)
    }

    /// Some devices report no mute field at all. Nil means "unknown", which the
    /// caller treats as "do not try to restore" rather than "was unmuted".
    func testMissingFieldIsNilNotFalse() {
        let out = "output volume:50, input volume:75, alert volume:100\n"
        XCTAssertNil(MacAudioMute.parseMuted(from: out))
    }

    func testGarbageValueIsNil() {
        XCTAssertNil(MacAudioMute.parseMuted(from: "output muted:maybe"))
    }

    func testEmptyOutputIsNil() {
        XCTAssertNil(MacAudioMute.parseMuted(from: ""))
    }

    /// "output muted" must not be confused with a differently-prefixed field.
    func testDoesNotMatchInputMutedField() {
        XCTAssertNil(MacAudioMute.parseMuted(from: "input muted:true"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd MacHost && swift test --filter MacAudioMuteTests`
Expected: FAIL to compile — "cannot find 'MacAudioMute' in scope".

- [ ] **Step 3: Write the implementation**

Create `MacHost/Sources/MacAudioMute.swift`:

```swift
import Foundation

/// Mutes the Mac's default output device while audio is streaming to the
/// tablet, so the tablet acts as the output device instead of a second speaker.
///
/// ponytail: shells out to osascript rather than using CoreAudio's
/// kAudioDevicePropertyMute — 3 lines instead of ~30, and AppDelegate already
/// shells out to adb, so subprocess use is established here. Ceiling: ~50ms per
/// call, and it only ever touches the *default* output device. Move to
/// CoreAudio if per-device control is ever needed.
enum MacAudioMute {
    /// Parses `osascript -e 'get volume settings'`, whose output looks like:
    /// `output volume:50, input volume:75, alert volume:100, output muted:false`
    ///
    /// Returns nil when the field is absent or unparseable, which callers must
    /// treat as "unknown" rather than "unmuted" — guessing false here would
    /// unmute a Mac the user had deliberately silenced.
    static func parseMuted(from output: String) -> Bool? {
        guard let range = output.range(of: "output muted:") else { return nil }
        let tail = output[range.upperBound...]
        if tail.hasPrefix("true") { return true }
        if tail.hasPrefix("false") { return false }
        return nil
    }

    static func isMuted() -> Bool? {
        guard let out = runOsascript("get volume settings") else { return nil }
        return parseMuted(from: out)
    }

    static func setMuted(_ muted: Bool) {
        _ = runOsascript("set volume output muted \(muted)")
    }

    private static func runOsascript(_ script: String) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice

        do {
            try process.run()
        } catch {
            debugLog("osascript failed to launch: \(error)")
            return nil
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()

        guard process.terminationStatus == 0 else {
            debugLog("osascript exited \(process.terminationStatus) for: \(script)")
            return nil
        }
        return String(data: data, encoding: .utf8)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd MacHost && swift test --filter MacAudioMuteTests`
Expected: PASS, 6 tests.

- [ ] **Step 5: Verify against the real system**

Run:

```bash
osascript -e 'set volume output muted false' && osascript -e 'get volume settings'
osascript -e 'set volume output muted true' && osascript -e 'get volume settings'
osascript -e 'set volume output muted false'
```

Expected: the field flips `output muted:false` → `output muted:true` → back. This confirms the parser is matching a real string, not an invented one.

- [ ] **Step 6: Commit**

```bash
git add MacHost/Sources/MacAudioMute.swift MacHost/Tests/SideScreenTests/MacAudioMuteTests.swift
git commit -m "feat(mac): mute the default output device while streaming audio

osascript rather than CoreAudio: 3 lines instead of ~30, and AppDelegate
already shells out to adb. Parser returns nil for an absent mute field so
callers never mistake 'unknown' for 'was unmuted' and silently unmute a Mac
the user had deliberately silenced."
```

---

### Task 6: Host — settings, and wire capture to server and mute

Connects everything on the Mac: two persisted settings, the UI section, forwarding PCM from capture to server, and mute save/restore across stream start, stop, and app quit.

**Files:**
- Modify: `MacHost/Sources/SettingsWindow.swift` (`@Published` block near :1145-1180, loader near :1218-1232, `resetToDefaults` near :1300-1322, and a new UI section after the Touch Control `FrostedGroupBox` near :450)
- Modify: `MacHost/Sources/AppDelegate.swift` (settings observers near :258-270, stream start near :577 and :662, stop path, `applicationWillTerminate`)

**Interfaces:**
- Consumes: `StreamingServer.sendAudio(_:)`, `ScreenCapture.onAudioPCM`, `ScreenCapture.configureStream(..., captureAudio:)` from Task 4; `MacAudioMute.isMuted()`, `MacAudioMute.setMuted(_:)` from Task 5.
- Produces: `DisplaySettings.audioEnabled: Bool`, `DisplaySettings.muteMacWhileStreaming: Bool`.

- [ ] **Step 1: Add the two settings**

In `MacHost/Sources/SettingsWindow.swift`, alongside the other `@Published` properties:

```swift
    @Published var audioEnabled: Bool {
        didSet { save("audioEnabled", audioEnabled) }
    }
    @Published var muteMacWhileStreaming: Bool {
        didSet { save("muteMacWhileStreaming", muteMacWhileStreaming) }
    }
```

In the initialiser alongside the other `defaults` reads:

```swift
        // `object(forKey:) as? Bool` rather than `bool(forKey:)` so an absent
        // key means "default on", not false.
        self.audioEnabled = defaults.object(forKey: keyPrefix + "audioEnabled") as? Bool ?? true
        self.muteMacWhileStreaming =
            defaults.object(forKey: keyPrefix + "muteMacWhileStreaming") as? Bool ?? true
```

**If the Task 2 spike found the tap goes silent when muted**, use `?? false` for `muteMacWhileStreaming` instead of `?? true`, and use `false` in `resetToDefaults` below.

In `resetToDefaults`, add both keys to the `keys` array:

```swift
        let keys = ["resolution", "refreshRate", "hiDPI", "bitrate", "quality",
                    "gamingBoost", "port", "rotation", "flipHorizontal", "flipVertical", "showAllResolutions",
                    "customWidth", "customHeight", "touchEnabled", "autoStartStreamingOnLaunch", "startupMode",
                    "audioEnabled", "muteMacWhileStreaming"]
```

and set the values alongside the other assignments:

```swift
        audioEnabled = true
        muteMacWhileStreaming = true
```

- [ ] **Step 2: Add the Audio settings section**

In `SettingsWindow.swift`, immediately after the closing brace of the `FrostedGroupBox(title: "Touch Control", icon: "hand.tap")` block, add:

```swift
                        // Audio passthrough
                        FrostedGroupBox(title: "Audio", icon: "speaker.wave.2") {
                            VStack(alignment: .leading, spacing: 8) {
                                Toggle("Stream audio to tablet", isOn: $settings.audioEnabled)
                                    .font(.system(size: 11))
                                Toggle("Mute Mac while streaming", isOn: $settings.muteMacWhileStreaming)
                                    .font(.system(size: 11))
                                    .disabled(!settings.audioEnabled)
                                Text("48 kHz stereo over the existing connection. Toggling restarts the stream.")
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary)
                            }
                        }
```

- [ ] **Step 3: Restart the stream when the audio toggle changes**

`capturesAudio` is fixed at stream-configure time, so toggling it needs a restart. In `AppDelegate.swift`, immediately after the existing `settings.$resolution` observer (which ends with `.store(in: &cancellables)` at :274), add the same pattern — this mirrors that observer exactly, including the `Task { @MainActor in }` wrapper and the `settings.isRunning` guard:

```swift
        // capturesAudio is set when the stream is configured, so toggling audio
        // needs the same stop/start cycle the resolution observer performs.
        settings.$audioEnabled
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] enabled in
                guard let self = self else { return }
                Task { @MainActor in
                    guard self.settings.isRunning else { return }
                    debugLog("Audio \(enabled ? "enabled" : "disabled") — restarting server")
                    self.stopServer()
                    await self.startServer()
                }
            }
            .store(in: &cancellables)
```

Note there is no `restartServer()` helper in this codebase — `stopServer()` followed by `await startServer()` inside a `@MainActor` task is the established pattern.

- [ ] **Step 4: Forward captured PCM to the server, and apply mute**

In `AppDelegate.swift`, after `screenCapture = try await ScreenCapture()` (around :577), add:

```swift
            screenCapture?.onAudioPCM = { [weak self] pcm in
                self?.streamingServer?.sendAudio(pcm)
            }
```

Pass the setting through at the point `configureStream` is called (find the call in the `startStreaming` path around :662 and add the argument):

```swift
                captureAudio: settings.audioEnabled
```

Then, where streaming starts, save and apply mute:

```swift
            if settings.audioEnabled && settings.muteMacWhileStreaming {
                previousMacMuteState = MacAudioMute.isMuted()
                MacAudioMute.setMuted(true)
                debugLog("Muted Mac output (was \(previousMacMuteState.map(String.init) ?? "unknown"))")
            }
```

Add the stored property to `AppDelegate`:

```swift
    /// Mute state before we changed it. nil means unknown or never changed —
    /// in both cases we must not touch it on restore.
    private var previousMacMuteState: Bool?
```

- [ ] **Step 5: Restore mute on stop and on quit**

Add this method to `AppDelegate`:

```swift
    private func restoreMacMuteState() {
        guard let previous = previousMacMuteState else { return }
        MacAudioMute.setMuted(previous)
        previousMacMuteState = nil
        debugLog("Restored Mac output mute state to \(previous)")
    }
```

Call it from `stopServer()` (at :690, which calls `streamingServer?.stop()` at :695) and from the existing `applicationWillTerminate(_:)` (at :1099, which already does `cancellables.removeAll()` at :1107). Add the call as the **first** statement in each, so mute is restored even if later teardown throws:

```swift
        restoreMacMuteState()
```

Both methods already exist — do not add new ones.

- [ ] **Step 6: Build and run the full suite**

Run: `cd MacHost && swift build 2>&1 | tail -5 && swift test 2>&1 | tail -15`
Expected: `Build complete!` and all tests passing.

- [ ] **Step 7: Confirm mute is not left stuck after a run**

Run:

```bash
osascript -e 'get volume settings'
```

Expected: `output muted:false`. If a previous build left it muted, unmute with `osascript -e 'set volume output muted false'` and check the restore call sites cover every exit path.

- [ ] **Step 8: Commit**

```bash
git add MacHost/Sources/SettingsWindow.swift MacHost/Sources/AppDelegate.swift
git commit -m "feat(mac): audio settings, PCM forwarding, and mute lifecycle

Two persisted settings (both default on), an Audio section in the settings
window, capture-to-server PCM forwarding, and mute save/restore across
stream stop and app quit. Toggling audio restarts the stream because
capturesAudio is fixed when the stream is configured."
```

---

### Task 7: Client — play the streamed audio

Adds `AudioTrack` playback and the two message types on the Android side. The client always advertises audio support; the host's toggle alone decides whether audio flows, keeping configuration in one place.

**Files:**
- Create: `AndroidClient/app/src/main/java/com/sidescreen/app/AudioPlayer.kt`
- Modify: `AndroidClient/app/src/main/java/com/sidescreen/app/StreamClient.kt` (companion constants at :599-611, `advertise*` calls at :129-131 and :258-260, a new `advertiseAudioSupport`, the `receiveData` dispatch at :327-371, and `disconnect` at :563)
- Modify: `AndroidClient/app/src/main/java/com/sidescreen/app/MainActivity.kt` (wire the player to the client)
- Test: `AndroidClient/app/src/test/java/com/sidescreen/app/AudioFrameSizeTest.kt`

**Interfaces:**
- Consumes: wire types 12 and 13 from Task 4.
- Produces:
  - `AudioPlayer.play(pcm: ByteArray, length: Int)` — lazily starts the track on first use
  - `AudioPlayer.stop()`
  - `AudioPlayer.isValidAudioFrameSize(size: Int): Boolean` (companion)
  - `StreamClient.onAudioFrame: ((ByteArray, Int) -> Unit)?`

**Why lazy start:** starting on the first audio frame rather than on connect means no `onConnectionStatus` handler needs touching (there are two, at `MainActivity.kt:1031` and `:1178`), and it is the more accurate trigger — the host may have audio disabled, in which case no track should ever be allocated.

- [ ] **Step 1: Write the failing test**

Create `AndroidClient/app/src/test/java/com/sidescreen/app/AudioFrameSizeTest.kt`:

```kotlin
package com.sidescreen.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameSizeTest {
    @Test
    fun typicalFrameIsValid() {
        // 1024 frames x 2 channels x 2 bytes = 4096, a normal SCK callback.
        assertTrue(AudioPlayer.isValidAudioFrameSize(4096))
    }

    @Test
    fun zeroIsRejected() {
        assertFalse(AudioPlayer.isValidAudioFrameSize(0))
    }

    @Test
    fun negativeIsRejected() {
        // A misaligned stream can read a negative Int32; allocating on it throws.
        assertFalse(AudioPlayer.isValidAudioFrameSize(-1))
    }

    @Test
    fun absurdlyLargeIsRejected() {
        // Guard against a misaligned stream making us allocate ~2GB.
        assertFalse(AudioPlayer.isValidAudioFrameSize(Int.MAX_VALUE))
    }

    @Test
    fun boundaryIsAccepted() {
        assertTrue(AudioPlayer.isValidAudioFrameSize(AudioPlayer.MAX_AUDIO_FRAME_SIZE))
    }

    @Test
    fun justPastBoundaryIsRejected() {
        assertFalse(AudioPlayer.isValidAudioFrameSize(AudioPlayer.MAX_AUDIO_FRAME_SIZE + 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd AndroidClient && ./gradlew :app:testDebugUnitTest --tests '*AudioFrameSizeTest*'`
Expected: FAIL to compile — unresolved reference `AudioPlayer`.

- [ ] **Step 3: Write AudioPlayer**

Create `AndroidClient/app/src/main/java/com/sidescreen/app/AudioPlayer.kt`:

```kotlin
package com.sidescreen.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the Mac's system audio: 48 kHz stereo signed 16-bit LE PCM, written
 * straight from the network receive thread.
 *
 * ponytail: AudioTrack's own buffer is the jitter buffer — there is
 * deliberately no ring buffer here. Buffer is 2x the device minimum, which is
 * enough to absorb the ~10ms a large video keyframe can add ahead of an audio
 * packet on the shared connection. Add a real jitter buffer only if dropouts
 * show up on WiFi.
 *
 * There is no A/V sync: audio plays as it arrives. Fine for music, calls, and
 * UI sound; may drift slightly against video.
 */
class AudioPlayer {
    private var track: AudioTrack? = null
    private var startFailed = false

    /**
     * Writes PCM, starting the track on first use. Synchronized against
     * [stop] because writes come from the network thread while teardown comes
     * from the main thread, and writing to a released AudioTrack throws.
     */
    @Synchronized
    fun play(
        pcm: ByteArray,
        length: Int,
    ) {
        if (track == null) {
            if (startFailed) return
            start()
            if (track == null) {
                startFailed = true
                return
            }
        }
        track?.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
    }

    private fun start() {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        if (minBytes <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBytes — audio playback unavailable")
            return
        }

        track =
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBytes * 2)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                    .also { it.play() }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack creation failed", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "AudioTrack rejected the format", e)
                null
            }

        if (track != null) {
            Log.i(TAG, "AudioTrack started (buffer ${minBytes * 2} bytes)")
        }
    }

    @Synchronized
    fun stop() {
        startFailed = false
        track?.let { t ->
            try {
                t.pause()
                t.flush()
                t.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack already stopped", e)
            }
            t.release()
        }
        track = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 256 KB, about 1.4s of 48 kHz stereo — far above any real frame. */
        const val MAX_AUDIO_FRAME_SIZE = 1 shl 18

        /**
         * Guards against a misaligned stream making us allocate an absurd
         * buffer, or throw on a negative length.
         */
        fun isValidAudioFrameSize(size: Int): Boolean = size in 1..MAX_AUDIO_FRAME_SIZE
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd AndroidClient && ./gradlew :app:testDebugUnitTest --tests '*AudioFrameSizeTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Add the message types and advertise support**

In `StreamClient.kt`'s companion object, alongside the other `MESSAGE_` constants:

```kotlin
        private const val MESSAGE_CLIENT_SUPPORTS_AUDIO = 12
        private const val MESSAGE_AUDIO_FRAME = 13
```

Alongside the other callbacks near :39:

```kotlin
    var onAudioFrame: ((ByteArray, Int) -> Unit)? = null
```

Add the advertise function next to `advertiseDecoderLimits`:

```kotlin
    private fun advertiseAudioSupport() {
        outputStream?.let { out ->
            out.writeByte(MESSAGE_CLIENT_SUPPORTS_AUDIO)
            out.flush()
            diagLog("Advertised audio playback support")
        }
    }
```

Call it in **both** connect paths, before `advertiseFrameMetadataSupport()` — at :129-131 and :258-260, so each block reads:

```kotlin
                advertiseAvcOnlyIfNeeded() // MUST precede type 8: type 8 can trigger the server's early protocol finish
                advertiseDecoderLimits() // Also before type 8, for the same reason
                advertiseAudioSupport() // Also before type 8, for the same reason
                advertiseFrameMetadataSupport()
```

- [ ] **Step 6: Handle incoming audio frames**

In `receiveData`'s `when (type.toInt())`, add before the `else ->` branch:

```kotlin
                        MESSAGE_AUDIO_FRAME -> {
                            val size = input.readInt()
                            if (!AudioPlayer.isValidAudioFrameSize(size)) {
                                Log.e(TAG, "Invalid audio frame size $size — disconnecting")
                                break
                            }
                            val buf = ByteArray(size)
                            input.readFully(buf, 0, size)
                            onAudioFrame?.invoke(buf, size)
                        }
```

`readFully` blocks until the whole frame arrives, so a frame split across TCP reads needs no extra handling.

- [ ] **Step 7: Wire the player in MainActivity**

In `MainActivity.kt`, add a field alongside `private var streamClient: StreamClient? = null` (:53):

```kotlin
    private val audioPlayer = AudioPlayer()
```

Callbacks are assigned at **two** sites — the USB path (near `streamClient?.onLatencyMeasured` at :1025) and the wireless path (near :1174). Add this at both, immediately after the `onLatencyMeasured` assignment:

```kotlin
        streamClient?.onAudioFrame = { pcm, length ->
            audioPlayer.play(pcm, length)
        }
```

Mind the indentation: the wireless site at :1174 is nested more deeply than the USB site at :1025.

No `onConnectionStatus` change is needed — `play` starts the track itself. For teardown, add `audioPlayer.stop()` to `cleanup()`, which `onDestroy` (:1458) already calls. Also call it on the disconnect path so a reconnect starts a fresh track; if `cleanup()` is what the disconnect button invokes, that one call covers both.

- [ ] **Step 8: Build, test, and lint**

Run:

```bash
cd AndroidClient && ./gradlew :app:assembleDebug :app:testDebugUnitTest 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all unit tests pass.

- [ ] **Step 9: Commit**

```bash
git add AndroidClient/app/src/main/java/com/sidescreen/app/AudioPlayer.kt \
        AndroidClient/app/src/main/java/com/sidescreen/app/StreamClient.kt \
        AndroidClient/app/src/main/java/com/sidescreen/app/MainActivity.kt \
        AndroidClient/app/src/test/java/com/sidescreen/app/AudioFrameSizeTest.kt
git commit -m "feat(android): play streamed Mac audio via AudioTrack

48 kHz stereo PCM16 written straight from the receive thread, with
AudioTrack's own buffer as the jitter buffer. The client always advertises
type 12; the host's toggle decides whether audio actually flows, so
configuration stays in one place.

Frame-size guard rejects negative and absurd lengths so a misaligned stream
cannot trigger a huge allocation."
```

---

### Task 8: End-to-end verification, frame-rate ceiling, and latency tuning

Confirms audio works on real hardware, then answers the question the spec deliberately left to measurement: whether 144 Hz holds at 2800×1840, or whether the pipeline is decoder-bound and 120 is the real ceiling.

**Files:**
- Modify: `docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md` (record measurements)

**Interfaces:**
- Consumes: everything from Tasks 1, 4, 5, 6, 7.
- Produces: measured settings recorded in the spec. No code.

- [ ] **Step 1: Install both sides on real hardware**

```bash
cd /Users/huynhquocdinh/dev/personal/SideScreen
./scripts/build_mac.sh && ./scripts/build_android.sh && ./scripts/install_android.sh
```

Expected: both build; the APK installs to the connected MRDI-W09.

- [ ] **Step 2: Select the MatePad preset and confirm audio**

In the Mac settings window: enable "Show all", pick the **MatePad Pro 12.2** preset (`1400x920`), enable HiDPI, set 120 Hz, and confirm both Audio toggles are on. Start streaming and connect the tablet over USB.

Play audio on the Mac (`afplay /System/Library/Sounds/Submarine.aiff`).

Expected: sound comes from the **tablet**, the Mac is silent, and the tablet's resolution overlay reads `1400x920`.

If sound comes from both, the Task 2 spike found the tap dies under mute and `muteMacWhileStreaming` defaults off — that is the documented fallback, not a bug.

- [ ] **Step 3: Confirm mute restores cleanly**

Stop streaming, then:

```bash
osascript -e 'get volume settings'
```

Expected: `output muted:false`. Repeat after quitting the app entirely — the state must be restored on both paths.

- [ ] **Step 4: Pin the panel to 144 Hz**

On the tablet: Settings → Display & brightness → Screen refresh rate → **High** (not Dynamic). Confirm:

```bash
adb shell dumpsys display | grep -oE "refreshRate=[0-9.]+" | sort -u
```

Expected: 144 present. Without this pinned, the 144 Hz measurement below tests nothing.

- [ ] **Step 5: Measure 120 Hz**

Set the Mac to 120 Hz, stream for 60 seconds with a moving image on the virtual display (drag a window, or play a video), and capture the host's per-second pipeline log:

```bash
log stream --predicate 'process == "SideScreen"' --style compact | grep -E "^.*Pipeline:" | head -60
```

If the app logs to stdout rather than the unified log, read the terminal output from `./scripts/dev-test.sh` instead.

Record: sustained fps, Mbps, avg frame age (ms), dropped count. Also note the tablet's on-screen stats overlay figures.

Acceptance: sustained fps within ~2% of 120 (≥117.6) and dropped ≈ 0.

- [ ] **Step 6: Measure 144 Hz**

Repeat Step 5 with the Mac set to 144 Hz.

Acceptance: sustained fps ≥ 141.1 and dropped ≈ 0.

At 2800×1840 this is 742 Mpx/s versus 618 at 120 Hz — for reference 4K60 is 498 Mpx/s — so a shortfall here is the expected outcome, not a defect. If 144 falls short, 120 is the ceiling.

- [ ] **Step 7: Latency tuning pass at the winning rate**

At whichever rate passed, sweep `bitrate` and `quality` and record avg frame age for each:

- Current baseline: `bitrate=100`, `quality=high`
- `bitrate=100`, `quality=ultralow`
- `bitrate=50`, `quality=high`
- `bitrate=200`, `quality=high`

Pick the lowest avg frame age with no visible quality loss and dropped ≈ 0, then set those values in the Mac settings window.

- [ ] **Step 8: Record results in the spec**

In `docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md`, replace the "### Method" subsection's closing paragraphs with a results table of the actual numbers, and state the chosen configuration explicitly. Include the 90 Hz judder confirmation or refutation from Step 5 (whether matching a real panel mode removed the micro-stutter).

- [ ] **Step 9: Commit**

```bash
git add docs/superpowers/specs/2026-08-03-matepad-profile-and-audio-passthrough-design.md
git commit -m "docs: record measured frame-rate ceiling and tuned settings

Measured on M4 + MRDI-W09 at 2800x1840 HiDPI rather than assumed."
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: Part A → Task 1; Part B → Task 8 (steps 4-8); Part C transport and wire protocol → Task 4; Mac capture and PCM → Tasks 3, 4; `MacAudioMute` → Task 5; Mac settings UI → Task 6; Android `AudioPlayer` and `StreamClient` → Task 7; the sequenced-first spike → Task 2; testing section → Tasks 1, 3, 5, 7. The two accepted limitations (no A/V sync, crash-while-muted) are recorded as code comments in Tasks 5 and 7 rather than as work.

**Placeholder scan.** No TBD/TODO. Every code step carries actual code with real symbol names and line numbers read from source. The one remaining judgement call is Task 7 Step 7's note that `cleanup()` may already cover the disconnect path — an implementer must read `cleanup()` to confirm rather than add a redundant second call.

**Type consistency.** `AudioPCM.interleaveInt16(channels:)` and `AudioPCM.audioPacket(messageType:pcm:)` are defined in Task 3 and consumed with identical signatures in Task 4. `StreamingServer.sendAudio(_:)` defined in Task 4, consumed in Task 6. `ScreenCapture.onAudioPCM` defined in Task 4, assigned in Task 6. `MacAudioMute.isMuted()`/`setMuted(_:)`/`parseMuted(from:)` defined in Task 5, used in Task 6. `AudioPlayer.play(pcm:length:)` is the only write entry point — `start()` is private and `write()` does not exist. `AudioPlayer.isValidAudioFrameSize`/`MAX_AUDIO_FRAME_SIZE` defined in Task 7 Step 3, tested in Task 7 Step 1 and used in Step 6. Message types 12 and 13 are consistent across Task 4 (Swift) and Task 7 (Kotlin).

**Host symbol names verified against source**, not assumed: `settings.isRunning` (not `isServerRunning`), `stopServer()` + `await startServer()` (there is no `restartServer()`), `cancellables`, and the already-present `applicationWillTerminate(_:)` at `AppDelegate.swift:1099`. Client sites verified too: two `advertise*` blocks, two `onConnectionStatus` handlers, two callback-assignment sites, and `cleanup()` as the teardown point.

**One cross-task dependency to respect:** Task 2's spike result decides the default value of `muteMacWhileStreaming` in Task 6 Step 1 and the expected outcome in Task 8 Step 2. Both places state both branches explicitly.
