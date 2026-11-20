import AVFoundation
import CoreMedia
import Foundation

/// Captures audio from a CoreAudio *input* device — typically a loopback driver
/// such as BlackHole — so the tablet can act as a selectable macOS output device
/// rather than a mirror of the system mix.
///
/// How the pieces fit: the user installs a loopback driver, selects it as the
/// Mac's output (directly, or inside a Multi-Output Device to keep hearing the
/// Mac too), and we capture the loopback's *input* side here. Everything
/// downstream — the wire format, wire type 16, and the Android AudioPlayer — is
/// unchanged from the ScreenCaptureKit tap path.
///
/// ponytail: an AVCaptureSession rather than a hand-rolled AUHAL input unit.
/// `audioSettings` lets us demand the exact wire format, so there is no
/// conversion step at all — the sample buffer bytes ARE the payload. Ceiling: no
/// control over the capture buffer size, so if Mac-side latency ever needs
/// tuning, that is the reason to drop to an AUHAL unit.
///
/// Requires microphone permission: macOS treats every audio input device as a
/// microphone, loopback drivers included.
final class AudioDeviceCapture: NSObject {
    /// Interleaved 48 kHz stereo Int16 LE — already the wire payload format.
    var onPCM: ((Data) -> Void)?

    private var session: AVCaptureSession?
    private let sampleQueue = DispatchQueue(label: "audioDeviceCaptureQueue", qos: .userInitiated)

    /// Per-second capture stats. `peak` is the diagnostic that matters: buffers
    /// arrive from a running loopback device whether or not anything is routed
    /// into it, so bytes/sec alone cannot tell "the tablet is silent because we
    /// are not sending" from "…because no app is actually playing to BlackHole".
    /// All four are touched only on `sampleQueue` and the stats timer, which is
    /// itself targeted at `sampleQueue`.
    private var statsBuffers = 0
    private var statsBytes = 0
    private var statsPeak: Int16 = 0
    private var statsTimer: DispatchSourceTimer?

    /// Watchdog state. Also on `sampleQueue` only.
    private var watchdogBuffers = 0
    private var watchdogTimer: DispatchSourceTimer?
    private var currentDeviceName: String?

    /// Audio input devices, for the settings picker. Excludes nothing: a
    /// loopback driver is indistinguishable from a real microphone at this
    /// layer, and guessing which is which by name would be fragile.
    /// Empty below macOS 14, where the `.microphone` discovery type does not
    /// exist. The alternative there is the long-deprecated `devices(for:)`, and
    /// this whole path is an opt-in extra: macOS 13 users keep the
    /// ScreenCaptureKit tap, which needs no driver and works fine. Not worth
    /// carrying a deprecated API for.
    static func availableDevices() -> [AVCaptureDevice] {
        guard #available(macOS 14.0, *) else { return [] }
        return AVCaptureDevice.DiscoverySession(
            deviceTypes: [.microphone],
            mediaType: .audio,
            position: .unspecified
        ).devices
    }

    static func device(named name: String) -> AVCaptureDevice? {
        availableDevices().first { $0.localizedName == name }
    }

