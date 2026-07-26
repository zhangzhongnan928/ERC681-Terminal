import XCTest

@MainActor
final class StoreScreenshotUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["OPK_UI_TEST_KEYCHAIN_NAMESPACE"] =
            "store-screenshots-\(UUID().uuidString)"
        let isOfflineReviewerDemoCapture = name.contains(
            "testCaptureOfflineReviewerDemoStoreScreenshots"
        )
        if isOfflineReviewerDemoCapture {
            app.launchEnvironment["OPK_UI_TEST_FORBID_LIVE_BOOTSTRAP"] = "1"
        }
        app.launch()
        if !isOfflineReviewerDemoCapture {
            let liveButton = app.buttons["launchOpenLiveTerminalButton"]
            XCTAssertTrue(liveButton.waitForExistence(timeout: 5))
            liveButton.tap()
        }
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

    func testCaptureOfflineReviewerDemoStoreScreenshots() {
        XCTAssertTrue(element("terminalLaunchChooser").waitForExistence(timeout: 5))
        capture("01-cold-launch-choice")

        let demoButton = app.buttons["launchReviewerDemoButton"]
        XCTAssertTrue(demoButton.exists)
        XCTAssertEqual(demoButton.label, "Explore offline demo")
        demoButton.tap()

        let safetyLabel = element("reviewerDemoSafetyLabel")
        XCTAssertTrue(safetyLabel.waitForExistence(timeout: 5))
        XCTAssertEqual(
            safetyLabel.label,
            "OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS"
        )
        XCTAssertTrue(element("reviewerDemoQRCode").exists)
        XCTAssertEqual(element("reviewerDemoPaymentStatus").label, "Waiting for payment")
        capture("02-offline-demo-waiting")

        app.buttons["reviewerDemoSimulatePaymentButton"].tap()
        XCTAssertEqual(element("reviewerDemoPaymentStatus").label, "Paid")
        capture("03-offline-demo-paid")

        selectDemoTab("History")
        XCTAssertTrue(app.navigationBars["Demo History"].waitForExistence(timeout: 5))
        XCTAssertTrue(safetyLabel.exists)
        XCTAssertEqual(element("reviewerDemoHistoryStatus").value as? String, "Paid")
        capture("04-offline-demo-history")

        selectDemoTab("Settlement")
        XCTAssertTrue(app.navigationBars["Demo Settlement"].waitForExistence(timeout: 5))
        XCTAssertTrue(safetyLabel.exists)
        let disabledSettlement = app.buttons["reviewerDemoSettlementDisabledButton"]
        XCTAssertTrue(disabledSettlement.exists)
        XCTAssertFalse(disabledSettlement.isEnabled)
        capture("05-offline-demo-settlement")
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

    private func selectDemoTab(
        _ label: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let tab = element(label)
        XCTAssertTrue(tab.waitForExistence(timeout: 5), file: file, line: line)
        XCTAssertTrue(tab.isHittable, file: file, line: line)
        tab.tap()
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }
}
