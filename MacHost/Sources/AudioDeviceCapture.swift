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
/// downstream — the wire format, wire type 13, and the Android AudioPlayer — is
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
    func start(deviceName: String) throws {
        stop()

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

        session = newSession
        newSession.startRunning()
        debugLog("🎚️  Audio device capture started: \(deviceName)")
    }

    func stop() {
        guard let session = session else { return }
        session.stopRunning()
        self.session = nil
        debugLog("🎚️  Audio device capture stopped")
    }

    deinit {
        stop()
    }
}

extension AudioDeviceCapture: AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let data = AudioDeviceCapture.pcmData(from: sampleBuffer), !data.isEmpty else { return }
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
