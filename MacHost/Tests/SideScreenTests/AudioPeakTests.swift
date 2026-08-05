import XCTest
@testable import SideScreen

/// `peakMagnitude` is how we tell "the tablet is silent because nothing is
/// routed into BlackHole" from "…because we are not sending it", so a wrong
/// answer here would misdirect the next diagnosis.
final class AudioPeakTests: XCTestCase {
    private func pcm(_ samples: [Int16]) -> Data {
        var data = Data()
        for s in samples { withUnsafeBytes(of: s.littleEndian) { data.append(contentsOf: $0) } }
        return data
    }

    func testSilenceReadsZero() {
        XCTAssertEqual(AudioDeviceCapture.peakMagnitude(of: pcm([0, 0, 0, 0])), 0)
    }

    func testReportsLoudestMagnitudeRegardlessOfSign() {
        XCTAssertEqual(AudioDeviceCapture.peakMagnitude(of: pcm([100, -6784, 42])), 6784)
    }

    func testMostNegativeSampleDoesNotOverflow() {
        // abs(Int16.min) traps; a real DAC does emit -32768.
        XCTAssertEqual(AudioDeviceCapture.peakMagnitude(of: pcm([Int16.min])), Int16.max)
    }

    func testEmptyBufferIsZero() {
        XCTAssertEqual(AudioDeviceCapture.peakMagnitude(of: Data()), 0)
    }
}
