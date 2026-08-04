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
