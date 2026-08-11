import Foundation
import OPKTerminalCore
import XCTest
@testable import OPKTerminalApp

final class ReviewerDemoTests: XCTestCase {
    func testRequiredSafetyLabelsAreExplicit() {
        XCTAssertEqual(
            ReviewerDemoCopy.safetyBannerLabel,
            "OFFLINE DEMO · BASE MAINNET FORMAT · SIMULATED · NO NETWORK · NO REAL FUNDS"
        )
        XCTAssertTrue(ReviewerDemoCopy.isolationDetail.contains("Offline"))
        XCTAssertTrue(ReviewerDemoCopy.isolationDetail.contains("in-memory"))
        XCTAssertTrue(ReviewerDemoCopy.isolationDetail.contains("product tour"))
    }

    func testSampleMarkerIsNonPaymentBaseMainnetDemoData() {
        XCTAssertEqual(
            ReviewerDemoCopy.sampleMarker,
            "opk-demo:v1?network=base-mainnet&chainId=8453&simulated=true"
        )
        XCTAssertFalse(ReviewerDemoCopy.sampleMarker.hasPrefix("ethereum:"))
        XCTAssertThrowsError(
            try ERC681TransferRequest.parse(
                ReviewerDemoCopy.sampleMarker,
                expectedChainID: 8_453
            )
        )
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
