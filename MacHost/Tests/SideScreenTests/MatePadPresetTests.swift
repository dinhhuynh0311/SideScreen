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
