import XCTest

@MainActor
final class KeyboardDismissalUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
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
        XCTAssertTrue(createPaymentButton.isHittable)
    }

    func testSettingsKeyboardsCanBeDismissedForNumberAndAddressFields() {
        app.tabBars.buttons["Settings"].tap()

        let chainIDField = app.textFields["Chain ID"]
        XCTAssertTrue(chainIDField.waitForExistence(timeout: 5))
        assertKeyboardCanBeDismissed(from: chainIDField)

        let factoryField = app.textFields["Factory address"]
        XCTAssertTrue(factoryField.waitForExistence(timeout: 5))
        if !factoryField.isHittable {
            app.swipeUp()
        }
        assertKeyboardCanBeDismissed(from: factoryField)
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
