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
