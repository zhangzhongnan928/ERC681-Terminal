import Foundation
import OPKTerminalCore
import OPKTerminalOperator
import OPKTerminalRPC
import SwiftData

protocol OperatorWalletLifecycleManaging: Sendable {
    func existingAddress() throws -> EthereumAddress?
    func create(
        reason: String,
        persistenceAuthorization: @Sendable (
            _ persistence: () throws -> EthereumAddress
        ) throws -> EthereumAddress
    ) async throws -> EthereumAddress
    func reset(
        reason: String,
        beforeDeletion: @Sendable () async throws -> Void,
        deletionAuthorization: @Sendable (
            _ deletion: () throws -> Void
        ) throws -> Void
    ) async throws
}

extension KeychainOperatorWallet: OperatorWalletLifecycleManaging {}

@MainActor
final class AppModel: ObservableObject {
    @Published var settings: AppSettings {
        didSet {
            AppPreferences.saveSettings(settings)
            validatedConfigurationFingerprint = nil
            settlementCoordinator = nil
            settlementCoordinatorKey = nil
            operatorStatus = nil
        }
    }
    @Published private(set) var validationMessage = "Not validated"
    @Published private(set) var activeRequest: PaymentRequest?
    @Published private(set) var activeObservation: PaymentObservation?
    @Published private(set) var isBusy = false
    @Published private(set) var operationBusy = false
    @Published private(set) var operatorAddress: EthereumAddress?
    @Published private(set) var operatorStatus: OperatorChainStatus?
    @Published private(set) var operatorStatusMessage: String?
    @Published private(set) var validatedConfigurationFingerprint: String?
    @Published private(set) var provisioningMessage: String?
    @Published private(set) var isProvisioning = false
    @Published private(set) var adminPINConfigured: Bool
    @Published private(set) var adminUnlocked: Bool
    @Published private(set) var preparedSettlement: PreparedSettlement?
    @Published var errorMessage: String?

    private let container: ModelContainer
    private let operatorWallet: KeychainOperatorWallet
    private let operatorWalletLifecycle: any OperatorWalletLifecycleManaging
    private let provisioningValidator: any TerminalProvisioningValidating
    private let historicalConfigurationValidator: any HistoricalTerminalConfigurationValidating
    private let adminPINStore: any AdminPINManaging
    private let persistMainContext: (ModelContext) throws -> Void
    private let operatorResetBalanceReader: (@Sendable (
        TerminalConfiguration,
        EthereumAddress
    ) async throws -> OperatorNativeBalanceSnapshot)?
    private let adminSessionGate = AdminSessionGate()
    private var monitoringTask: Task<Void, Never>?
    private var settlementCoordinator: SettlementCoordinator?
    private var settlementCoordinatorKey: String?
    private var preparedConfiguration: TerminalConfiguration?
    private var preparedConfirmationSnapshots: [SweepableConfirmationSnapshot]?
    private let lifecycleOperationGate = AppModelOperationGate()
    private let foregroundInvoiceReconciliationGate = ForegroundInvoiceReconciliationGate()

    init(
        container: ModelContainer,
        operatorWallet: KeychainOperatorWallet = KeychainOperatorWallet(),
        operatorWalletLifecycle: (any OperatorWalletLifecycleManaging)? = nil,
        provisioningValidator: any TerminalProvisioningValidating = TerminalProvisioner(),
        historicalConfigurationValidator: any HistoricalTerminalConfigurationValidating = TerminalProvisioner(),
        adminPINStore: any AdminPINManaging = KeychainAdminPINStore(),
        persistMainContext: @escaping (ModelContext) throws -> Void = { try $0.save() },
        operatorResetBalanceReader: (@Sendable (
            TerminalConfiguration,
            EthereumAddress
        ) async throws -> OperatorNativeBalanceSnapshot)? = nil
    ) {
        self.container = container
        self.operatorWallet = operatorWallet
        self.operatorWalletLifecycle = operatorWalletLifecycle ?? operatorWallet
        self.provisioningValidator = provisioningValidator
        self.historicalConfigurationValidator = historicalConfigurationValidator
        self.adminPINStore = adminPINStore
        self.persistMainContext = persistMainContext
        self.operatorResetBalanceReader = operatorResetBalanceReader
        settings = AppPreferences.loadSettings()
        let configured = (try? adminPINStore.isConfigured) ?? false
        adminPINConfigured = configured
        adminUnlocked = !configured
        operatorAddress = try? self.operatorWalletLifecycle.existingAddress()
    }

    var terminalReadiness: TerminalReadiness {
        TerminalReadiness.evaluate(
            settings: settings,
            operatorAddress: operatorAddress,
            validatedFingerprint: validatedConfigurationFingerprint,
            operatorStatus: operatorStatus
        )
    }

    var canAccessAdmin: Bool { !adminPINConfigured || adminUnlocked }

    var operatorPairingPayload: String? {
        guard let operatorAddress else { return nil }
        return try? TerminalOperatorPairingPayload.encode(address: operatorAddress)
    }

    var operatorFundingPayload: String? {
        guard let operatorAddress else { return nil }
        return settings.operatorFundingPayload(for: operatorAddress)
    }

    var preparedSettlementToken: PaymentToken? {
        guard let preparedSettlement, let preparedConfiguration else { return nil }
        return preparedConfiguration.tokens.first {
            $0.address == preparedSettlement.intent.token
        }
    }

