import XCTest

@MainActor
final class StoreScreenshotUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["OPK_UI_TEST_KEYCHAIN_NAMESPACE"] =
            "store-screenshots-\(UUID().uuidString)"
        app.launch()
        let liveButton = app.buttons["launchOpenLiveTerminalButton"]
        XCTAssertTrue(liveButton.waitForExistence(timeout: 5))
        liveButton.tap()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    func testCaptureFirstRunStoreScreenshots() {
        XCTAssertTrue(app.navigationBars["Checkout"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["checkoutReadinessActionButton"].exists)
        capture("01-first-run-checkout")

        selectTab("History")
        XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No invoices"].exists)
        capture("02-history")

        selectTab("Settle")
        XCTAssertTrue(app.navigationBars["Settlement"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No funded sessions to settle"].exists)
        capture("03-settlement")

        selectTab("Settings")
        XCTAssertTrue(app.navigationBars["Terminal Setup"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["createOperatorWalletButton"].exists)
        capture("04-terminal-setup")

        let privacyPolicy = app.buttons["privacyPolicyLink"]
        for _ in 0..<8 where !privacyPolicy.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(privacyPolicy.isHittable)
        XCTAssertTrue(app.buttons["supportLink"].isHittable)
        capture("05-privacy-support")
    }

    private func selectTab(
        _ label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let tab = app.tabBars.buttons[label]
        XCTAssertTrue(tab.waitForExistence(timeout: 5), file: file, line: line)
        tab.tap()
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
