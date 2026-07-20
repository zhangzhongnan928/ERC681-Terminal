import Foundation
import OPKTerminalCore
import OPKTerminalOperator
import OPKTerminalRPC
import SwiftData

@MainActor
final class AppModel: ObservableObject {
    @Published var settings: AppSettings {
        didSet {
            AppPreferences.saveSettings(settings)
            settlementCoordinator = nil
            settlementCoordinatorKey = nil
            operatorStatus = nil
        }
    }
    @Published private(set) var validationMessage = "Not validated"
    @Published private(set) var activeRequest: PaymentRequest?
    @Published private(set) var activeObservation: PaymentObservation?
    @Published private(set) var isBusy = false
    @Published private(set) var settlementBusy = false
    @Published private(set) var operatorAddress: EthereumAddress?
    @Published private(set) var operatorStatus: OperatorChainStatus?
    @Published private(set) var operatorStatusMessage: String?
    @Published private(set) var preparedSettlement: PreparedSettlement?
    @Published var errorMessage: String?

    private let container: ModelContainer
    private let operatorWallet: KeychainOperatorWallet
    private var monitoringTask: Task<Void, Never>?
    private var settlementCoordinator: SettlementCoordinator?
    private var settlementCoordinatorKey: String?
    private var preparedConfiguration: TerminalConfiguration?

    init(container: ModelContainer) {
        self.container = container
        operatorWallet = KeychainOperatorWallet()
        settings = AppPreferences.loadSettings()
        operatorAddress = try? operatorWallet.existingAddress()
    }

    func validateConfiguration() async -> Bool {
        isBusy = true
        defer { isBusy = false }
        do {
            let configuration = try settings.configuration()
            try await validate(configuration)
            errorMessage = nil
            return true
        } catch {
            validationMessage = "Validation failed"
            errorMessage = error.localizedDescription
            return false
        }
    }

