import XCTest
@testable import OPKTerminalApp

final class SettingsExternalLinksTests: XCTestCase {
    func testPrivacyPolicyLinkUsesRequiredPublicLabelAndURL() {
        XCTAssertEqual(SettingsExternalLinks.privacyPolicy.label, "Privacy Policy")
        XCTAssertEqual(
            SettingsExternalLinks.privacyPolicy.destination.absoluteString,
            "https://www.openpasskey.com/privacy"
        )
    }

    func testSupportLinkUsesPublicAboutPage() {
        XCTAssertEqual(SettingsExternalLinks.support.label, "Support")
        XCTAssertEqual(
            SettingsExternalLinks.support.destination.absoluteString,
            "https://www.openpasskey.com/about"
        )
    }
}
