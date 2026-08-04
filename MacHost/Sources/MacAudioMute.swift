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