    /// Requests microphone access, calling back on an arbitrary queue.
    /// Already-granted resolves synchronously without prompting.
    static func requestPermission(_ completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio, completionHandler: completion)
        case .denied, .restricted:
            completion(false)
        @unknown default:
            completion(false)
        }
    }

    /// Starts capture from `deviceName`. Throws rather than failing silently so
    /// the caller can fall back to the ScreenCaptureKit tap and say why.
    ///
    /// Serialised onto `sampleQueue` because the watchdog restarts capture from
    /// that queue while callers start and stop it from the main thread, and the
    /// session and timer state is shared between them. Safe against deadlock:
    /// nothing running on `sampleQueue` ever blocks — the delivery path only
    /// does an `async` hop.
    func start(deviceName: String) throws {
        try sampleQueue.sync { try startOnQueue(deviceName: deviceName) }
    }

    func stop() {
        sampleQueue.sync {
            currentDeviceName = nil
            stopSessionOnQueue()
        }
    }

    private func startOnQueue(deviceName: String) throws {
        currentDeviceName = nil
        stopSessionOnQueue()

        guard let device = AudioDeviceCapture.device(named: deviceName) else {
            throw AudioDeviceCaptureError.deviceNotFound(deviceName)
        }
        guard AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else {
            throw AudioDeviceCaptureError.permissionDenied
        }

        let newSession = AVCaptureSession()
        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: device)
        } catch {
            throw AudioDeviceCaptureError.inputCreationFailed(error.localizedDescription)
        }
        guard newSession.canAddInput(input) else {
            throw AudioDeviceCaptureError.inputRejected(deviceName)
        }
        newSession.addInput(input)

        let output = AVCaptureAudioDataOutput()
        // Demand the wire format outright, so no conversion is needed and a
        // device running at 44.1kHz or float32 cannot silently produce
        // wrong-speed or wrong-width audio on the tablet.
        output.audioSettings = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: AudioWireFormat.sampleRate,
            AVNumberOfChannelsKey: AudioWireFormat.channelCount,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]
        output.setSampleBufferDelegate(self, queue: sampleQueue)
        guard newSession.canAddOutput(output) else {
            throw AudioDeviceCaptureError.outputRejected
        }
        newSession.addOutput(output)

        // A loopback device's nominal sample rate follows whichever app is
        // driving it, so it can change under a running session. Log the failure
        // rather than handling it: silently dying is what makes "sometimes no
        // sound" undiagnosable.
        observeFailures(of: newSession)

        session = newSession
        currentDeviceName = deviceName
        newSession.startRunning()
        startStatsTimer()
        startWatchdog()
        debugLog("🎚️  Audio device capture started: \(deviceName)")
    }

    /// Tears the session down but leaves `currentDeviceName` alone, so the
    /// watchdog can restart the same device.
    private func stopSessionOnQueue() {
        guard let session = session else { return }
        statsTimer?.cancel()
        statsTimer = nil
        watchdogTimer?.cancel()
        watchdogTimer = nil
        NotificationCenter.default.removeObserver(self)
        session.stopRunning()
        self.session = nil
        debugLog("🎚️  Audio device capture stopped")
    }

    /// Restarts capture when the session has silently stopped delivering.
    ///
    /// An AVCaptureSession on a loopback device dies when the device's nominal
    /// sample rate changes underneath it — and a loopback driver's rate follows
    /// whichever app is playing, so it changes whenever you go from a 44.1kHz
    /// source to a 48kHz one. Measured on BlackHole 2ch: buffer delivery drops
    /// from 86/s to 0 within one second of the flip and never returns, while
    /// `session.isRunning` stays `true` and NO runtimeError or wasInterrupted
    /// notification is posted. The session lies about being alive, so the only
    /// reliable liveness signal is whether buffers are actually arriving.
    ///
    /// Removing `audioSettings` does not help — the native-format session dies
    /// the same way, so this is not about the format conversion.
    ///
    /// ponytail: a delivery watchdog rather than a
    /// `kAudioDevicePropertyNominalSampleRate` listener. Same recovery action
    /// either way (restart the session), and this also covers silent deaths we
    /// have not characterised. Ceiling: a measured ~600ms gap in the audio —
    /// up to 250ms to notice plus ~350ms for a session to start delivering
    /// again. Most of that is the restart, not the polling, so tightening the
    /// interval buys little; drop to an AUHAL input unit, which handles device
    /// reconfiguration itself, if the gap ever matters.
    private func startWatchdog() {
        watchdogBuffers = 0
        let timer = DispatchSource.makeTimerSource(queue: sampleQueue)
        // First check a full second in: a freshly started session takes a few
        // hundred ms to deliver its first buffer, and treating that as death
        // would restart in a loop.
        timer.schedule(deadline: .now() + 1, repeating: 0.25)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            guard self.watchdogBuffers == 0 else {
                self.watchdogBuffers = 0
                return
            }
            guard let deviceName = self.currentDeviceName else { return }
            debugLog("⚠️  Audio capture stopped delivering (the device's sample rate probably "
                + "changed) — restarting \(deviceName)")
            do {
                // Already on sampleQueue, so call the on-queue variant directly
                // — start() would deadlock on its own sync.
                try self.startOnQueue(deviceName: deviceName)
            } catch {
                debugLog("⚠️  Audio capture restart failed: \(error.localizedDescription) — audio is off "
                    + "until the server restarts")
            }
        }
        watchdogTimer = timer
        timer.resume()
    }

    private func observeFailures(of session: AVCaptureSession) {
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(sessionFailed(_:)),
            name: AVCaptureSession.runtimeErrorNotification,
            object: session
        )
        center.addObserver(
            self,
            selector: #selector(sessionInterrupted(_:)),
            name: AVCaptureSession.wasInterruptedNotification,
            object: session
        )
    }

    @objc private func sessionFailed(_ note: Notification) {
        let error = note.userInfo?[AVCaptureSessionErrorKey] as? NSError
        debugLog("⚠️  Audio capture session runtime error: \(error?.localizedDescription ?? "unknown") "
            + "— audio has stopped until the server restarts")
    }

    @objc private func sessionInterrupted(_ note: Notification) {
        debugLog("⚠️  Audio capture session interrupted: \(note.userInfo ?? [:])")
    }

    private func startStatsTimer() {
        let timer = DispatchSource.makeTimerSource(queue: sampleQueue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            debugLog("🎚️  AudioCapture: buffers=\(self.statsBuffers), bytes=\(self.statsBytes), "
                + "peak=\(self.statsPeak) (0 = nothing is routed into the device)")
            self.statsBuffers = 0
            self.statsBytes = 0
            self.statsPeak = 0
        }
        statsTimer = timer
        timer.resume()
    }

    /// Loudest sample in the buffer, as a magnitude. Cheap enough to run on
    /// every buffer: ~480 Int16 comparisons per 5ms at 48kHz stereo.
    static func peakMagnitude(of pcm: Data) -> Int16 {
        pcm.withUnsafeBytes { raw in
            raw.bindMemory(to: Int16.self).reduce(Int16(0)) { best, sample in
                // magnitude(of: Int16.min) overflows; clamp to .max instead.
                let magnitude = sample == Int16.min ? Int16.max : abs(sample)
                return magnitude > best ? magnitude : best
            }
        }
    }

    deinit {
        // Not stop(): the last strong reference can be dropped by the watchdog
        // handler itself, which runs ON sampleQueue, and stop()'s sync would
        // then deadlock on it. deinit means nothing else holds this object, so
        // exclusive access is already guaranteed without the hop.
        currentDeviceName = nil
        stopSessionOnQueue()
    }
}

