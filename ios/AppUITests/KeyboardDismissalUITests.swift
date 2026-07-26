import XCTest

@MainActor
final class KeyboardDismissalUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["OPK_UI_TEST_KEYCHAIN_NAMESPACE"] = UUID().uuidString
        let isOfflineReviewerDemoTest = name.contains(
            "testColdLaunchOfflineReviewerDemoIsIsolatedLabeledAndReset"
        )
        if isOfflineReviewerDemoTest {
            app.launchEnvironment["OPK_UI_TEST_FORBID_LIVE_BOOTSTRAP"] = "1"
        }
        if name.contains("testReadyCheckoutFixtureSwitchesOneWholePaymentProfileAtATime") {
            app.launchEnvironment["OPK_UI_TEST_CHECKOUT_FIXTURE"] = "ready"
        }
        app.launch()
        if !isOfflineReviewerDemoTest {
            openLiveTerminal()
        }
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    func testCheckoutRoutesUnreadyTerminalToSettingsWithoutShowingKeypad() {
        XCTAssertTrue(app.navigationBars["Checkout"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.otherElements["checkoutReadinessBlocker"].exists)
        XCTAssertFalse(app.buttons["checkoutKey1"].exists)

        let setupButton = app.buttons["checkoutReadinessActionButton"]
        XCTAssertTrue(setupButton.exists)
        XCTAssertEqual(setupButton.label, "Finish terminal setup")
        setupButton.tap()

        XCTAssertTrue(app.navigationBars["Terminal Setup"].waitForExistence(timeout: 5))
    }

    func testColdLaunchOfflineReviewerDemoIsIsolatedLabeledAndReset() {
        XCTAssertTrue(element(identifier: "terminalLaunchChooser").waitForExistence(timeout: 5))

        let demoButton = app.buttons["launchReviewerDemoButton"]
        XCTAssertTrue(demoButton.exists)
        XCTAssertEqual(demoButton.label, "Explore offline demo")
        demoButton.tap()

        XCTAssertTrue(element(identifier: "reviewerDemoRoot").waitForExistence(timeout: 5))
        XCTAssertEqual(
            element(identifier: "reviewerDemoSafetyLabel").label,
            "OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS"
        )
        XCTAssertTrue(element(identifier: "reviewerDemoQRCode").exists)
        XCTAssertEqual(
            element(identifier: "reviewerDemoPaymentStatus").label,
            "Waiting for payment"
        )

        app.buttons["reviewerDemoSimulatePaymentButton"].tap()
        XCTAssertEqual(element(identifier: "reviewerDemoPaymentStatus").label, "Paid")

        app.tabBars.buttons["History"].tap()
        XCTAssertTrue(app.navigationBars["Demo History"].waitForExistence(timeout: 5))
        XCTAssertEqual(
            element(identifier: "reviewerDemoHistoryStatus").value as? String,
            "Paid"
        )

        app.tabBars.buttons["Settlement"].tap()
        XCTAssertTrue(app.navigationBars["Demo Settlement"].waitForExistence(timeout: 5))
        let settlementButton = app.buttons["reviewerDemoSettlementDisabledButton"]
        XCTAssertTrue(settlementButton.exists)
        XCTAssertFalse(settlementButton.isEnabled)

        app.buttons["reviewerDemoCloseButton"].tap()
        XCTAssertTrue(element(identifier: "terminalLaunchChooser").waitForExistence(timeout: 5))

        app.buttons["launchReviewerDemoButton"].tap()
        XCTAssertTrue(element(identifier: "reviewerDemoQRCode").waitForExistence(timeout: 5))
        XCTAssertEqual(
            element(identifier: "reviewerDemoPaymentStatus").label,
            "Waiting for payment"
        )
    }

    func testFirstRunAllowsOperatorBeforePINAndPINKeyboardCanBeDismissed() {
        app.tabBars.buttons["Settings"].tap()

        let operatorButton = app.buttons["createOperatorWalletButton"]
        XCTAssertTrue(operatorButton.waitForExistence(timeout: 5))
        XCTAssertTrue(operatorButton.isEnabled)

        let pinField = app.secureTextFields["createAdminPIN"]
        XCTAssertTrue(pinField.waitForExistence(timeout: 5))
        if !pinField.isHittable {
            app.swipeUp()
        }
        assertKeyboardCanBeDismissed(from: pinField)
        XCTAssertTrue(app.buttons["Create local admin PIN"].exists)
    }

    func testReadyCheckoutFixtureSupportsExactKeypadAmountAndAccessibleQRAction() {
        app.terminate()
        app.launchEnvironment["OPK_UI_TEST_CHECKOUT_FIXTURE"] = "ready"
        app.launch()
        openLiveTerminal()

        XCTAssertTrue(app.navigationBars["Checkout"].waitForExistence(timeout: 5))

        let readyStatus = element(identifier: "checkoutReadyStatus")
        XCTAssertTrue(readyStatus.waitForExistence(timeout: 5))
        XCTAssertEqual(readyStatus.label, "Ready")

        let networkStatus = element(identifier: "checkoutNetworkStatus")
        XCTAssertTrue(networkStatus.exists)
        XCTAssertEqual(networkStatus.label, "Base Sepolia testnet")

        let qrButton = app.buttons["showPaymentQRButton"]
        XCTAssertTrue(qrButton.exists)
        XCTAssertFalse(qrButton.isEnabled)

        let decimalButton = app.buttons["checkoutDecimalKey"]
        XCTAssertTrue(decimalButton.exists)
        XCTAssertEqual(decimalButton.label, "Decimal point")

        let backspaceButton = app.buttons["checkoutBackspaceKey"]
        XCTAssertTrue(backspaceButton.exists)
        XCTAssertEqual(backspaceButton.label, "Delete last digit")

        let oneButton = app.buttons["checkoutKey1"]
        XCTAssertTrue(oneButton.exists)
        XCTAssertTrue(oneButton.isHittable)
        for _ in 0..<20 {
            oneButton.tap()
        }

        let amount = "11111111111111111111"
        let exactAmount = "\(amount) AUDM"

        let amountDisplay = element(identifier: "checkoutAmountDisplay")
        XCTAssertEqual(amountDisplay.label, "Checkout amount")
        XCTAssertEqual(amountDisplay.value as? String, exactAmount)

        let exactAmountReview = element(identifier: "checkoutExactAmountReview")
        XCTAssertTrue(exactAmountReview.exists)
        XCTAssertEqual(exactAmountReview.label, "Exact payment amount")
        XCTAssertEqual(exactAmountReview.value as? String, exactAmount)

        XCTAssertTrue(qrButton.isEnabled)
        XCTAssertEqual(qrButton.label, "Show payment QR for \(exactAmount)")

        let clearButton = app.buttons["checkoutClearButton"]
        XCTAssertTrue(clearButton.exists)
        XCTAssertEqual(clearButton.label, "Clear amount")
    }

    func testReadyCheckoutFixtureSwitchesOneWholePaymentProfileAtATime() {
        XCTAssertTrue(app.navigationBars["Checkout"].waitForExistence(timeout: 5))
        let picker = app.buttons["checkoutPaymentProfilePicker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 5))
        XCTAssertTrue((picker.value as? String)?.contains("AUDM · Base Sepolia") == true)
        XCTAssertTrue((picker.value as? String)?.contains("Token 0x7ffba6…e211") == true)
        XCTAssertEqual(element(identifier: "checkoutNetworkStatus").label, "Base Sepolia testnet")

        picker.tap()
        let usdc = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "USDC · Base Sepolia")
        ).firstMatch
        XCTAssertTrue(usdc.waitForExistence(timeout: 3))
        usdc.tap()

        XCTAssertTrue((picker.value as? String)?.contains("USDC · Base Sepolia") == true)
        XCTAssertTrue((picker.value as? String)?.contains("Token 0x888888…8888") == true)
        XCTAssertEqual(
            element(identifier: "checkoutNetworkStatus").label,
            "Base Sepolia testnet"
        )
        XCTAssertEqual(element(identifier: "checkoutAmountDisplay").value as? String, "0.00 USDC")
    }

    func testCheckoutSetupActionRemainsReachableInLandscapeAtAccessibilityXXXL() {
        app.terminate()
        app.launchArguments += [
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL",
        ]
        XCUIDevice.shared.orientation = .landscapeLeft
        addTeardownBlock {
            XCUIDevice.shared.orientation = .portrait
        }
        app.launch()
        openLiveTerminal()

        XCTAssertTrue(app.navigationBars["Checkout"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Create operator wallet"].exists)
        XCTAssertTrue(
            app.staticTexts[
                "Create the device-local operator before pairing this terminal."
            ].exists
        )

        let setupButton = app.buttons["checkoutReadinessActionButton"]
        XCTAssertTrue(setupButton.waitForExistence(timeout: 5))

        let blockerScrollView = app.scrollViews.firstMatch
        XCTAssertTrue(blockerScrollView.exists)
        for _ in 0..<6 where !setupButton.isHittable {
            blockerScrollView.swipeUp()
        }

        XCTAssertTrue(setupButton.isHittable)
        setupButton.tap()
        XCTAssertTrue(app.navigationBars["Terminal Setup"].waitForExistence(timeout: 5))
    }

    private func assertKeyboardCanBeDismissed(
        from field: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(field.isHittable, file: file, line: line)
        field.tap()

        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 3), file: file, line: line)

        let dismissButton = app.buttons["keyboardDismissButton"]
        XCTAssertTrue(dismissButton.waitForExistence(timeout: 3), file: file, line: line)
        dismissButton.tap()

        XCTAssertTrue(keyboard.waitForNonExistence(timeout: 3), file: file, line: line)
    }

    private func element(identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func openLiveTerminal(
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let liveButton = app.buttons["launchOpenLiveTerminalButton"]
        XCTAssertTrue(liveButton.waitForExistence(timeout: 5), file: file, line: line)
        liveButton.tap()
    }
}
