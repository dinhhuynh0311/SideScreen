# MatePad Pro 12.2" display profile + audio passthrough

Date: 2026-08-03
Status: approved

## Goal

Two changes to this fork:

1. Make the Huawei MatePad Pro 12.2" a first-class target — correct native
   resolution preset, a refresh rate the panel actually runs, and a measured
   (not guessed) frame-rate ceiling.
2. Stream Mac system audio to the tablet so it works as an audio output
   device, not just a display.

## Confirmed hardware facts

All measured on the actual devices, not taken from spec sheets. The published
spec for this tablet says 2800×1864; the panel reports 2800×1840.

| Property | Value |
|---|---|
| Tablet | MRDI-W09, MatePad Pro 12.2" (2025) |
| Tablet OS | HarmonyOS 4.x, AOSP-based — APKs run; the existing Kotlin client is usable |
| Panel | 2800×1840, density 360 |
| Panel refresh modes | 60 / 120 / 144 Hz (`dumpsys display`) |
| Mac | Apple M4, Mac16,1, 10 cores |
| macOS | 26.6 (25G72) |

Pre-existing hand-tuned config found in `UserDefaults`
(`com.sidescreen.app`): `resolution=1400x920`, `hiDPI=1`, `refreshRate=90`,
`quality=high`, `bitrate=100`, `connectionMode=usb`.

`1400x920` was entered through the custom-resolution field, not chosen from a
preset — no preset matches this panel because 2800/1840 ≈ 1.522:1 is not a
standard aspect ratio.

### Why HiDPI matters to the throughput budget

Capture and encode run at **physical** pixels, not logical. `ScreenCapture`
resolves size via `CGDisplayCopyDisplayMode().pixelWidth/pixelHeight`
(`ScreenCapture.swift:127-172`), and `VirtualDisplayManager.createDisplay`
doubles the requested logical size when `hiDPI` is set
(`VirtualDisplayManager.swift:39-40`).

So logical `1400x920` + HiDPI encodes **2800×1840 = 5.15 Mpx/frame**. It is
also an exact 2× of the panel, so no resampling occurs at any stage: encoder,
decoder, or `TextureView`.

The logical size is what gets sent to the client as display config
(`AppDelegate.swift:607`), deliberately — the stream's SPS carries true
physical dimensions, so the decoder configures correctly regardless.

## Part A — MatePad display profile

Two literal edits. Both fit existing types; no new abstraction.

1. `MacHost/Sources/SettingsWindow.swift:1254`, `resolutionGroups` — add:

   ```swift
   ResolutionGroup(name: "MatePad Pro 12.2", ratio: "HiDPI 2800×1840",
                   resolutions: ["1400x920"])
   ```

   Consumed by the existing `ForEach(DisplaySettings.resolutionGroups)` and
   `ResolutionRow` (`SettingsWindow.swift:224-241`, `1031`) with no UI change.
   Note this row only appears when "Show all" is enabled; it is intentionally
   not added to `commonResolutions`, which is a generic 4-entry shortlist.

2. `MacHost/Sources/SettingsWindow.swift:429` — frame rate options
   `[30, 60, 90, 120]` → `[30, 60, 90, 120, 144]`.

### Explicitly rejected: adb model auto-detection

Reading the tablet model over adb to auto-apply the profile is one more code
path, with its own failure modes, serving a list row the user picks once.

## Part B — Measured frame-rate ceiling, then latency tuning

### The 90 Hz problem

`refreshRate=90` matches no panel mode (60/120/144). 90 fps presented on a
120 Hz panel is uneven — successive frames are held for 2 refreshes, then 1 —
which presents as micro-stutter. Moving to a real panel mode is expected to
fix this at no cost. To be confirmed by the same measurement below rather
than assumed.

### Throughput budget

For reference, 4K60 = 3840×2160×60 = 498 Mpx/s.

| Mode | Pixel rate |
|---|---|
| 2800×1840 @ 120 | 618 Mpx/s |
| 2800×1840 @ 144 | 742 Mpx/s |

144 Hz may be decoder-bound on the Kirin SoC. This is measured, not assumed.

### Method

Uses instrumentation already in the tree — nothing new to build:

- Host: the `Pipeline: Xfps, Ymbps, avg frame age: Zms, dropped: N` log
  emitted once per second at `StreamingServer.swift:557`.
- Client: the existing on-tablet stats overlay.

Matrix: {120, 144} × HiDPI on. Accept the highest rate where sustained fps
holds within ~2% of target and `dropped` stays at approximately 0.

Manual precondition: HarmonyOS display settings must be pinned to 144 Hz, not
"Dynamic", or the panel will not run the mode under test.

Then a bitrate / `quality` / GOP pass at the winning rate, verified with the
same two readouts.

## Part C — Audio passthrough

### Approach

macOS offers no API to redirect system audio output without a CoreAudio HAL
virtual-device plug-in. ScreenCaptureKit's audio tap gives a copy of the
system mix with no driver and no install — the flag already exists in the tree
at `ScreenCapture.swift:343`, set to `false`.

A tap alone means audio plays on the Mac *and* the tablet. To get
output-device behaviour, the Mac's default output device is muted over
CoreAudio while streaming.

