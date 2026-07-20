import XCTest

@MainActor
final class KeyboardDismissalUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["OPK_UI_TEST_KEYCHAIN_NAMESPACE"] = UUID().uuidString
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    func testDecimalPadCanBeDismissedBeforeCreatingPayment() {
        let amountField = app.textFields["Sale amount"]
        XCTAssertTrue(amountField.waitForExistence(timeout: 5))

        assertKeyboardCanBeDismissed(from: amountField)

        let createPaymentButton = app.buttons["Create payment QR"]
        XCTAssertTrue(createPaymentButton.exists)
        XCTAssertFalse(createPaymentButton.isEnabled)
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
}
