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
        let p = AudioPCM.audioPacket(messageType: 16, pcm: Data([0xAA, 0xBB]))
        XCTAssertEqual(Array(p), [16, 0x00, 0x00, 0x00, 0x02, 0xAA, 0xBB])
    }

    func testEmptyPcmStillProducesWellFormedHeader() {
        let p = AudioPCM.audioPacket(messageType: 16, pcm: Data())
        XCTAssertEqual(Array(p), [16, 0x00, 0x00, 0x00, 0x00])
    }
}
