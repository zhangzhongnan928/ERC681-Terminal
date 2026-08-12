import XCTest
@testable import OPKTerminalApp
import OPKTerminalCore
import OPKTerminalOperator
import OPKTerminalRPC
import SwiftData

final class ReceiptParityTests: XCTestCase {
    func testMerchantProfileCanonicalizesNameAndValidAustralianABN() throws {
        let profile = try MerchantReceiptProfile(
            name: "  Blue   Brew  ",
            abn: "51 824 753 556"
        )

        XCTAssertEqual(profile.name, "Blue Brew")
        XCTAssertEqual(profile.abn, "51 824 753 556")
        XCTAssertThrowsError(
            try MerchantReceiptProfile(name: "Blue Brew", abn: "12 345 678 901")
        )
        XCTAssertThrowsError(
            try MerchantReceiptProfile(name: "Blue Brew", abn: "٥١ ٨٢٤ ٧٥٣ ٥٥٦")
        )
    }

    func testSettingsV3DefaultsReceiptIdentityAndAutoSweepOff() throws {
        let currentData = try JSONEncoder().encode(AppSettings())
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: currentData) as? [String: Any]
        )
        object["schemaVersion"] = 3
        object.removeValue(forKey: "merchantReceiptName")
        object.removeValue(forKey: "merchantReceiptABN")
        object.removeValue(forKey: "autoSweepEnabled")
        object.removeValue(forKey: "dismissedAutoSweepFingerprints")

        let decoded = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        )

        XCTAssertEqual(decoded.merchantReceiptProfile, .default)
        XCTAssertFalse(decoded.autoSweepEnabled)
    }

    func testReceiptAndAutoSweepSettingsDoNotInvalidatePaymentConfiguration() throws {
        let original = AppSettings()
        let changed = try original
            .updatingMerchantReceiptProfile(name: "Blue Brew", abn: "51 824 753 556")
            .updatingAutoSweepEnabled(true)

        XCTAssertTrue(original.hasSamePaymentConfiguration(as: changed))
        let cleared = changed.clearingProvisioning()
        XCTAssertFalse(cleared.autoSweepEnabled)
        XCTAssertEqual(cleared.merchantReceiptName, "Blue Brew")
        XCTAssertEqual(cleared.merchantReceiptABN, "51 824 753 556")
    }

    func testBaseScanMapsCanonicalBaseTransactionsOnly() throws {
        let hash = "0x" + String(repeating: "a", count: 64)
        XCTAssertEqual(
            try BaseScanExplorer.transactionURL(chainID: 84_532, hash: hash).absoluteString,
            "https://sepolia.basescan.org/tx/\(hash)"
        )
        XCTAssertEqual(
            try BaseScanExplorer.transactionURL(chainID: 8_453, hash: hash).absoluteString,
            "https://basescan.org/tx/\(hash)"
        )
        XCTAssertThrowsError(
            try BaseScanExplorer.transactionURL(chainID: 1, hash: hash)
        )
        XCTAssertThrowsError(
            try BaseScanExplorer.transactionURL(chainID: 84_532, hash: "0x1234")
        )
    }

    func testReceiptFormattingIsExactAndStableAcrossDeviceTimezones() throws {
        let document = ReceiptDocument(
            merchantName: "Blue Brew",
            merchantABN: "51 824 753 556",
            displayAmount: "10.50",
            tokenSymbol: "AUD",
            networkName: "Base",
            terminalAddress: "0x" + String(repeating: "1", count: 40),
            paymentTransactionHash: "0x" + String(repeating: "a", count: 64),
            receiptNumber: 42,
            paidAtEpochSeconds: 1_735_689_600,
            explorerURL: try XCTUnwrap(
                URL(string: "https://sepolia.basescan.org/tx/0x" + String(repeating: "a", count: 64))
            )
        )
        let priorTimezone = NSTimeZone.default
        defer { NSTimeZone.default = priorTimezone }

        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "Australia/Sydney"))
        let sydney = ReceiptFormatter.format(document)
        NSTimeZone.default = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let losAngeles = ReceiptFormatter.format(document)

        XCTAssertEqual(sydney, losAngeles)
        XCTAssertEqual(
            sydney,
            """
                       Blue Brew
                   ABN 51 824 753 556
                    PAYMENT RECEIPT
            Date (UTC):   01 Jan 2025  00:00
            Receipt:                     #42
            TOTAL                  10.50 AUD
            Paid: 10.50 AUD (Base)
            Terminal: 0x11111...11111
            Tx Hash:  0xaaaaa...aaaaa
                 Powered by OpenPasskey
              Scan for transaction details

            """
        )
    }

    func testReceiptSurvivesCanonicalZeroBalanceAndSettlementButClearsOnReorg() throws {
        let fixture = try makeInvoice()
        let invoice = fixture.invoice
        let fundingCursor = PaymentConfirmationCursor(
            blockNumber: 101,
            blockHash: try hash("1")
        )
        try invoice.apply(
            PaymentObservation(
                invoiceID: fixture.request.invoiceID,
                blockNumber: 102,
                blockHash: try hash("2"),
                balance: fixture.request.expectedAmount,
                status: .paid(received: fixture.request.expectedAmount),
                thresholdBlock: fundingCursor.blockNumber,
                thresholdBlockHash: fundingCursor.blockHash
            )
        )
        XCTAssertTrue(
            try invoice.applyIncomingPaymentEvidence(
                PaymentTransactionEvidence(
                    transactionHash: try hash("a"),
                    payer: try address("3"),
                    blockNumber: 101,
                    blockHash: fundingCursor.blockHash,
                    blockTimestamp: 1_735_689_600
                ),
                expectedFundingCursor: fundingCursor
            )
        )
        let originalReceipt = try XCTUnwrap(invoice.receiptDocument())

        let zeroObservation = PaymentObservation(
            invoiceID: fixture.request.invoiceID,
            blockNumber: 110,
            blockHash: try hash("4"),
            balance: .zero,
            status: .waiting,
            thresholdBlock: nil,
            thresholdBlockHash: nil,
            validatedPreviousCursors: [fundingCursor]
        )
        try invoice.apply(zeroObservation, cumulativeConfirmedSweptAmount: .zero)
        XCTAssertEqual(try invoice.receiptDocument(), originalReceipt)

        try invoice.apply(
            zeroObservation,
            cumulativeConfirmedSweptAmount: fixture.request.expectedAmount
        )
        XCTAssertEqual(try invoice.receiptDocument(), originalReceipt)

        let replacementFork = PaymentObservation(
            invoiceID: fixture.request.invoiceID,
            blockNumber: 111,
            blockHash: try hash("5"),
            balance: .zero,
            status: .waiting,
            thresholdBlock: nil,
            thresholdBlockHash: nil,
            validatedPreviousCursors: []
        )
        try invoice.apply(replacementFork, cumulativeConfirmedSweptAmount: .zero)
        XCTAssertFalse(invoice.hasIncomingPaymentEvidence)
        XCTAssertNil(try invoice.receiptDocument())
    }

    func testLegacyOrSettlementOnlyInvoiceCannotProduceReceipt() throws {
        let fixture = try makeInvoice(receiptEligible: false)
        fixture.invoice.statusLabel = "Settled"

        XCTAssertNil(try fixture.invoice.receiptDocument())
        XCTAssertNil(fixture.invoice.paymentTransactionHash)
    }

    @MainActor
    func testHistoryRevalidationClearsCanonicalReplacementBeforeShowingReceipt() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        AppPreferences.saveSettings(AppSettings())
        let fixture = try makeEvidencedInvoice()
        let container = try testContainer()
        container.mainContext.insert(fixture.invoice)
        try container.mainContext.save()
        let model = makeModel(container: container) { _, _ in
            throw PaymentEvidenceResolutionError.canonicalBlockChanged(blockNumber: 101)
        }

        let document = await model.ensureReceiptDocument(for: fixture.invoice.invoiceID)

        XCTAssertNil(document)
        XCTAssertFalse(fixture.invoice.hasIncomingPaymentEvidence)
        XCTAssertNil(fixture.invoice.paymentTransactionHash)
    }

    @MainActor
    func testHistoryRevalidationPreservesStoredEvidenceOnTransientFailureButRefusesPrint() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        AppPreferences.saveSettings(AppSettings())
        let fixture = try makeEvidencedInvoice()
        let originalHash = fixture.invoice.paymentTransactionHash
        let container = try testContainer()
        container.mainContext.insert(fixture.invoice)
        try container.mainContext.save()
        let model = makeModel(container: container) { _, _ in
            throw URLError(.timedOut)
        }

        let document = await model.ensureReceiptDocument(for: fixture.invoice.invoiceID)

        XCTAssertNil(document)
        XCTAssertTrue(fixture.invoice.hasIncomingPaymentEvidence)
        XCTAssertEqual(fixture.invoice.paymentTransactionHash, originalHash)
    }

    func testPaidReceiptUsesButtonOnlyPresentationToAvoidCompetingSheets() {
        XCTAssertEqual(
            PaymentReceiptPresentationPolicy.presentation(for: .paid(received: UInt256(1))),
            .buttonOnly
        )
        XCTAssertEqual(
            PaymentReceiptPresentationPolicy.presentation(for: .waiting),
            .hidden
        )
    }

    func testAutoSweepSelectsOnlyUnsuppressedNewReceiptEvidencedPayment() throws {
        let fixture = try makeEvidencedInvoice()
        let fingerprint = try XCTUnwrap(fixture.invoice.autoSweepFingerprint)
        let now = Date(timeIntervalSince1970: 1_735_689_700)

        XCTAssertEqual(
            AutoSweepPolicy.selectCandidate(
                from: [fixture.invoice],
                excludingActiveInvoiceIDs: [],
                suppressedFingerprints: [],
                retryAfter: [:],
                now: now
            ),
            AutoSweepCandidate(invoiceID: fixture.invoice.invoiceID, fingerprint: fingerprint)
        )
        XCTAssertNil(
            AutoSweepPolicy.selectCandidate(
                from: [fixture.invoice],
                excludingActiveInvoiceIDs: [],
                suppressedFingerprints: [fingerprint],
                retryAfter: [:],
                now: now
            )
        )
        XCTAssertNil(
            AutoSweepPolicy.selectCandidate(
                from: [fixture.invoice],
                excludingActiveInvoiceIDs: [],
                suppressedFingerprints: [],
                retryAfter: [fingerprint: now.addingTimeInterval(60)],
                now: now
            )
        )

        fixture.invoice.confirmedCumulativeSweptAmount = "1"
        fixture.invoice.cumulativeSweptAtObservation = "1"
        XCTAssertNil(fixture.invoice.autoSweepFingerprint)
    }

    func testAutoSweepAttemptGateCoalescesTriggersAndQueuesOnlyANewerGeneration() throws {
        var gate = AutoSweepAttemptGate()
        let first = try XCTUnwrap(gate.acquire(enabled: true))

        XCTAssertNil(gate.acquire(enabled: true))
        XCTAssertTrue(gate.isCurrent(first, enabled: true))
        XCTAssertFalse(gate.release(first, enabled: true))

        let stale = try XCTUnwrap(gate.acquire(enabled: true))
        gate.invalidate()
        XCTAssertFalse(gate.isCurrent(stale, enabled: true))
        XCTAssertNil(gate.acquire(enabled: true))
        XCTAssertTrue(gate.release(stale, enabled: true))

        let current = try XCTUnwrap(gate.acquire(enabled: true))
        XCTAssertFalse(gate.release(current, enabled: true))
        XCTAssertNil(gate.acquire(enabled: false))
    }

    @MainActor
    func testPaymentEvidenceResolutionUsesDedicatedConfigurableDeadline() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        AppPreferences.saveSettings(AppSettings())
        let fixture = try makeEvidencedInvoice()
        let container = try testContainer()
        container.mainContext.insert(fixture.invoice)
        try container.mainContext.save()
        let probe = ReceiptEvidenceCallProbe()
        let model = makeModel(
            container: container,
            backgroundRPCUnitDeadline: .milliseconds(50),
            paymentEvidenceResolutionDeadline: .seconds(2)
        ) { _, _ in
            let timeout = try RPCRequestDeadline.boundedRequestTimeout(default: 120)
            await probe.record(timeout: timeout)
            throw URLError(.timedOut)
        }

        let document = await model.ensureReceiptDocument(for: fixture.invoice.invoiceID)
        XCTAssertNil(document)
        let snapshot = await probe.snapshot()
        XCTAssertEqual(snapshot.calls, 1)
        XCTAssertGreaterThan(snapshot.timeout ?? 0, 1)
        XCTAssertLessThanOrEqual(snapshot.timeout ?? .infinity, 2)
    }

    @MainActor
    func testAutoSweepEvidenceFailureBacksOffExactFingerprint() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        AppPreferences.saveSettings(AppSettings().updatingAutoSweepEnabled(true))
        let fixture = try makeEvidencedInvoice()
        let container = try testContainer()
        container.mainContext.insert(fixture.invoice)
        try container.mainContext.save()
        let probe = ReceiptEvidenceCallProbe()
        let model = makeModel(
            container: container,
            operatorAddress: try address("6")
        ) { _, _ in
            await probe.record()
            throw URLError(.timedOut)
        }
        let attemptDate = Date(timeIntervalSince1970: 1_735_689_700)

        await model.attemptAutoSweepPreparation(now: attemptDate)
        await model.attemptAutoSweepPreparation(
            now: attemptDate.addingTimeInterval(1)
        )

        let probeSnapshot = await probe.snapshot()
        XCTAssertEqual(probeSnapshot.calls, 1)
        XCTAssertNil(model.preparedSettlement)
        XCTAssertEqual(model.autoSweepReviewSequence, 0)
    }

    @MainActor
    func testAutoSweepIsSingleFlightAndDisableInvalidatesAwaitingEvidence() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        AppPreferences.saveSettings(AppSettings().updatingAutoSweepEnabled(true))
        let fixture = try makeEvidencedInvoice()
        let container = try testContainer()
        container.mainContext.insert(fixture.invoice)
        try container.mainContext.save()
        let evidence = try paymentEvidence()
        let blocker = ReceiptBlockingEvidenceProbe()
        let model = makeModel(
            container: container,
            operatorAddress: try address("6"),
            adminPINConfigured: true
        ) { _, _ in
            await blocker.recordCallAndWait()
            return evidence
        }
        model.unlockAdmin(with: "123456")
        let first = Task { @MainActor in
            await model.attemptAutoSweepPreparation()
        }
        await blocker.waitUntilCalled()

        await model.attemptAutoSweepPreparation()
        let overlappingCallCount = await blocker.callCount()
        XCTAssertEqual(overlappingCallCount, 1)
        model.updateAutoSweepEnabled(false)
        await blocker.release()
        await first.value

        XCTAssertFalse(model.settings.autoSweepEnabled)
        let finalCallCount = await blocker.callCount()
        XCTAssertEqual(finalCallCount, 1)
        XCTAssertNil(model.preparedSettlement)
        XCTAssertEqual(model.autoSweepReviewSequence, 0)
    }

    @MainActor
    func testPersistedAutoSweepDismissalSuppressesRelaunchUntilExplicitReenable() async throws {
        let savedSettings = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(savedSettings) }
        let fixture = try makeEvidencedInvoice()
        let fingerprint = try XCTUnwrap(fixture.invoice.autoSweepFingerprint)
        var dismissed = AppSettings().updatingAutoSweepEnabled(true)
        for index in 0..<64 {
            dismissed = dismissed.recordingAutoSweepDismissal("prior-dismissal-\(index)")
        }
        dismissed = dismissed.recordingAutoSweepDismissal(fingerprint)
        let decoded = try JSONDecoder().decode(
            AppSettings.self,
            from: JSONEncoder().encode(dismissed)
        )
        XCTAssertEqual(decoded, dismissed)
        XCTAssertEqual(decoded.dismissedAutoSweepFingerprints.count, 65)
        XCTAssertTrue(decoded.dismissedAutoSweepFingerprints.contains(fingerprint))
        AppPreferences.saveSettings(decoded)
        let container = try testContainer()
        container.mainContext.insert(fixture.invoice)
        try container.mainContext.save()
        let probe = ReceiptEvidenceCallProbe()
        let model = makeModel(
            container: container,
            operatorAddress: try address("6")
        ) { _, _ in
            await probe.record()
            throw URLError(.timedOut)
        }

        await model.attemptAutoSweepPreparation()

        let probeSnapshot = await probe.snapshot()
        XCTAssertEqual(probeSnapshot.calls, 0)
        XCTAssertEqual(
            decoded
                .updatingAutoSweepEnabled(false)
                .updatingAutoSweepEnabled(true)
                .dismissedAutoSweepFingerprints,
            []
        )
    }

    func testAutoSweepDismissalCapacityFailsClosedWithoutEviction() throws {
        var settings = AppSettings().updatingAutoSweepEnabled(true)
        for index in 0..<AppSettings.maximumDismissedAutoSweepFingerprintCount {
            settings = settings.recordingAutoSweepDismissal("protected-dismissal-\(index)")
        }
        let protected = settings.dismissedAutoSweepFingerprints
        XCTAssertTrue(settings.autoSweepEnabled)
        XCTAssertEqual(
            protected.count,
            AppSettings.maximumDismissedAutoSweepFingerprintCount
        )

        let exhausted = settings.recordingAutoSweepDismissal("new-current-dismissal")

        XCTAssertFalse(exhausted.autoSweepEnabled)
        XCTAssertEqual(exhausted.dismissedAutoSweepFingerprints, protected)
        XCTAssertFalse(
            exhausted.dismissedAutoSweepFingerprints.contains("new-current-dismissal")
        )
        let encoded = try JSONEncoder().encode(exhausted)
        let decoded = try JSONDecoder().decode(AppSettings.self, from: encoded)
        XCTAssertEqual(decoded, exhausted)
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        XCTAssertEqual(object["schemaVersion"] as? Int, 5)

        let reenabled = decoded.updatingAutoSweepEnabled(true)
        XCTAssertTrue(reenabled.autoSweepEnabled)
        XCTAssertTrue(reenabled.dismissedAutoSweepFingerprints.isEmpty)
    }

    private func makeInvoice(
        receiptEligible: Bool = true
    ) throws -> (invoice: StoredInvoice, request: PaymentRequest) {
        let known = TerminalKnownChainProfile.baseSepolia
        let token = try PaymentToken(
            address: EthereumAddress(
                hex: "0x7ffba642bc902880a737cb1c18a4e9540879e211",
                allowZero: false
            ),
            symbol: "AUD",
            decimals: 18
        )
        let configuration = try TerminalConfiguration(
            chainID: known.chainID,
            rpcEndpoints: [known.rpcEndpoint],
            protocolVersion: known.protocolVersion,
            deployment: OPKDeployment(
                factory: known.factory,
                receiverImplementation: known.receiverImplementation,
                vault: known.create2TestVector.vault
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: 1),
            create2TestVector: known.create2TestVector
        )
        let request = try InvoiceFactory.create(
            terminalIdentifier: TerminalIdentifier(address: try address("6")),
            amount: UInt256(1_000),
            token: token,
            configuration: configuration,
            createdAt: Date(timeIntervalSince1970: 1_735_689_500),
            nonce: try hash("7")
        )
        let invoice = try StoredInvoice(
            request: request,
            configuration: configuration,
            publicationCursor: PaymentConfirmationCursor(
                blockNumber: 100,
                blockHash: try hash("0")
            ),
            receiptProfile: try MerchantReceiptProfile(
                name: "Blue Brew",
                abn: "51 824 753 556"
            ),
            receiptNumber: receiptEligible ? 42 : 0,
            receiptEligible: receiptEligible
        )
        return (invoice, request)
    }

    private func makeEvidencedInvoice() throws -> (invoice: StoredInvoice, request: PaymentRequest) {
        let fixture = try makeInvoice()
        let cursor = PaymentConfirmationCursor(
            blockNumber: 101,
            blockHash: try hash("1")
        )
        try fixture.invoice.apply(
            PaymentObservation(
                invoiceID: fixture.request.invoiceID,
                blockNumber: 102,
                blockHash: try hash("2"),
                balance: fixture.request.expectedAmount,
                status: .paid(received: fixture.request.expectedAmount),
                thresholdBlock: cursor.blockNumber,
                thresholdBlockHash: cursor.blockHash
            )
        )
        XCTAssertTrue(
            try fixture.invoice.applyIncomingPaymentEvidence(
                PaymentTransactionEvidence(
                    transactionHash: try hash("a"),
                    payer: try address("3"),
                    blockNumber: 101,
                    blockHash: cursor.blockHash,
                    blockTimestamp: 1_735_689_600
                ),
                expectedFundingCursor: cursor
            )
        )
        return fixture
    }

    private func paymentEvidence() throws -> PaymentTransactionEvidence {
        try PaymentTransactionEvidence(
            transactionHash: hash("a"),
            payer: address("3"),
            blockNumber: 101,
            blockHash: hash("1"),
            blockTimestamp: 1_735_689_600
        )
    }

    @MainActor
    private func makeModel(
        container: ModelContainer,
        operatorAddress: EthereumAddress? = nil,
        adminPINConfigured: Bool = false,
        backgroundRPCUnitDeadline: Duration = .seconds(5),
        paymentEvidenceResolutionDeadline: Duration = .seconds(60),
        resolver: @escaping AppPaymentEvidenceResolving
    ) -> AppModel {
        AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.receipt-tests.\(UUID())"
            ),
            operatorWalletLifecycle: ReceiptTestWalletLifecycle(address: operatorAddress),
            adminPINStore: ReceiptTestAdminPINStore(isConfigured: adminPINConfigured),
            backgroundRPCUnitDeadline: backgroundRPCUnitDeadline,
            paymentEvidenceResolutionDeadline: paymentEvidenceResolutionDeadline,
            paymentEvidenceResolver: resolver
        )
    }

    private func testContainer() throws -> ModelContainer {
        try ModelContainer(
            for: StoredInvoice.self,
            StoredSettlement.self,
            StoredCanonicalSweepProof.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func hash(_ digit: Character) throws -> Bytes32 {
        try Bytes32(hex: "0x" + String(repeating: digit, count: 64))
    }

    private func address(_ digit: Character) throws -> EthereumAddress {
        try EthereumAddress(
            hex: "0x" + String(repeating: digit, count: 40),
            allowZero: false
        )
    }
}

private struct ReceiptTestWalletLifecycle: OperatorWalletLifecycleManaging {
    let address: EthereumAddress?

    init(address: EthereumAddress? = nil) {
        self.address = address
    }

    func existingAddress() throws -> EthereumAddress? { address }

    func create(
        reason: String,
        persistenceAuthorization: @Sendable (
            _ persistence: () throws -> EthereumAddress
        ) throws -> EthereumAddress
    ) async throws -> EthereumAddress {
        throw ReceiptTestDependencyError.unavailable
    }

    func reset(
        reason: String,
        beforeDeletion: @Sendable () async throws -> Void,
        deletionAuthorization: @Sendable (
            _ deletion: () throws -> Void
        ) throws -> Void
    ) async throws {
        throw ReceiptTestDependencyError.unavailable
    }
}

private final class ReceiptTestAdminPINStore: AdminPINManaging {
    private let configured: Bool

    init(isConfigured: Bool = false) {
        configured = isConfigured
    }

    var isConfigured: Bool { configured }
    func setPIN(_ pin: String) throws {}
    func verify(_ pin: String) throws {}
    func secondsUntilNextAttempt() throws -> Int { 0 }
}

private actor ReceiptEvidenceCallProbe {
    private var calls = 0
    private var timeout: TimeInterval?

    func record(timeout: TimeInterval? = nil) {
        calls += 1
        if let timeout { self.timeout = timeout }
    }

    func snapshot() -> (calls: Int, timeout: TimeInterval?) {
        (calls, timeout)
    }
}

private actor ReceiptBlockingEvidenceProbe {
    private var calls = 0
    private var released = false

    func recordCallAndWait() async {
        calls += 1
        while !released {
            await Task.yield()
        }
    }

    func waitUntilCalled() async {
        while calls == 0 {
            await Task.yield()
        }
    }

    func callCount() -> Int { calls }

    func release() {
        released = true
    }
}

private enum ReceiptTestDependencyError: Error {
    case unavailable
}