    func configureAdminPIN(_ pin: String, confirmation: String) {
        do {
            guard pin == confirmation else { throw AdminPINError.invalidFormat }
            try adminPINStore.setPIN(pin)
            adminPINConfigured = true
            adminUnlocked = true
            adminSessionGate.unlock()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func unlockAdmin(with pin: String) {
        do {
            try adminPINStore.verify(pin)
            adminPINConfigured = true
            adminUnlocked = true
            adminSessionGate.unlock()
            errorMessage = nil
        } catch {
            adminSessionGate.invalidate()
            adminUnlocked = false
            errorMessage = error.localizedDescription
        }
    }

    func lockAdmin() {
        guard adminPINConfigured else { return }
        adminSessionGate.invalidate()
        adminUnlocked = false
    }

    func provision(_ payload: TerminalProvisioningPayload) async {
        guard canAccessAdmin else {
            errorMessage = "Unlock Admin before changing terminal provisioning."
            return
        }
        guard adminPINConfigured else {
            errorMessage = "Create the six-digit local admin PIN before provisioning."
            return
        }
        guard let adminSession = adminSessionGate.capture() else {
            errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
            return
        }
        guard let operatorAddress else {
            errorMessage = "Create the device-local operator wallet before provisioning."
            return
        }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }

        isProvisioning = true
        provisioningMessage = "Deriving and validating configuration on chain…"
        defer {
            isProvisioning = false
            endExclusiveOperation()
        }
        let original = settings
        do {
            guard let blocks = UInt64(original.confirmationBlocks), blocks > 0 else {
                throw AppSettingsError.invalidValue
            }
            let derived = try await provisioningValidator.deriveAndValidate(
                payload,
                expectedOperator: operatorAddress,
                confirmationPolicy: .init(requiredBlocks: blocks),
                rpcEndpointOverride: existingRPCOverride(
                    for: payload.chainID,
                    settings: original
                )
            )
            let candidate = try original.applying(
                derived.configuration,
                boundTo: operatorAddress
            )
            guard settings == original else { throw AppSafetyError.configurationChanged }
            try adminSessionGate.requireCurrent(adminSession)

            // This is the provisioning flow's single settings mutation and persistence point.
            settings = candidate
            validatedConfigurationFingerprint = candidate.validationFingerprint
            validationMessage = "Validated \(derived.validationReport.checks.count) checks on chain \(derived.validationReport.chainID)"
            provisioningMessage = "Provisioning validated and saved."
            errorMessage = nil
            lockAdmin()
            await refreshOperatorStatus()
        } catch {
            provisioningMessage = "Provisioning rejected. Existing settings were not changed."
            errorMessage = error.localizedDescription
        }
    }

    func refreshReadiness() async {
        guard settings.isProvisioned else {
            validatedConfigurationFingerprint = nil
            await refreshOperatorStatus()
            return
        }
        _ = await validateConfiguration()
        await refreshOperatorStatus()
    }

    func validateConfiguration() async -> Bool {
        isBusy = true
        defer { isBusy = false }
        let snapshot = settings
        do {
            let configuration = try snapshot.configuration()
            try await validate(configuration)
            guard settings == snapshot else { throw AppSafetyError.configurationChanged }
            validatedConfigurationFingerprint = snapshot.validationFingerprint
            errorMessage = nil
            return true
        } catch {
            validatedConfigurationFingerprint = nil
            validationMessage = "Validation failed"
            errorMessage = error.localizedDescription
            return false
        }
    }

    func createSale(displayAmount: String) async {
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        isBusy = true
        defer {
            isBusy = false
            endExclusiveOperation()
        }
        do {
            // Capture once so the exact configuration used for derivation is the one validated.
            let settingsSnapshot = settings
            guard settingsSnapshot.isProvisioned else {
                throw AppSafetyError.provisioningRequired
            }
            let configuration = try settingsSnapshot.configuration()
            guard let operatorAddress else {
                throw AppSafetyError.operatorWalletRequired
            }
            guard settingsSnapshot.provisionedOperatorAddress?.lowercased()
                == operatorAddress.hex.lowercased()
            else { throw AppSafetyError.operatorBindingMismatch }
            validatedConfigurationFingerprint = nil
            try await validate(configuration)
            guard settings == settingsSnapshot else { throw AppSafetyError.configurationChanged }
            validatedConfigurationFingerprint = settingsSnapshot.validationFingerprint
            operatorStatus = nil
            let liveStatus = try await fetchOperatorStatus(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            guard settings == settingsSnapshot else { throw AppSafetyError.configurationChanged }
            operatorStatus = liveStatus
            operatorStatusMessage = nil
            let readiness = TerminalReadiness.evaluate(
                settings: settingsSnapshot,
                operatorAddress: operatorAddress,
                validatedFingerprint: settingsSnapshot.validationFingerprint,
                operatorStatus: liveStatus
            )
            guard readiness.isReady else {
                throw AppSafetyError.terminalNotReady(readiness.detail)
            }
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
            guard settings == settingsSnapshot,
                  self.operatorAddress == operatorAddress
            else {
                throw AppSafetyError.configurationChanged
            }
            container.mainContext.insert(try StoredInvoice(request: request, configuration: configuration))
            try saveMainContextOrRollback()
            activeRequest = request
            activeObservation = nil
            startMonitoring(request, configuration: configuration)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func closeActiveSale() {
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
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
                    try saveMainContextOrRollback()
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
        activeRequest = nil
        activeObservation = nil
    }

    func reconcileForegroundInvoices(now: Date = Date()) async {
        guard !foregroundInvoiceReconciliationGate.isInFlight else { return }
        guard beginExclusiveOperation() else { return }
        var candidates = [ForegroundInvoiceReconciliationCandidate]()
        do {
            let invoices = try container.mainContext.fetch(
                ForegroundInvoiceReconciliationPolicy.fetchDescriptor(now: now)
            )
            for invoice in invoices {
                invoice.beginForegroundReconciliation(at: now)
                do {
                    let request = try invoice.paymentRequest()
                    let configuration = try invoice.configurationSnapshot()
                    try validateSnapshot(request, against: configuration)
                    candidates.append(
                        ForegroundInvoiceReconciliationCandidate(
                            invoiceID: invoice.invoiceID,
                            request: request,
                            configuration: configuration,
                            previousThresholdCursor: invoice.paymentThresholdCursor,
                            additionalCursors: [invoice.sweepableConfirmationCursor]
                                .compactMap { $0 }
                        )
                    )
                } catch {
                    invoice.recordForegroundReconciliationFailure(
                        error.localizedDescription,
                        at: now
                    )
                }
            }
            try saveMainContextOrRollback()
        } catch {
            candidates.removeAll()
            errorMessage = error.localizedDescription
        }
        let reconciliationToken = candidates.isEmpty
            ? nil
            : foregroundInvoiceReconciliationGate.acquire()
        endExclusiveOperation()
        guard let reconciliationToken else { return }
        defer { foregroundInvoiceReconciliationGate.release(reconciliationToken) }

        await withTaskGroup(of: ForegroundInvoiceReconciliationOutcome.self) { group in
            for candidate in candidates {
                group.addTask {
                    await Self.sampleForegroundInvoice(candidate, now: now)
                }
            }
            for await outcome in group {
                persistForegroundInvoiceReconciliation(outcome, at: Date())
            }
        }
    }

    func createOperatorWallet() async {
        guard canAccessAdmin else {
            errorMessage = "Unlock Admin before creating an operator wallet."
            return
        }
        let adminSession: AdminSessionEpoch?
        if adminPINConfigured {
            guard let captured = adminSessionGate.capture() else {
                errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
                return
            }
            adminSession = captured
        } else {
            // First-run wallet creation deliberately remains available before a PIN exists.
            adminSession = nil
        }
        guard operatorAddress == nil else { return }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        do {
            let sessionGate = adminSessionGate
            let address = try await operatorWalletLifecycle.create(
                reason: "Create the settlement operator wallet on this device",
                persistenceAuthorization: { persistence in
                    if let adminSession {
                        return try sessionGate.performIfCurrent(
                            adminSession,
                            operation: persistence
                        )
                    }
                    return try persistence()
                }
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

    func resetOperatorWallet() async {
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before resetting the operator wallet."
            return
        }
        guard let adminSession = adminSessionGate.capture() else {
            errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
            return
        }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        guard activeRequest == nil, preparedSettlement == nil else {
            errorMessage = "Close the active payment or settlement review before resetting the operator."
            return
        }
        do {
            guard let operatorAddress else { throw OperatorWalletError.walletNotCreated }
            var invoiceDescriptor = FetchDescriptor<StoredInvoice>()
            invoiceDescriptor.fetchLimit = 1
            let issuedInvoiceCount = try container.mainContext.fetch(invoiceDescriptor).count
            guard OperatorResetSafety.allowsOperatorKeyDeletion(
                issuedInvoiceCount: issuedInvoiceCount
            ) else {
                throw AppSafetyError.operatorResetBlockedByIssuedInvoice
            }
            guard !foregroundInvoiceReconciliationGate.isInFlight else {
                throw AppSettlementError.operationInProgress
            }
            let settlements = try container.mainContext.fetch(FetchDescriptor<StoredSettlement>())
            guard !settlements.contains(where: { OperatorResetSafety.isUnresolved($0.phase) }) else {
                throw AppSafetyError.operatorResetBlockedBySettlement
            }
            let operationalConfiguration = try settings.configuration()
            guard let trustedProfile = TerminalKnownChainProfile.profile(
                for: operationalConfiguration.chainID
            ) else { throw AppSettingsError.unsupportedChain }
            // Key deletion must not trust an admin-editable or previously persisted RPC. A
            // compromised endpoint could otherwise report zero for a funded operator address.
            let resetConfiguration = try TerminalConfiguration(
                chainID: operationalConfiguration.chainID,
                rpcEndpoints: [trustedProfile.rpcEndpoint],
                protocolVersion: operationalConfiguration.protocolVersion,
                deployment: operationalConfiguration.deployment,
                tokens: operationalConfiguration.tokens,
                confirmationPolicy: operationalConfiguration.confirmationPolicy,
                create2TestVector: operationalConfiguration.create2TestVector
            )
            let readResetBalances: @Sendable () async throws -> OperatorNativeBalanceSnapshot
            if let operatorResetBalanceReader {
                readResetBalances = {
                    try await operatorResetBalanceReader(resetConfiguration, operatorAddress)
                }
            } else {
                let resetCoordinator = try coordinator(
                    configuration: resetConfiguration,
                    operatorAddress: operatorAddress
                )
                readResetBalances = {
                    try await resetCoordinator.resetSafetyBalances(
                        expectedChainID: resetConfiguration.chainID
                    )
                }
            }
            try OperatorResetSafety.requireEmptyNativeBalance(
                try await readResetBalances()
            )
            let sessionGate = adminSessionGate
            try await operatorWalletLifecycle.reset(
                reason: "Permanently reset this terminal's empty settlement operator wallet",
                beforeDeletion: {
                    // Re-read after device authentication so a pending withdrawal cannot make a
                    // funded key look empty during the destructive confirmation window.
                    try OperatorResetSafety.requireEmptyNativeBalance(
                        try await readResetBalances()
                    )
                },
                deletionAuthorization: { deletion in
                    try sessionGate.performIfCurrent(adminSession, operation: deletion)
                }
            )
            self.operatorAddress = nil
            operatorStatus = nil
            operatorStatusMessage = nil
            validatedConfigurationFingerprint = nil
            settings = settings.clearingProvisioning()
            provisioningMessage = "Operator wallet reset. Create and register a new operator before accepting payments."
            errorMessage = nil
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
        guard settings.isProvisioned else {
            operatorStatus = nil
            operatorStatusMessage = "Scan the portal provisioning QR to bind a vault."
            return
        }
        do {
            let settingsSnapshot = settings
            let configuration = try settingsSnapshot.configuration()
            let status = try await fetchOperatorStatus(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            guard settings == settingsSnapshot,
                  self.operatorAddress == operatorAddress
            else { throw AppSafetyError.configurationChanged }
            operatorStatus = status
            operatorStatusMessage = nil
        } catch {
            operatorStatus = nil
            operatorStatusMessage = error.localizedDescription
        }
    }

    func prepareSettlement(for invoices: [StoredInvoice]) async {
        guard !invoices.isEmpty else { return }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        do {
            guard invoices.count <= 20 else { throw AppSettlementError.invalidSelection }

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
            for (request, snapshot) in zip(requests, configurations) {
                try validateSnapshot(request, against: snapshot)
            }
            guard let operatorAddress else { throw OperatorWalletError.walletNotCreated }
            try enforceDurableNonceGate(
                operatorAddress: operatorAddress,
                chainID: configuration.chainID
            )
            _ = try await historicalConfigurationValidator.validateHistoricalConfiguration(
                configuration
            )

            let cumulative = try currentCumulativeTotals(for: invoices)
            let confirmationSnapshots = try invoices.map { invoice in
                let key = try invoice.cumulativeSettlementKey()
                guard let snapshot = invoice.confirmedSweepableSnapshot(
                    confirmedCumulative: cumulative[key, default: .zero]
                ) else { throw AppSettlementError.unconfirmedSweepableBalance }
                return snapshot
            }
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
            try await revalidateSweepableConfirmations(
                invoices: invoices,
                configuration: configuration,
                expectedSnapshots: confirmationSnapshots
            )
            let prepared = try await coordinator.prepare(intent)
            let refreshedCumulative = try currentCumulativeTotals(for: invoices)
            let refreshedSnapshots = try invoices.map { invoice in
                let key = try invoice.cumulativeSettlementKey()
                guard let snapshot = invoice.confirmedSweepableSnapshot(
                    confirmedCumulative: refreshedCumulative[key, default: .zero]
                ) else { throw AppSettlementError.confirmedBalanceChanged }
                return snapshot
            }
            guard refreshedSnapshots == confirmationSnapshots else {
                throw AppSettlementError.confirmedBalanceChanged
            }
            let confirmedObservedBalances = try invoices.map {
                try UInt256(decimalString: $0.observedBalance)
            }
            guard prepared.observedTokenBalances == confirmedObservedBalances else {
                throw AppSettlementError.confirmedBalanceChanged
            }
            preparedSettlement = prepared
            preparedConfiguration = configuration
            preparedConfirmationSnapshots = confirmationSnapshots
            errorMessage = nil
        } catch {
            preparedSettlement = nil
            preparedConfiguration = nil
            preparedConfirmationSnapshots = nil
            errorMessage = error.localizedDescription
        }
    }

    func cancelPreparedSettlement() {
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        preparedSettlement = nil
        preparedConfiguration = nil
        preparedConfirmationSnapshots = nil
    }

    func confirmPreparedSettlement() async {
        guard let preparedSettlement,
              let configuration = preparedConfiguration,
              let expectedSnapshots = preparedConfirmationSnapshots,
              let operatorAddress
        else { return }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }

        do {
            _ = try await historicalConfigurationValidator.validateHistoricalConfiguration(
                configuration
            )
            let coordinator = try coordinator(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            let invoices = try storedInvoices(for: preparedSettlement.intent)
            let cumulative = try currentCumulativeTotals(for: invoices)
            let currentSnapshots = try invoices.map { invoice in
                let key = try invoice.cumulativeSettlementKey()
                guard let snapshot = invoice.confirmedSweepableSnapshot(
                    confirmedCumulative: cumulative[key, default: .zero]
                ) else { throw AppSettlementError.unconfirmedSweepableBalance }
                return snapshot
            }
            guard currentSnapshots == expectedSnapshots else {
                throw AppSettlementError.confirmedBalanceChanged
            }
            try await revalidateSweepableConfirmations(
                invoices: invoices,
                configuration: configuration,
                expectedSnapshots: expectedSnapshots
            )
            let count = preparedSettlement.intent.sessions.count
            let signed = try await coordinator.sign(
                preparedSettlement,
                authenticationReason: "Authorize a zero-value sweep of \(count) funded session\(count == 1 ? "" : "s") to the configured vault",
                postAuthenticationValidation: { [weak self] in
                    guard let self else { throw AppSettlementError.operationInProgress }
                    try await self.revalidatePreparedSettlementAfterAuthentication(
                        intent: preparedSettlement.intent,
                        configuration: configuration,
                        expectedSnapshots: expectedSnapshots
                    )
                }
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
            try saveMainContextOrRollback()

            let submission = await coordinator.broadcast(signed)
            try stored.apply(submission)
            try saveMainContextOrRollback()
            self.preparedSettlement = nil
            preparedConfiguration = nil
            preparedConfirmationSnapshots = nil
            errorMessage = submission.broadcastError
            await refreshOperatorStatus()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func reconcileSettlements() async {
        guard beginExclusiveOperation() else { return }
        defer { endExclusiveOperation() }
        do {
            let records = try container.mainContext.fetch(
                SettlementReconciliationPolicy.activeFetchDescriptor()
            )
            for record in records {
                do {
                    try Task.checkCancellation()
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
                    var result = try await coordinator.reconcile(
                        transactionHash: Bytes32(hex: record.transactionHash),
                        intent: intent,
                        requiredConfirmations: required,
                        priorPhase: record.phase
                    )
                    try Task.checkCancellation()
                    // Check for a receipt first. Only a still receipt-less pending/unknown record
                    // needs the exact persisted raw transaction rebroadcast.
                    if result.blockNumber == nil &&
                        (record.phase == .unknown || record.phase == .pending) {
                        let retry = await coordinator.retryPersistedBroadcast(
                            transactionHash: try Bytes32(hex: record.transactionHash),
                            rawTransaction: record.rawTransaction,
                            intent: intent,
                            nonce: nonce
                        )
                        try Task.checkCancellation()
                        try record.apply(retry)
                        try saveMainContextOrRollback()
                        result = try await coordinator.reconcile(
                            transactionHash: Bytes32(hex: record.transactionHash),
                            intent: intent,
                            requiredConfirmations: required,
                            priorPhase: record.phase
                        )
                        try Task.checkCancellation()
                    }
                    try record.apply(result)
                    try saveMainContextOrRollback()
                } catch is CancellationError {
                    return
                } catch let error as URLError where error.code == .cancelled {
                    return
                } catch {
                    record.phase = .unknown
                    record.failureReason = error.localizedDescription
                    record.updatedAt = Date()
                    try saveMainContextOrRollback()
                }
            }
            try Task.checkCancellation()
            try indexCanonicalSettlementEvidence()
            try healCumulativeSettlementEvidence()
        } catch is CancellationError {
            return
        } catch let error as URLError where error.code == .cancelled {
            return
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func indexCanonicalSettlementEvidence(
        now: Date = Date(),
        batchLimit: Int = SettlementReconciliationPolicy.evidenceBatchLimit
    ) throws {
        let recordIDs = try container.mainContext.fetch(
            SettlementReconciliationPolicy.evidenceFetchDescriptor(
                now: now,
                limit: batchLimit
            )
        ).map(\.id)

        for recordID in recordIDs {
            try Task.checkCancellation()
            do {
                guard let record = try settlementRecord(id: recordID) else { continue }
                try applyCanonicalEvidenceIndex(for: record, at: now)
                try Task.checkCancellation()
                record.cumulativeEvidenceIndexed = true
                record.cumulativeEvidenceLastAttemptAt = now
                record.cumulativeEvidenceNextAttemptAt = .distantFuture
                record.cumulativeEvidenceFailureCount = 0
                record.cumulativeEvidenceLastError = nil
                record.updatedAt = now
                // Each row is its own transaction. A malformed or temporarily unpersistable
                // record cannot roll back an earlier healthy row or starve later eligible work.
                try saveMainContextOrRollback()
            } catch is CancellationError {
                container.mainContext.rollback()
                throw CancellationError()
            } catch {
                container.mainContext.rollback()
                try Task.checkCancellation()
                try persistCanonicalEvidenceIndexFailure(
                    settlementID: recordID,
                    error: error,
                    at: now
                )
            }
        }
    }

    private func settlementRecord(id: UUID) throws -> StoredSettlement? {
        var descriptor = FetchDescriptor<StoredSettlement>(
            predicate: #Predicate { $0.id == id }
        )
        descriptor.fetchLimit = 1
        return try container.mainContext.fetch(descriptor).first
    }

    private func applyCanonicalEvidenceIndex(
        for record: StoredSettlement,
        at now: Date
    ) throws {
        let evidence = try OperatorResetSafety.canonicalEvidence(in: record)
        var pending = [(CanonicalSettlementEvidence, StoredInvoice?)]()
        for proof in evidence {
            let proofIdentity = proof.identity
            var proofDescriptor = FetchDescriptor<StoredCanonicalSweepProof>(
                predicate: #Predicate { $0.identity == proofIdentity }
            )
            proofDescriptor.fetchLimit = 1
            if try container.mainContext.fetch(proofDescriptor).first != nil { continue }

            let invoiceID = proof.key.invoiceID
            var invoiceDescriptor = FetchDescriptor<StoredInvoice>(
                predicate: #Predicate { $0.invoiceID == invoiceID }
            )
            invoiceDescriptor.fetchLimit = 1
            let invoice = try container.mainContext.fetch(invoiceDescriptor).first
            if let invoice {
                guard try invoice.cumulativeSettlementKey() == proof.key,
                      Int64(exactly: proof.minedBlock) != nil
                else { throw AppSettingsError.invalidValue }
                let current = try UInt256(
                    decimalString: invoice.confirmedCumulativeSweptAmount
                )
                let (_, overflow) = current.addingReportingOverflow(proof.amount)
                guard !overflow else { throw AppSettingsError.invalidValue }
            }
            pending.append((proof, invoice))
        }

        for (proof, invoice) in pending {
            if let invoice {
                let current = try UInt256(
                    decimalString: invoice.confirmedCumulativeSweptAmount
                )
                let (updated, overflow) = current.addingReportingOverflow(proof.amount)
                guard !overflow,
                      let storedBlock = Int64(exactly: proof.minedBlock)
                else { throw AppSettingsError.invalidValue }
                invoice.confirmedCumulativeSweptAmount = updated.decimalString
                invoice.confirmedCumulativeSweptThroughBlock = max(
                    invoice.confirmedCumulativeSweptThroughBlock ?? storedBlock,
                    storedBlock
                )
            }
            container.mainContext.insert(StoredCanonicalSweepProof(
                identity: proof.identity,
                settlementID: record.id,
                appliedAt: now
            ))
        }
    }

    private func persistCanonicalEvidenceIndexFailure(
        settlementID: UUID,
        error: Error,
        at now: Date
    ) throws {
        guard let record = try settlementRecord(id: settlementID) else { return }
        let failureCount = min(record.cumulativeEvidenceFailureCount + 1, 16)
        record.cumulativeEvidenceIndexed = false
        record.cumulativeEvidenceLastAttemptAt = now
        record.cumulativeEvidenceNextAttemptAt = now.addingTimeInterval(
            SettlementReconciliationPolicy.evidenceFailureDelay(
                failureCount: failureCount
            )
        )
        record.cumulativeEvidenceFailureCount = failureCount
        record.cumulativeEvidenceLastError = String(
            "Cumulative proof indexing failed closed: \(error.localizedDescription)"
                .prefix(512)
        )
        record.updatedAt = now
        try saveMainContextOrRollback()
    }

    func healCumulativeSettlementEvidence(
        now: Date = Date(),
        batchLimit: Int = SettlementReconciliationPolicy.cumulativeReviewBatchLimit
    ) throws {
        let recordIDs = try container.mainContext.fetch(
            SettlementReconciliationPolicy.cumulativeReviewFetchDescriptor(
                now: now,
                limit: batchLimit
            )
        ).map(\.id)

        for recordID in recordIDs {
            try Task.checkCancellation()
            do {
                guard let record = try settlementRecord(id: recordID),
                      record.phase == .needsReview,
                      record.cumulativeEvidenceIndexed
                else { continue }
                let isComplete = try cumulativeReviewIsComplete(record)
                try Task.checkCancellation()
                if isComplete {
                    record.phase = .final
                    record.failureReason = nil
                    record.cumulativeReviewNextAttemptAt = .distantFuture
                } else {
                    // A healthy but incomplete row is delayed and rotated behind untouched rows.
                    record.cumulativeReviewNextAttemptAt = now.addingTimeInterval(
                        SettlementReconciliationPolicy.cumulativeReviewRotationDelay
                    )
                }
                record.cumulativeReviewLastAttemptAt = now
                record.cumulativeReviewFailureCount = 0
                record.cumulativeReviewLastError = nil
                record.updatedAt = now
                // Persist each row independently so one malformed oldest record cannot roll
                // back healthy repairs completed earlier in this bounded pass.
                try saveMainContextOrRollback()
            } catch is CancellationError {
                container.mainContext.rollback()
                throw CancellationError()
            } catch {
                container.mainContext.rollback()
                try Task.checkCancellation()
                try persistCumulativeReviewFailure(
                    settlementID: recordID,
                    error: error,
                    at: now
                )
            }
        }
    }

    private func cumulativeReviewIsComplete(_ record: StoredSettlement) throws -> Bool {
        let intent = try record.intent()
        // Cumulative healing can complete the amounts for a partial positive sweep, but it
        // must not relabel a canonical receipt that never proved every batch session as final.
        guard try OperatorResetSafety.canonicalEvidence(in: record).count
            == intent.sessions.count
        else { return false }
        for session in intent.sessions {
            try Task.checkCancellation()
            let invoiceID = session.invoiceID.hex
            var descriptor = FetchDescriptor<StoredInvoice>(
                predicate: #Predicate { $0.invoiceID == invoiceID }
            )
            descriptor.fetchLimit = 1
            guard let invoice = try container.mainContext.fetch(descriptor).first,
                  try invoice.cumulativeSettlementKey() == CumulativeSettlementKey(
                    chainID: intent.chainID,
                    vault: intent.vault.hex,
                    invoiceID: session.invoiceID.hex,
                    token: intent.token.hex
                  ),
                  let total = try? UInt256(
                    decimalString: invoice.confirmedCumulativeSweptAmount
                  ),
                  total >= session.expectedAmount
            else { return false }
        }
        return true
    }

    private func persistCumulativeReviewFailure(
        settlementID: UUID,
        error: Error,
        at now: Date
    ) throws {
        guard let record = try settlementRecord(id: settlementID) else { return }
        let failureCount = min(record.cumulativeReviewFailureCount + 1, 16)
        record.cumulativeReviewLastAttemptAt = now
        record.cumulativeReviewNextAttemptAt = now.addingTimeInterval(
            SettlementReconciliationPolicy.evidenceFailureDelay(
                failureCount: failureCount
            )
        )
        record.cumulativeReviewFailureCount = failureCount
        record.cumulativeReviewLastError = String(
            "Cumulative settlement repair failed closed: \(error.localizedDescription)"
                .prefix(512)
        )
        record.updatedAt = now
        try saveMainContextOrRollback()
    }

    nonisolated private static func sampleForegroundInvoice(
        _ candidate: ForegroundInvoiceReconciliationCandidate,
        now: Date
    ) async -> ForegroundInvoiceReconciliationOutcome {
        do {
            try Task.checkCancellation()
            let configuration = candidate.configuration
            let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
            let actualChainID = try await rpc.chainID()
            try Task.checkCancellation()
            guard actualChainID == configuration.chainID else {
                throw AppSafetyError.snapshotChainMismatch(
                    expected: configuration.chainID,
                    actual: actualChainID
                )
            }
            let monitor = PaymentMonitor(
                rpc: rpc,
                confirmationPolicy: configuration.confirmationPolicy
            )
            let observation = try await monitor.sample(
                candidate.request,
                previousThresholdCursor: candidate.previousThresholdCursor,
                additionalCursors: candidate.additionalCursors,
                now: now
            )
            try Task.checkCancellation()
            return .success(invoiceID: candidate.invoiceID, observation: observation)
        } catch is CancellationError {
            return .cancelled(invoiceID: candidate.invoiceID)
        } catch let error as URLError where error.code == .cancelled {
            return .cancelled(invoiceID: candidate.invoiceID)
        } catch {
            return .failure(
                invoiceID: candidate.invoiceID,
                message: error.localizedDescription
            )
        }
    }

    private func persistForegroundInvoiceReconciliation(
        _ outcome: ForegroundInvoiceReconciliationOutcome,
        at date: Date
    ) {
        let invoiceID = outcome.invoiceID
        do {
            var descriptor = FetchDescriptor<StoredInvoice>(
                predicate: #Predicate { $0.invoiceID == invoiceID }
            )
            descriptor.fetchLimit = 1
            guard let invoice = try container.mainContext.fetch(descriptor).first else { return }
            switch outcome {
            case let .success(_, observation):
                guard let confirmedAtObservation = try confirmedCumulativeTotal(
                    for: invoice,
                    through: observation.blockNumber
                ) else {
                    // Canonical evidence newer than this sampled block was indexed while the RPC
                    // request was in flight. Discard the stale sample and retry promptly.
                    invoice.cancelForegroundReconciliation(at: date)
                    try saveMainContextOrRollback()
                    return
                }
                try invoice.recordForegroundReconciliationSuccess(
                    observation,
                    cumulativeConfirmedSweptAmount: confirmedAtObservation,
                    at: date
                )
            case let .failure(_, message):
                invoice.recordForegroundReconciliationFailure(message, at: date)
            case .cancelled:
                invoice.cancelForegroundReconciliation(at: date)
            }
            try saveMainContextOrRollback()
        } catch {
            do {
                var descriptor = FetchDescriptor<StoredInvoice>(
                    predicate: #Predicate { $0.invoiceID == invoiceID }
                )
                descriptor.fetchLimit = 1
                if let invoice = try container.mainContext.fetch(descriptor).first {
                    invoice.recordForegroundReconciliationFailure(
                        error.localizedDescription,
                        at: date
                    )
                    try saveMainContextOrRollback()
                }
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func confirmedCumulativeTotal(
        for invoice: StoredInvoice,
        through block: UInt64
    ) throws -> UInt256? {
        if let throughValue = invoice.confirmedCumulativeSweptThroughBlock {
            guard let throughBlock = UInt64(exactly: throughValue) else {
                throw AppSettingsError.invalidValue
            }
            guard throughBlock <= block else { return nil }
        }
        let amount = try UInt256(decimalString: invoice.confirmedCumulativeSweptAmount)
        guard amount.decimalString == invoice.confirmedCumulativeSweptAmount else {
            throw AppSettingsError.invalidValue
        }
        return amount
    }

    private func currentCumulativeTotals(
        for invoices: [StoredInvoice]
    ) throws -> [CumulativeSettlementKey: UInt256] {
        var totals = [CumulativeSettlementKey: UInt256]()
        for invoice in invoices {
            let key = try invoice.cumulativeSettlementKey()
            let amount = try UInt256(decimalString: invoice.confirmedCumulativeSweptAmount)
            guard amount.decimalString == invoice.confirmedCumulativeSweptAmount else {
                throw AppSettingsError.invalidValue
            }
            totals[key] = amount
        }
        return totals
    }

    private func storedInvoices(for intent: SettlementIntent) throws -> [StoredInvoice] {
        try intent.sessions.map { session in
            let invoiceID = session.invoiceID.hex
            var descriptor = FetchDescriptor<StoredInvoice>(
                predicate: #Predicate { $0.invoiceID == invoiceID }
            )
            descriptor.fetchLimit = 1
            guard let invoice = try container.mainContext.fetch(descriptor).first,
                  try invoice.cumulativeSettlementKey() == CumulativeSettlementKey(
                    chainID: intent.chainID,
                    vault: intent.vault.hex,
                    invoiceID: session.invoiceID.hex,
                    token: intent.token.hex
                  )
            else { throw AppSettlementError.mixedSnapshots }
            return invoice
        }
    }

    private func revalidateSweepableConfirmations(
        invoices: [StoredInvoice],
        configuration: TerminalConfiguration,
        expectedSnapshots: [SweepableConfirmationSnapshot]
    ) async throws {
        guard invoices.count == expectedSnapshots.count else {
            throw AppSettlementError.mixedSnapshots
        }
        let rpc = try JSONRPCEthereumClient(endpoint: configuration.rpcEndpoints[0])
        let actualChainID = try await rpc.chainID()
        guard actualChainID == configuration.chainID else {
            throw AppSafetyError.snapshotChainMismatch(
                expected: configuration.chainID,
                actual: actualChainID
            )
        }
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: configuration.confirmationPolicy
        )
        for (invoice, snapshot) in zip(invoices, expectedSnapshots) {
            try Task.checkCancellation()
            guard invoice.invoiceID == snapshot.invoiceID,
                  let cursor = snapshot.confirmationCursor
            else { throw AppSettlementError.unconfirmedSweepableBalance }
            let observation = try await monitor.sample(
                invoice.paymentRequest(),
                previousThresholdCursor: invoice.paymentThresholdCursor,
                additionalCursors: [cursor]
            )
            guard snapshot.isRevalidated(by: observation) else {
                throw AppSettlementError.confirmedBalanceChanged
            }
        }

        // No observer/proof-index update may replace the selected snapshot while RPC validation
        // was in flight. This also binds the exact cumulative-proof baseline used by the intent.
        let cumulative = try currentCumulativeTotals(for: invoices)
        let refreshed = try invoices.map { invoice in
            let key = try invoice.cumulativeSettlementKey()
            guard let snapshot = invoice.confirmedSweepableSnapshot(
                confirmedCumulative: cumulative[key, default: .zero]
            ) else { throw AppSettlementError.unconfirmedSweepableBalance }
            return snapshot
        }
        guard refreshed == expectedSnapshots else {
            throw AppSettlementError.confirmedBalanceChanged
        }
    }

    /// Runs inside the wallet's authenticated signing boundary. The authentication prompt may
    /// have been open indefinitely, so resolve the rows again and recheck both the exact live
    /// balances and their saved canonical confirmation cursors immediately before key use.
    private func revalidatePreparedSettlementAfterAuthentication(
        intent: SettlementIntent,
        configuration: TerminalConfiguration,
        expectedSnapshots: [SweepableConfirmationSnapshot]
    ) async throws {
        let invoices = try storedInvoices(for: intent)
        try await revalidateSweepableConfirmations(
            invoices: invoices,
            configuration: configuration,
            expectedSnapshots: expectedSnapshots
        )
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

    private func fetchOperatorStatus(
        configuration: TerminalConfiguration,
        operatorAddress: EthereumAddress
    ) async throws -> OperatorChainStatus {
        let coordinator = try coordinator(
            configuration: configuration,
            operatorAddress: operatorAddress
        )
        return try await coordinator.refreshStatus(
            expectedChainID: configuration.chainID,
            vault: configuration.deployment.vault
        )
    }

    private func beginExclusiveOperation() -> Bool {
        guard lifecycleOperationGate.acquire() else { return false }
        operationBusy = true
        return true
    }

    private func endExclusiveOperation() {
        lifecycleOperationGate.release()
        operationBusy = false
    }

    /// SwiftData keeps failed mutations registered in memory unless the context is explicitly
    /// rolled back. Never allow an invoice total, proof-ledger insert, settlement phase, or
    /// signed-transaction insert from a failed save to hitchhike on a later unrelated save.
    func saveMainContextOrRollback() throws {
        do {
            try persistMainContext(container.mainContext)
        } catch {
            container.mainContext.rollback()
            throw error
        }
    }

    private func existingRPCOverride(
        for chainID: UInt64,
        settings: AppSettings
    ) -> URL? {
        guard settings.chainID == String(chainID),
              let profile = TerminalKnownChainProfile.profile(for: chainID),
              let endpoint = URL(string: settings.rpcURL),
              endpoint != profile.rpcEndpoint,
              (try? RPCURLPolicy.validate(endpoint)) != nil
        else { return nil }
        return endpoint
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
                while !Task.isCancelled {
                    let cursors = try self.confirmationCursors(
                        for: request.invoiceID.hex
                    )
                    let observation = try await monitor.sample(
                        request,
                        previousThresholdCursor: cursors.payment,
                        additionalCursors: cursors.sweepable.map { [$0] } ?? []
                    )
                    guard !Task.isCancelled else { return }
                    self.activeObservation = observation
                    try self.persist(observation)
                    switch observation.status {
                    case .paid, .overpaid, .expired:
                        return
                    default:
                        break
                    }
                    try await Task.sleep(
                        nanoseconds: monitor.pollIntervalNanoseconds
                    )
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
            guard let confirmedAtObservation = try confirmedCumulativeTotal(
                for: invoice,
                through: observation.blockNumber
            ) else { return }
            try invoice.apply(
                observation,
                cumulativeConfirmedSweptAmount: confirmedAtObservation
            )
            try saveMainContextOrRollback()
        }
    }

    private func confirmationCursors(
        for invoiceID: String
    ) throws -> InvoiceConfirmationCursors {
        var descriptor = FetchDescriptor<StoredInvoice>(
            predicate: #Predicate { $0.invoiceID == invoiceID }
        )
        descriptor.fetchLimit = 1
        guard let invoice = try container.mainContext.fetch(descriptor).first else {
            throw AppSafetyError.corruptInvoiceSnapshot
        }
        return InvoiceConfirmationCursors(
            payment: invoice.paymentThresholdCursor,
            sweepable: invoice.sweepableConfirmationCursor
        )
    }
}

private struct ForegroundInvoiceReconciliationCandidate: Sendable {
    let invoiceID: String
    let request: PaymentRequest
    let configuration: TerminalConfiguration
    let previousThresholdCursor: PaymentConfirmationCursor?
    let additionalCursors: [PaymentConfirmationCursor]
}

private struct InvoiceConfirmationCursors: Sendable {
    let payment: PaymentConfirmationCursor?
    let sweepable: PaymentConfirmationCursor?
}

private enum ForegroundInvoiceReconciliationOutcome: Sendable {
    case success(invoiceID: String, observation: PaymentObservation)
    case failure(invoiceID: String, message: String)
    case cancelled(invoiceID: String)

    var invoiceID: String {
        switch self {
        case let .success(invoiceID, _),
             let .failure(invoiceID, _),
             let .cancelled(invoiceID):
            invoiceID
        }
    }
}

private struct AdminSessionEpoch: Hashable, Sendable {
    fileprivate let identifier: UUID
}

/// Process-local, deliberately non-persistent authorization epoch. Background locking clears
/// the current epoch, and destructive cross-actor commits execute while holding this gate so
/// invalidation and deletion have one linear order.
private final class AdminSessionGate: @unchecked Sendable {
    private let lock = NSLock()
    private var currentIdentifier: UUID?

    @discardableResult
    func unlock() -> AdminSessionEpoch {
        lock.lock()
        defer { lock.unlock() }
        let identifier = UUID()
        currentIdentifier = identifier
        return AdminSessionEpoch(identifier: identifier)
    }

    func invalidate() {
        lock.lock()
        currentIdentifier = nil
        lock.unlock()
    }

    func capture() -> AdminSessionEpoch? {
        lock.lock()
        defer { lock.unlock() }
        return currentIdentifier.map(AdminSessionEpoch.init(identifier:))
    }

    func requireCurrent(_ epoch: AdminSessionEpoch) throws {
        lock.lock()
        defer { lock.unlock() }
        guard currentIdentifier == epoch.identifier else {
            throw AppSafetyError.adminSessionExpired
        }
    }

    func performIfCurrent<T>(
        _ epoch: AdminSessionEpoch,
        operation: () throws -> T
    ) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        guard currentIdentifier == epoch.identifier else {
            throw AppSafetyError.adminSessionExpired
        }
        return try operation()
    }
}

private enum AppSafetyError: LocalizedError {
    case operatorWalletRequired
    case provisioningRequired
    case operatorBindingMismatch
    case terminalNotReady(String)
    case configurationChanged
    case adminSessionExpired
    case operatorResetBlockedBySettlement
    case operatorResetBlockedByIssuedInvoice
    case receiverAlreadyDeployed
    case receiverAlreadyFunded
    case corruptInvoiceSnapshot
    case snapshotChainMismatch(expected: UInt64, actual: UInt64)

    var errorDescription: String? {
        switch self {
        case .operatorWalletRequired:
            "Create the terminal operator wallet in Settings before creating a payment QR. Historical invoices remain available."
        case .provisioningRequired:
            "Provision this terminal from the merchant portal before creating a payment QR."
        case .operatorBindingMismatch:
            "The saved provisioning is bound to a different operator. Reprovision this terminal."
        case let .terminalNotReady(message):
            message
        case .configurationChanged:
            "Terminal settings changed during validation. Retry with the current configuration."
        case .adminSessionExpired:
            "The Admin session was locked while this operation was in progress. Unlock Admin and retry."
        case .operatorResetBlockedBySettlement:
            "The operator cannot be reset while a settlement is pending, mined, unresolved, or needs review."
        case .operatorResetBlockedByIssuedInvoice:
            "This operator key cannot be deleted after any payment QR has been issued. Published receiver addresses remain payable forever; revoke or reprovision the operator without deleting this device key."
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
    case operationInProgress
    case unconfirmedSweepableBalance
    case confirmedBalanceChanged

    var errorDescription: String? {
        switch self {
        case .invalidSelection:
            "Choose between 1 and 20 confirmed sweepable invoices from one token group."
        case .alreadySubmitted:
            "At least one selected invoice already has an active settlement transaction."
        case .mixedSnapshots:
            "The selected invoices do not share the same saved chain, vault, token, and RPC configuration."
        case .walletMismatch:
            "The saved transaction belongs to a different device operator wallet and cannot be reconciled here."
        case .unresolvedOperatorNonce:
            "This operator already has an unresolved transaction on the selected chain. Reconcile or re-broadcast its saved raw transaction before signing another nonce."
        case .operationInProgress:
            "Another terminal lifecycle operation is already in progress."
        case .unconfirmedSweepableBalance:
            "The currently sweepable receiver balance has not reached the invoice's saved confirmation requirement, or its cumulative proof baseline changed. Refresh and wait for confirmation."
        case .confirmedBalanceChanged:
            "A receiver balance changed after it was confirmed. Refresh and wait for the current amount to reach the saved confirmation requirement before settling."
        }
    }
}