Rejected: shipping a HAL plug-in. Correct and permanent, but a signed
installer, a privileged install, and a whole subsystem of failure modes for
a behaviour the mute achieves.

### Transport: existing TCP connection

Audio rides the existing connection rather than a second socket.

48 kHz stereo Int16 = 1.54 Mbps, negligible beside video. The risk with a
single socket is head-of-line blocking behind a large keyframe: ~300 KB over
USB adb-reverse is roughly 8-10 ms of added jitter, absorbed by a ~40 ms
AudioTrack buffer.

A second socket would need its own lifecycle, auth handshake, and port for a
problem the arithmetic says does not exist. Recorded in code as:

```
// ponytail: audio shares the video TCP connection; a large keyframe can
// delay an audio packet ~10ms on USB, absorbed by the AudioTrack buffer.
// Move audio to its own connection if dropouts appear on WiFi.
```

`NWConnection` serialises sends internally, so sending audio from a queue
other than `frameQueue` is safe.

### Wire protocol

Follows the opt-in convention already documented at
`StreamingServer.swift:4-24`.

| Type | Direction | Payload | Notes |
|---|---|---|---|
| 12 `clientSupportsAudio` | client→server | none | Sent before type 8, like types 9 and 11. Payload-free so old hosts consume 1 byte harmlessly via the `default` branch at `StreamingServer.swift:440`. |
| 13 `audioFrame` | server→client | `[len:4 BE][PCM]` | Sent **only** to clients that sent type 12 — same discipline as `codecSelected` (type 10), because old clients disconnect on unknown message types. |

PCM format: 48 kHz, 2 channels, signed 16-bit, interleaved, little-endian.

### Components

**Mac — `ScreenCapture.swift`**

- `config.capturesAudio = true`, `sampleRate = 48000`, `channelCount = 2`,
  `excludesCurrentProcessAudio = true`.
- Register a second stream output for `.audio` on its own dispatch queue.
- `StreamOutput` currently hard-returns for `type != .screen`
  (`ScreenCapture.swift:813`); it gains an `onAudioReceived` callback
  alongside `onFrameReceived`.
- Convert SCK planar Float32 → interleaved Int16. Halves bandwidth versus
  Float32 and is inaudible for playback.

**Mac — `AudioOutputMuter.swift` (new, small)**

- Resolve default output device via `kAudioHardwarePropertyDefaultOutputDevice`.
- Mute with `kAudioDevicePropertyMute`; fall back to
  `kAudioDevicePropertyVolumeScalar = 0` for devices that do not support mute.
- Save prior state; restore on stream stop and on `applicationWillTerminate`.

**Mac — `SettingsWindow.swift`**

- New "Audio" section: "Stream audio to tablet" toggle, and a "Mute Mac while
  streaming" toggle enabled only when the first is on. Both persisted through
  the existing `save(_:_:)` / `defaults` pattern and added to the
  `resetToDefaults` key list (`SettingsWindow.swift:1300`).
- Both default to **on** — audio passthrough with the Mac silent is the
  requested behaviour, so it is the out-of-box state, not opt-in. If the spike
  forces mirror-only, "Mute Mac while streaming" defaults **off** instead,
  since it would then be a manual control with a side effect.

**Android — `AudioPlayer.kt` (new) + `StreamClient.kt`**

- `AudioTrack`: 48 kHz, stereo, `ENCODING_PCM_16BIT`, `MODE_STREAM`,
  `PERFORMANCE_MODE_LOW_LATENCY`, buffer 2× `getMinBufferSize`.
- Written straight from the network receive thread. AudioTrack's own buffer
  is the jitter buffer — no ring buffer of ours.
- No manifest permission required for playback.
- Client sends type 12 during handshake only when the audio setting is on.

### Sequenced first: the mute/tap spike

Before any of Part C is built, verify that SCK's audio tap still delivers
non-silent samples while the default output device is muted. The tap is
expected to sit ahead of the hardware device, but this is unverified and the
whole "Mac goes silent" behaviour depends on it.

If the tap sits behind device mute: drop the auto-mute, keep the "Mute Mac"
toggle as a manual control, and ship mirror-only. Nothing else in Part C
changes.

### Accepted limitations

- **No A/V PTS sync.** Audio plays as it arrives. Fine for music, calls, and
  UI sound; may drift slightly against video. Not solved.
- **Crash while muted leaves the Mac muted.** Restore is best-effort only,
  since the volume key reverses it trivially.

## Testing

Two checks, in the test directories that already exist.

- `MacHost/Tests/SideScreenTests/` — planar Float32 → interleaved Int16
  conversion: channel interleave order, sample count, and clamping of values
  outside [-1, 1].
- `AndroidClient/app/src/test/java/com/sidescreen/app/` — audio frame framing
  and parse, including a length-prefixed frame split across two reads.
  Alongside the existing `SyncFrameDetectionTest.kt`.

Part A is literal list entries; no tests. Part B is a measurement task, not
code.

## Out of scope

- M-Pencil / stylus input (pressure, hover) — its own spec if wanted.
- A/V PTS synchronisation.
- CoreAudio HAL virtual audio driver.
- adb-based tablet auto-detection.
- Opus / AAC audio encoding.