    func createSale(displayAmount: String) async {
        isBusy = true
        defer { isBusy = false }
        do {
            // Capture once so the exact configuration used for derivation is the one validated.
            let configuration = try settings.configuration()
            guard let operatorAddress else {
                throw AppSafetyError.operatorWalletRequired
            }
            try await validate(configuration)
            let token = configuration.tokens[0]
            let amount = try TokenAmount(display: displayAmount, decimals: token.decimals).rawValue
            let request = try InvoiceFactory.create(
                terminalIdentifier: TerminalIdentifier(address: operatorAddress),
                amount: amount,
                token: token,
                configuration: configuration,
                expiresAt: Date().addingTimeInterval(15 * 60)
            )
            let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
            let observationBlock = try await rpc.blockNumber()
            let receiverCode = try await rpc.code(at: request.receiver, block: .number(observationBlock))
            guard receiverCode.isEmpty else { throw AppSafetyError.receiverAlreadyDeployed }
            let existingBalanceData = try await rpc.call(
                to: token.address,
                data: ABI.encodeCall(
                    selector: ABI.balanceOfSelector,
                    words: [ABI.word(request.receiver)]
                ),
                block: .number(observationBlock)
            )
            guard try ABI.decodeUInt256(existingBalanceData).isZero else {
                throw AppSafetyError.receiverAlreadyFunded
            }
            container.mainContext.insert(try StoredInvoice(request: request, configuration: configuration))
            try container.mainContext.save()
            activeRequest = request
            activeObservation = nil
            startMonitoring(request, configuration: configuration)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func closeActiveSale() {
        monitoringTask?.cancel()
        monitoringTask = nil
        if let id = activeRequest?.invoiceID.hex {
            do {
                var descriptor = FetchDescriptor<StoredInvoice>(predicate: #Predicate { $0.invoiceID == id })
                descriptor.fetchLimit = 1
                if let invoice = try container.mainContext.fetch(descriptor).first {
                    invoice.locallyClosed = true
                    if invoice.statusLabel != "Paid" && invoice.statusLabel != "Overpaid" && invoice.statusLabel != "Expired" {
                        invoice.statusLabel = "Closed"
                    }
                    try container.mainContext.save()
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
        activeRequest = nil
        activeObservation = nil
    }

    func reconcileForegroundInvoices() async {
        do {
            let invoices = try container.mainContext.fetch(
                FetchDescriptor<StoredInvoice>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)])
            )
            let open = invoices.filter {
                !$0.locallyClosed
                    && $0.statusLabel != "Paid"
                    && $0.statusLabel != "Overpaid"
                    && $0.statusLabel != "Expired"
                    && $0.statusLabel != "Closed"
            }
            guard !open.isEmpty else { return }
            for invoice in open {
                let request = try invoice.paymentRequest()
                let configuration = try invoice.configurationSnapshot()
                try validateSnapshot(request, against: configuration)
                let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
                let actualChainID = try await rpc.chainID()
                guard actualChainID == configuration.chainID else {
                    throw AppSafetyError.snapshotChainMismatch(
                        expected: configuration.chainID,
                        actual: actualChainID
                    )
                }
                let monitor = PaymentMonitor(rpc: rpc, confirmationPolicy: configuration.confirmationPolicy)
                let threshold = invoice.thresholdBlock.flatMap { UInt64(exactly: $0) }
                let observation = try await monitor.sample(
                    request,
                    previousThresholdBlock: threshold
                )
                try invoice.apply(observation)
            }
            try container.mainContext.save()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createOperatorWallet() async {
        guard operatorAddress == nil else { return }
        settlementBusy = true
        defer { settlementBusy = false }
        do {
            let address = try await operatorWallet.create(
                reason: "Create the settlement operator wallet on this device"
            )
            operatorAddress = address
            settlementCoordinator = nil
            settlementCoordinatorKey = nil
            errorMessage = nil
            await refreshOperatorStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func refreshOperatorStatus() async {
        guard let operatorAddress else {
            operatorStatus = nil
            operatorStatusMessage = "Create the operator wallet to enable native settlement."
            return
        }
        do {
            let configuration = try settings.configuration()
            let coordinator = try coordinator(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            let status = try await coordinator.refreshStatus(
                expectedChainID: configuration.chainID,
                vault: configuration.deployment.vault
            )
            operatorStatus = status
            operatorStatusMessage = nil
        } catch {
            operatorStatus = nil
            operatorStatusMessage = error.localizedDescription
        }
    }

    func prepareSettlement(for invoices: [StoredInvoice]) async {
        guard !invoices.isEmpty else { return }
        settlementBusy = true
        defer { settlementBusy = false }
        do {
            guard invoices.count <= 20,
                  invoices.allSatisfy({ $0.statusLabel == "Paid" || $0.statusLabel == "Overpaid" })
            else { throw AppSettlementError.invalidSelection }

            let activeIDs = try activeSettlementInvoiceIDs()
            guard invoices.allSatisfy({ !activeIDs.contains($0.invoiceID) }) else {
                throw AppSettlementError.alreadySubmitted
            }

            let requests = try invoices.map { try $0.paymentRequest() }
            let configurations = try invoices.map { try $0.configurationSnapshot() }
            guard let configuration = configurations.first,
                  configurations.allSatisfy({ $0 == configuration }),
                  let firstRequest = requests.first
            else { throw AppSettlementError.mixedSnapshots }
            guard let operatorAddress else { throw OperatorWalletError.walletNotCreated }
            try enforceDurableNonceGate(
                operatorAddress: operatorAddress,
                chainID: configuration.chainID
            )

            let allSettlements = try container.mainContext.fetch(
                FetchDescriptor<StoredSettlement>()
            )
            let cumulative = try confirmedCumulativeTotals(allSettlements)
            let sessions = requests.map { request in
                let key = CumulativeSettlementKey(
                    chainID: request.chainID,
                    vault: request.vault.hex.lowercased(),
                    invoiceID: request.invoiceID.hex.lowercased(),
                    token: request.token.address.hex.lowercased()
                )
                return SettlementSession(
                    invoiceID: request.invoiceID,
                    receiver: request.receiver,
                    expectedAmount: request.expectedAmount,
                    priorConfirmedSweptAmount: cumulative[key, default: .zero]
                )
            }
            let intent = try SettlementIntent(
                chainID: configuration.chainID,
                vault: configuration.deployment.vault,
                token: firstRequest.token.address,
                sessions: sessions
            )
            let coordinator = try coordinator(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            preparedSettlement = try await coordinator.prepare(intent)
            preparedConfiguration = configuration
            errorMessage = nil
        } catch {
            preparedSettlement = nil
            preparedConfiguration = nil
            errorMessage = error.localizedDescription
        }
    }

    func cancelPreparedSettlement() {
        preparedSettlement = nil
        preparedConfiguration = nil
    }

    func confirmPreparedSettlement() async {
        guard let preparedSettlement,
              let configuration = preparedConfiguration,
              let operatorAddress
        else { return }
        settlementBusy = true
        defer { settlementBusy = false }

        do {
            let coordinator = try coordinator(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            let count = preparedSettlement.intent.sessions.count
            let signed = try await coordinator.sign(
                preparedSettlement,
                authenticationReason: "Authorize a zero-value sweep of \(count) paid session\(count == 1 ? "" : "s") to the configured vault"
            )

            guard let token = configuration.tokens.first(where: {
                $0.address == preparedSettlement.intent.token
            }) else { throw AppSettlementError.mixedSnapshots }

            // This save is intentionally completed before any broadcast attempt. If it
            // fails, the signed bytes never leave the device.
            let stored = try StoredSettlement(
                signed: signed,
                prepared: preparedSettlement,
                rpcURL: configuration.rpcEndpoints[0],
                tokenSymbol: token.symbol,
                tokenDecimals: token.decimals,
                requiredConfirmations: configuration.confirmationPolicy.requiredBlocks
            )
            container.mainContext.insert(stored)
            try container.mainContext.save()

            let submission = await coordinator.broadcast(signed)
            try stored.apply(submission)
            try container.mainContext.save()
            self.preparedSettlement = nil
            preparedConfiguration = nil
            errorMessage = submission.broadcastError
            await refreshOperatorStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func reconcileSettlements() async {
        do {
            let records = try container.mainContext.fetch(
                FetchDescriptor<StoredSettlement>(
                    sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
                )
            )
            let reconcilable = records.filter {
                $0.phase == .pending || $0.phase == .mined || $0.phase == .unknown
            }
            guard !reconcilable.isEmpty else { return }

            for record in reconcilable {
                do {
                    guard let currentAddress = operatorAddress,
                          currentAddress.hex == record.operatorAddress,
                          let endpoint = URL(string: record.rpcURL),
                          let required = UInt64(exactly: record.requiredConfirmations),
                          let nonce = UInt64(exactly: record.nonce)
                    else { throw AppSettlementError.walletMismatch }
                    let rpc = try OperatorRPCClient(endpoint: endpoint)
                    let coordinator = SettlementCoordinator(
                        rpc: rpc,
                        wallet: operatorWallet,
                        operatorAddress: currentAddress
                    )
                    let intent = try record.intent()
                    if record.phase == .unknown {
                        let retry = await coordinator.retryPersistedBroadcast(
                            transactionHash: try Bytes32(hex: record.transactionHash),
                            rawTransaction: record.rawTransaction,
                            intent: intent,
                            nonce: nonce
                        )
                        try record.apply(retry)
                        try container.mainContext.save()
                    }
                    let result = try await coordinator.reconcile(
                        transactionHash: Bytes32(hex: record.transactionHash),
                        intent: intent,
                        requiredConfirmations: required,
                        priorPhase: record.phase
                    )
                    try record.apply(result)
                } catch {
                    record.phase = .unknown
                    record.failureReason = error.localizedDescription
                    record.updatedAt = Date()
                }
            }
            try applyCumulativeSettlementEvidence(records)
            try container.mainContext.save()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func activeSettlementInvoiceIDs() throws -> Set<String> {
        let records = try container.mainContext.fetch(FetchDescriptor<StoredSettlement>())
        return Set(records.filter(\.isActiveClaim).flatMap(\.invoiceIDs))
    }

    private func enforceDurableNonceGate(
        operatorAddress: EthereumAddress,
        chainID: UInt64
    ) throws {
        let records = try container.mainContext.fetch(FetchDescriptor<StoredSettlement>())
        let hasUnresolvedNonce = records.contains {
            $0.operatorAddress.lowercased() == operatorAddress.hex.lowercased()
                && UInt64(exactly: $0.chainID) == chainID
                && ($0.phase == .unknown || $0.phase == .pending || $0.phase == .mined)
        }
        guard !hasUnresolvedNonce else {
            throw AppSettlementError.unresolvedOperatorNonce
        }
    }

    private func applyCumulativeSettlementEvidence(
        _ records: [StoredSettlement]
    ) throws {
        let totals = try confirmedCumulativeTotals(records)
        for record in records where record.phase == .needsReview {
            guard let chainID = UInt64(exactly: record.chainID) else {
                throw AppSettingsError.invalidValue
            }
            let intent = try record.intent()
            let isCumulativelyComplete = intent.sessions.allSatisfy { session in
                let key = CumulativeSettlementKey(
                    chainID: chainID,
                    vault: record.vault.lowercased(),
                    invoiceID: session.invoiceID.hex.lowercased(),
                    token: record.tokenAddress.lowercased()
                )
                return totals[key, default: .zero] >= session.expectedAmount
            }
            if isCumulativelyComplete {
                record.phase = .final
                record.failureReason = nil
                record.updatedAt = Date()
            }
        }
    }

    private func confirmedCumulativeTotals(
        _ records: [StoredSettlement]
    ) throws -> [CumulativeSettlementKey: UInt256] {
        var totals = [CumulativeSettlementKey: UInt256]()
        var identities = [CumulativeSettlementKey: Set<String>]()

        for record in records where record.phase == .final || record.phase == .needsReview {
            guard let chainID = UInt64(exactly: record.chainID) else {
                throw AppSettingsError.invalidValue
            }
            for proof in try record.eventProofs() {
                guard proof.transactionHash.lowercased() == record.transactionHash.lowercased(),
                      proof.token.lowercased() == record.tokenAddress.lowercased(),
                      let logIndex = proof.logIndex,
                      let amount = try? UInt256(decimalString: proof.sweptAmount)
                else { continue }
                let key = CumulativeSettlementKey(
                    chainID: chainID,
                    vault: record.vault.lowercased(),
                    invoiceID: proof.invoiceID.lowercased(),
                    token: record.tokenAddress.lowercased()
                )
                let identity = "\(proof.transactionHash.lowercased()):\(logIndex)"
                guard identities[key, default: []].insert(identity).inserted else { continue }
                let (updated, overflow) = totals[key, default: .zero].addingReportingOverflow(amount)
                guard !overflow else { throw AppSettingsError.invalidValue }
                totals[key] = updated
            }
        }
        return totals
    }

    private func coordinator(
        configuration: TerminalConfiguration,
        operatorAddress: EthereumAddress
    ) throws -> SettlementCoordinator {
        let endpoint = configuration.rpcEndpoints[0]
        let key = "\(endpoint.absoluteString)|\(configuration.chainID)|\(operatorAddress.hex)"
        if settlementCoordinatorKey == key, let settlementCoordinator {
            return settlementCoordinator
        }
        let rpc = try OperatorRPCClient(endpoint: endpoint)
        let value = SettlementCoordinator(
            rpc: rpc,
            wallet: operatorWallet,
            operatorAddress: operatorAddress
        )
        settlementCoordinator = value
        settlementCoordinatorKey = key
        return value
    }

    private func validate(_ configuration: TerminalConfiguration) async throws {
        do {
            let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
            let report = try await ConfigurationValidator(rpc: rpc).validate(configuration)
            validationMessage = "Validated \(report.checks.count) checks on chain \(report.chainID)"
        } catch {
            validationMessage = "Validation failed"
            throw error
        }
    }

    private func validateSnapshot(
        _ request: PaymentRequest,
        against configuration: TerminalConfiguration
    ) throws {
        guard request.chainID == configuration.chainID,
              request.vault == configuration.deployment.vault,
              configuration.tokens.contains(request.token)
        else { throw AppSafetyError.corruptInvoiceSnapshot }
        let expectedReceiver = try ReceiverDerivation.receiver(
            factory: configuration.deployment.factory,
            receiverImplementation: configuration.deployment.receiverImplementation,
            vault: configuration.deployment.vault,
            invoiceID: request.invoiceID
        )
        guard expectedReceiver == request.receiver else {
            throw AppSafetyError.corruptInvoiceSnapshot
        }
    }

    private func startMonitoring(_ request: PaymentRequest, configuration: TerminalConfiguration) {
        monitoringTask?.cancel()
        monitoringTask = Task { [weak self] in
            guard let self else { return }
            do {
                let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
                let monitor = PaymentMonitor(rpc: rpc, confirmationPolicy: configuration.confirmationPolicy)
                for try await observation in monitor.observations(for: request) {
                    guard !Task.isCancelled else { return }
                    self.activeObservation = observation
                    try self.persist(observation)
                }
            } catch is CancellationError {
                return
            } catch {
                self.errorMessage = error.localizedDescription
            }
        }
    }

    private func persist(_ observation: PaymentObservation) throws {
        let id = observation.invoiceID.hex
        var descriptor = FetchDescriptor<StoredInvoice>(predicate: #Predicate { $0.invoiceID == id })
        descriptor.fetchLimit = 1
        if let invoice = try container.mainContext.fetch(descriptor).first {
            try invoice.apply(observation)
            try container.mainContext.save()
        }
    }
}

private enum AppSafetyError: LocalizedError {
    case operatorWalletRequired
    case receiverAlreadyDeployed
    case receiverAlreadyFunded
    case corruptInvoiceSnapshot
    case snapshotChainMismatch(expected: UInt64, actual: UInt64)

    var errorDescription: String? {
        switch self {
        case .operatorWalletRequired:
            "Create the terminal operator wallet in Settings before creating a payment QR. Historical invoices remain available."
        case .receiverAlreadyDeployed:
            "The newly derived receiver already has contract code. No QR was created."
        case .receiverAlreadyFunded:
            "The newly derived receiver already has a token balance. No QR was created."
        case .corruptInvoiceSnapshot:
            "A stored invoice no longer matches its saved network configuration. It was not monitored."
        case let .snapshotChainMismatch(expected, actual):
            "A stored invoice expects chain \(expected), but its saved RPC reported chain \(actual). It was not monitored."
        }
    }
}

private enum AppSettlementError: LocalizedError {
    case invalidSelection
    case alreadySubmitted
    case mixedSnapshots
    case walletMismatch
    case unresolvedOperatorNonce

    var errorDescription: String? {
        switch self {
        case .invalidSelection:
            "Choose between 1 and 20 fully paid invoices from one token group."
        case .alreadySubmitted:
            "At least one selected invoice already has an active settlement transaction."
        case .mixedSnapshots:
            "The selected invoices do not share the same saved chain, vault, token, and RPC configuration."
        case .walletMismatch:
            "The saved transaction belongs to a different device operator wallet and cannot be reconciled here."
        case .unresolvedOperatorNonce:
            "This operator already has an unresolved transaction on the selected chain. Reconcile or re-broadcast its saved raw transaction before signing another nonce."
        }
    }
}

private struct CumulativeSettlementKey: Hashable {
    let chainID: UInt64
    let vault: String
    let invoiceID: String
    let token: String
}