extension AudioDeviceCapture: AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let data = AudioDeviceCapture.pcmData(from: sampleBuffer), !data.isEmpty else { return }
        statsBuffers += 1
        watchdogBuffers += 1
        statsBytes += data.count
        let peak = AudioDeviceCapture.peakMagnitude(of: data)
        if peak > statsPeak { statsPeak = peak }
        onPCM?(data)
    }

    /// Raw bytes of an interleaved-PCM sample buffer.
    ///
    /// Uses CMBlockBufferCopyDataBytes rather than borrowing the data pointer:
    /// a block buffer may be non-contiguous, in which case the pointer only
    /// covers the first segment and the tail would be dropped or misread.
    static func pcmData(from sampleBuffer: CMSampleBuffer) -> Data? {
        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { return nil }
        let length = CMBlockBufferGetDataLength(block)
        guard length > 0 else { return nil }

        var data = Data(count: length)
        let status: OSStatus = data.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return OSStatus(-1) }
            return CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: base)
        }
        guard status == kCMBlockBufferNoErr else {
            debugLog("Audio device capture: CMBlockBufferCopyDataBytes failed (\(status))")
            return nil
        }
        return data
    }
}

enum AudioDeviceCaptureError: Error, LocalizedError {
    case deviceNotFound(String)
    case permissionDenied
    case inputCreationFailed(String)
    case inputRejected(String)
    case outputRejected

    var errorDescription: String? {
        switch self {
        case .deviceNotFound(let name):
            return "Audio device '\(name)' not found. Is the loopback driver still installed?"
        case .permissionDenied:
            return "Microphone permission denied. macOS treats loopback drivers as microphones; "
                + "grant access in System Settings → Privacy & Security → Microphone."
        case .inputCreationFailed(let message):
            return "Could not open the audio device: \(message)"
        case .inputRejected(let name):
            return "Capture session rejected the audio device '\(name)'."
        case .outputRejected:
            return "Capture session rejected the audio output."
        }
    }
}
