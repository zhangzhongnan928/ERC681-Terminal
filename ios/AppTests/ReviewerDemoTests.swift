import Foundation
import OPKTerminalCore
import XCTest
@testable import OPKTerminalApp

final class ReviewerDemoTests: XCTestCase {
    func testRequiredSafetyLabelsAreExplicit() {
        XCTAssertEqual(
            ReviewerDemoCopy.safetyBannerLabel,
            "OFFLINE DEMO · BASE SEPOLIA TESTNET · SIMULATED · NO NETWORK · NO REAL FUNDS"
        )
        XCTAssertTrue(ReviewerDemoCopy.isolationDetail.contains("Offline"))
        XCTAssertTrue(ReviewerDemoCopy.isolationDetail.contains("in-memory"))
    }

    func testSamplePaymentURIIsCanonicalBaseSepoliaERC681() throws {
        let request = try ERC681TransferRequest.parse(
            ReviewerDemoCopy.sampleERC681URI,
            expectedChainID: 84_532
        )

        XCTAssertEqual(request.canonicalString, ReviewerDemoCopy.sampleERC681URI)
        XCTAssertEqual(request.amount.decimalString, "1000000")
        XCTAssertEqual(request.token.hex, ReviewerDemoCopy.sampleToken)
        XCTAssertEqual(request.recipient.hex, ReviewerDemoCopy.sampleReceiver)
    }

    func testDemoStateTransitionsOnlyFromWaitingToPaidAndResets() {
        var state = ReviewerDemoState()
        XCTAssertEqual(state.paymentState, .waiting)
        XCTAssertEqual(state.selectedTab, .checkout)

        state.simulatePayment()
        state.selectedTab = .settlement
        XCTAssertEqual(state.paymentState, .paid)

        state.reset()
        XCTAssertEqual(state, ReviewerDemoState())
        XCTAssertEqual(state.paymentState, .waiting)
        XCTAssertEqual(state.selectedTab, .checkout)
    }

    func testDemoSelectionDoesNotRequireLiveDependencies() {
        var launchState = TerminalLaunchState()
        XCTAssertFalse(launchState.requiresLiveDependencies)

        launchState.openReviewerDemo()
        XCTAssertEqual(launchState.destination, .reviewerDemo)
        XCTAssertFalse(launchState.requiresLiveDependencies)

        launchState.returnToChooser()
        XCTAssertEqual(launchState.destination, .chooser)
        XCTAssertFalse(launchState.requiresLiveDependencies)

        launchState.openLiveTerminal()
        XCTAssertEqual(launchState.destination, .liveTerminal)
        XCTAssertTrue(launchState.requiresLiveDependencies)

        launchState.openReviewerDemo()
        XCTAssertEqual(launchState.destination, .liveTerminal)
        launchState.returnToChooser()
        XCTAssertEqual(launchState.destination, .liveTerminal)
        XCTAssertTrue(launchState.requiresLiveDependencies)
    }

    func testDemoSourceHasNoRealAppOrExternalIODependencies() throws {
        let iosDirectory = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let demoSourceURLs = [
            iosDirectory.appendingPathComponent("App/Sources/ReviewerDemo.swift"),
            iosDirectory.appendingPathComponent("App/Sources/QRCodeImage.swift"),
        ]
        let forbiddenReferences = [
            "import SwiftData",
            "@Query",
            "@EnvironmentObject",
            "AppModel",
            "UserDefaults",
            "Keychain",
            "LocalAuthentication",
            "URLSession",
            "JSONRPC",
            "OperatorWallet",
            "SettlementCoordinator",
            "UIPasteboard",
            "modelContext",
            "Task {",
        ]

        for sourceURL in demoSourceURLs {
            let source = try String(contentsOf: sourceURL, encoding: .utf8)
            for reference in forbiddenReferences {
                XCTAssertFalse(
                    source.contains(reference),
                    "Reviewer demo dependency \(sourceURL.lastPathComponent) must not reference \(reference)"
                )
            }
        }
    }

    func testColdLaunchRootHasNoLiveDependencyConstruction() throws {
        let iosDirectory = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let appSource = try String(
            contentsOf: iosDirectory.appendingPathComponent(
                "App/Sources/OPKTerminalApp.swift"
            ),
            encoding: .utf8
        )
        let launchSource = try String(
            contentsOf: iosDirectory.appendingPathComponent(
                "App/Sources/TerminalLaunch.swift"
            ),
            encoding: .utf8
        )
        let forbiddenColdLaunchReferences = [
            "import SwiftData",
            "OPKTerminalOperator",
            "ModelContainer",
            "AppModel",
            "UserDefaults",
            "Keychain",
            "LocalAuthentication",
            "URLSession",
            "JSONRPC",
            "Task {",
        ]

        for source in [appSource, launchSource] {
            for reference in forbiddenColdLaunchReferences {
                XCTAssertFalse(
                    source.contains(reference),
                    "Cold-launch routing must not reference \(reference)"
                )
            }
        }
    }
}
