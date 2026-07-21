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

typealias PaymentObservationSampling = @Sendable (
    _ request: PaymentRequest,
    _ configuration: TerminalConfiguration,
    _ paymentCursor: PaymentConfirmationCursor?,
    _ sweepableCursors: [PaymentConfirmationCursor]
) async throws -> PaymentObservation

enum AdminPINConfigurationState: Equatable {
    case configured
    case notConfigured
    case unavailable(String)
}

@MainActor
final class AppModel: ObservableObject {
    @Published var settings: AppSettings {
        didSet {
            // A corrupt persisted catalog is kept byte-for-byte until the device admin explicitly
            // quarantines it. Incidental readiness or UI mutations must never overwrite the only
            // recovery evidence with an empty default configuration.
            if !settingsRecoveryRequired {
                AppPreferences.saveSettings(settings)
            }
            guard !settings.hasSamePaymentConfiguration(as: oldValue) else { return }
            validatedConfigurationFingerprint = nil
            configurationValidationProof = nil
            preparedSettlementValidationProof = nil
            validationMessage = "On-chain validation required"
            settlementCoordinator = nil
            settlementCoordinatorKey = nil
            operatorStatus = nil
        }
    }
    @Published private(set) var validationMessage = "On-chain validation required"
    @Published private(set) var activeRequest: PaymentRequest?
    @Published private(set) var activeObservation: PaymentObservation?
    @Published private(set) var isBusy = false
    @Published private(set) var isRefreshingReadiness = false
    @Published private(set) var operationBusy = false
    @Published private(set) var operatorAddress: EthereumAddress?
    @Published private(set) var operatorStatus: OperatorChainStatus?
    @Published private(set) var operatorStatusMessage: String?
    @Published private(set) var validatedConfigurationFingerprint: String?
    @Published private(set) var provisioningMessage: String?
    @Published private(set) var isProvisioning = false
    @Published private(set) var adminPINConfigurationState: AdminPINConfigurationState
    @Published private(set) var adminUnlocked: Bool
    @Published private(set) var preparedSettlement: PreparedSettlement?
    @Published private(set) var settingsRecoveryRequired = false
    @Published var errorMessage: String?

    private let container: ModelContainer
    private let operatorWallet: KeychainOperatorWallet
    private let operatorWalletLifecycle: any OperatorWalletLifecycleManaging
    private let provisioningValidator: any TerminalProvisioningValidating
    private let historicalConfigurationValidator: any HistoricalTerminalConfigurationValidating
    private let adminPINStore: any AdminPINManaging
    private let persistMainContext: (ModelContext) throws -> Void
    private let currentConfigurationValidation: @Sendable (
        TerminalConfiguration
    ) async throws -> Void
    private let operatorStatusReader: (@Sendable (
        TerminalConfiguration,
        EthereumAddress
    ) async throws -> OperatorChainStatus)?
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
    private let backgroundRPCWorkGate: BackgroundRPCWorkGate
    private let backgroundRPCUnitDeadline: Duration
    private let paymentObservationSampler: PaymentObservationSampling
    private let paymentMonitorPollIntervalNanoseconds: UInt64
    private let validationNow: @Sendable () -> Date
    private let configurationValidationTTL: TimeInterval
    private let preparedSettlementValidationTTL: TimeInterval
    private var configurationValidationProof: ConfigurationValidationProof?
    private var preparedSettlementValidationProof: PreparedSettlementValidationProof?

    init(
        container: ModelContainer,
        operatorWallet: KeychainOperatorWallet = KeychainOperatorWallet(),
        operatorWalletLifecycle: (any OperatorWalletLifecycleManaging)? = nil,
        provisioningValidator: any TerminalProvisioningValidating = TerminalProvisioner(),
        historicalConfigurationValidator: any HistoricalTerminalConfigurationValidating = TerminalProvisioner(),
        adminPINStore: any AdminPINManaging = KeychainAdminPINStore(),
        persistMainContext: @escaping (ModelContext) throws -> Void = { try $0.save() },
        currentConfigurationValidation: @escaping @Sendable (
            TerminalConfiguration
        ) async throws -> Void = { configuration in
            let rpc = try EthereumRPCClientPool.shared.client(
                for: configuration.rpcEndpoints[0]
            )
            _ = try await ConfigurationValidator(rpc: rpc).validate(configuration)
        },
        operatorStatusReader: (@Sendable (
            TerminalConfiguration,
            EthereumAddress
        ) async throws -> OperatorChainStatus)? = nil,
        operatorResetBalanceReader: (@Sendable (
            TerminalConfiguration,
            EthereumAddress
        ) async throws -> OperatorNativeBalanceSnapshot)? = nil,
        validationNow: @escaping @Sendable () -> Date = Date.init,
        configurationValidationTTL: TimeInterval = 5 * 60,
        preparedSettlementValidationTTL: TimeInterval = 60,
        interactiveBackgroundDrainTimeout: Duration = .seconds(5),
        backgroundRPCUnitDeadline: Duration = .seconds(5),
        paymentObservationSampler: PaymentObservationSampling? = nil,
        paymentMonitorPollIntervalNanoseconds: UInt64 =
            PaymentMonitor.defaultPollIntervalNanoseconds
    ) {
        precondition(interactiveBackgroundDrainTimeout > .zero)
        precondition(backgroundRPCUnitDeadline > .zero)
        precondition(paymentMonitorPollIntervalNanoseconds > 0)
        self.container = container
        self.operatorWallet = operatorWallet
        self.operatorWalletLifecycle = operatorWalletLifecycle ?? operatorWallet
        self.provisioningValidator = provisioningValidator
        self.historicalConfigurationValidator = historicalConfigurationValidator
        self.adminPINStore = adminPINStore
        self.persistMainContext = persistMainContext
        self.currentConfigurationValidation = currentConfigurationValidation
        self.operatorStatusReader = operatorStatusReader
        self.operatorResetBalanceReader = operatorResetBalanceReader
        self.validationNow = validationNow
        self.configurationValidationTTL = max(0, configurationValidationTTL)
        self.preparedSettlementValidationTTL = max(0, preparedSettlementValidationTTL)
        backgroundRPCWorkGate = BackgroundRPCWorkGate(
            maximumInteractiveDrainWait: interactiveBackgroundDrainTimeout
        )
        self.backgroundRPCUnitDeadline = backgroundRPCUnitDeadline
        self.paymentObservationSampler = paymentObservationSampler
            ?? { request, configuration, paymentCursor, sweepableCursors in
                let rpc = try EthereumRPCClientPool.shared.client(
                    for: configuration.rpcEndpoints[0]
                )
                return try await PaymentMonitor(
                    rpc: rpc,
                    confirmationPolicy: configuration.confirmationPolicy
                ).sample(
                    request,
                    previousThresholdCursor: paymentCursor,
                    additionalCursors: sweepableCursors,
                    expectedChainID: configuration.chainID
                )
            }
        self.paymentMonitorPollIntervalNanoseconds = paymentMonitorPollIntervalNanoseconds
        let settingsLoadResult = AppPreferences.loadSettingsResult()
        settings = settingsLoadResult.settings
        settingsRecoveryRequired = settingsLoadResult.recoveryRequired
        let pinConfigurationState: AdminPINConfigurationState
        do {
            pinConfigurationState = try adminPINStore.isConfigured
                ? .configured
                : .notConfigured
        } catch {
            pinConfigurationState = .unavailable(error.localizedDescription)
        }
        adminPINConfigurationState = pinConfigurationState
        adminUnlocked = pinConfigurationState == .notConfigured
        operatorAddress = try? self.operatorWalletLifecycle.existingAddress()
        if settingsRecoveryRequired {
            errorMessage = settingsRecoveryMessage
        }
    }

    var terminalReadiness: TerminalReadiness {
        TerminalReadiness.evaluate(
            settings: settings,
            operatorAddress: operatorAddress,
            validatedFingerprint: validatedConfigurationFingerprint,
            operatorStatus: operatorStatus
        )
    }

    var adminPINConfigured: Bool {
        adminPINConfigurationState == .configured
    }

    var adminPINConfigurationUnavailableMessage: String? {
        guard case let .unavailable(message) = adminPINConfigurationState else { return nil }
        return message
    }

    var canAccessAdmin: Bool {
        switch adminPINConfigurationState {
        case .notConfigured:
            true
        case .configured:
            adminUnlocked
        case .unavailable:
            false
        }
    }

    var settingsRecoveryMessage: String? {
        guard settingsRecoveryRequired else { return nil }
        if let unavailable = adminPINConfigurationUnavailableMessage {
            return "The saved terminal setup could not be verified and was not overwritten. "
                + "The local admin PIN verifier is unavailable: \(unavailable) "
                + "Restore Keychain access before recovery. The unreadable setup remains active; "
                + "the operator wallet and invoice history are unchanged."
        }
        return "The saved terminal setup could not be verified and was not overwritten. "
            + "Unlock Admin, quarantine the unreadable setup, then provision the terminal again. "
            + "The operator wallet and invoice history are unchanged."
    }

    var pendingSettingsMigrationMessage: String? {
        settings.migrationNotice.map(Self.migrationNoticeMessage)
    }

    func acknowledgeSettingsMigrationNotice() {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard settings.migrationNotice != nil else { return }
        var updated = settings
        updated.acknowledgeMigrationNotice()
        settings = updated
    }

    var operatorPairingPayload: String? {
        guard let operatorAddress else { return nil }
        return try? TerminalOperatorPairingPayload.encode(address: operatorAddress)
    }

    private static func migrationNoticeMessage(
        _ notice: AppSettingsMigrationNotice
    ) -> String {
        let count = notice.adjustedConfirmationProfileIDs.count
        return "Safety update: confirmation depth was raised to the compiled network minimum "
            + "for \(count) legacy payment profile\(count == 1 ? "" : "s")."
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

    func selectPaymentProfile(id: String) async {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard id != settings.selectedPaymentProfileID else { return }
        guard !operationBusy, !isProvisioning, !isRefreshingReadiness else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        do {
            settings = try settings.selectingPaymentProfile(id: id)
            provisioningMessage = nil
            errorMessage = nil
            await refreshReadiness()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removePaymentProfile(id: String) async {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before removing a payment profile."
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
        do {
            let original = settings
            let candidate = try original.removingPaymentProfile(id: id)
            guard settings == original else { throw AppSafetyError.configurationChanged }
            try adminSessionGate.requireCurrent(adminSession)
            settings = candidate
            provisioningMessage = candidate.paymentProfiles.isEmpty
                ? "Payment profile removed. Add a profile before accepting payments."
                : "Payment profile removed from this terminal. On-chain authorization was not changed."
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        // `settings` invalidated all selected-profile readiness state. Refresh only the profile
        // deterministically selected after removal; historical invoice snapshots are untouched.
        await refreshReadiness()
    }

    func configureAdminPIN(_ pin: String, confirmation: String) {
        do {
            guard pin == confirmation else { throw AdminPINError.invalidFormat }
            try adminPINStore.setPIN(pin)
            adminPINConfigurationState = .configured
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
            adminPINConfigurationState = .configured
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

    /// Explicit admin recovery for a catalog that failed current trust-pin or structural checks.
    /// The unreadable bytes are retained in a quarantine slot before a clean, unprovisioned
    /// catalog is persisted. The operator key and all durable invoice/settlement rows are separate.
    func resetUnreadableSettingsForRecovery() {
        guard settingsRecoveryRequired else { return }
        guard adminPINConfigurationUnavailableMessage == nil else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard canAccessAdmin else {
            errorMessage = "Unlock Admin before resetting unreadable terminal setup."
            return
        }
        if adminPINConfigured, adminSessionGate.capture() == nil {
            errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
            return
        }
        guard !operationBusy, !isProvisioning, !isRefreshingReadiness else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        guard AppPreferences.quarantineUnreadableSettings() else {
            errorMessage = "The unreadable setup could not be quarantined. Nothing was changed."
            return
        }

        settingsRecoveryRequired = false
        settings = AppSettings()
        validatedConfigurationFingerprint = nil
        validationMessage = "On-chain validation required"
        provisioningMessage = "Unreadable setup quarantined. Scan the merchant portal setup QR again."
        operatorStatus = nil
        operatorStatusMessage = nil
        errorMessage = nil
        if adminPINConfigured { lockAdmin() }
    }

    func provision(_ payload: TerminalProvisioningPayload) async {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
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
            await backgroundRPCWorkGate.waitUntilIdle()
            guard let knownNetwork = TerminalKnownChainProfile.profile(for: payload.chainID) else {
                throw AppSettingsError.unsupportedChain
            }
            let derived = try await provisioningValidator.deriveAndValidate(
                payload,
                expectedOperator: operatorAddress,
                confirmationPolicy: .init(
                    requiredBlocks: knownNetwork.defaultConfirmationBlocks
                ),
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
            configurationValidationProof = ConfigurationValidationProof(
                configuration: derived.configuration,
                fingerprint: candidate.validationFingerprint,
                validatedAt: validationNow()
            )
            validatedConfigurationFingerprint = candidate.validationFingerprint
            validationMessage = "On-chain validation passed"
            provisioningMessage = "Payment profile validated and saved. Existing profiles were preserved."
            errorMessage = nil
            lockAdmin()
            await refreshOperatorStatusWithinInteractiveOperation()
        } catch {
            provisioningMessage = "Provisioning rejected. Existing settings were not changed."
            errorMessage = error.localizedDescription
        }
    }

    func refreshReadiness() async {
        guard !isRefreshingReadiness, !operationBusy, !isProvisioning else { return }
        guard let backgroundToken = backgroundRPCWorkGate.acquire(
            interactiveOperationBusy: operationBusy
        ) else { return }
        isRefreshingReadiness = true
        defer {
            isRefreshingReadiness = false
            backgroundRPCWorkGate.release(backgroundToken)
        }

        await RPCRequestDeadline.withDeadline(after: backgroundRPCUnitDeadline) {
            guard settings.isProvisioned else {
                validatedConfigurationFingerprint = nil
                await refreshOperatorStatusWithoutRPCGate()
                return
            }
            _ = await validateConfiguration()
            await refreshOperatorStatusWithoutRPCGate()
        }
    }

    func validateConfiguration() async -> Bool {
        isBusy = true
        defer { isBusy = false }
        let snapshot = settings
        do {
            let configuration = try snapshot.configuration()
            try await validate(
                configuration,
                fingerprint: snapshot.validationFingerprint,
                allowCachedBackgroundProof: true
            )
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
            await backgroundRPCWorkGate.waitUntilIdle()
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
            let paymentProfile = try TerminalPaymentProfile(configuration: configuration)
            let token = paymentProfile.token
            let amount = try TokenAmount(display: displayAmount, decimals: token.decimals)
            let request = try InvoiceFactory.create(
                terminalIdentifier: TerminalIdentifier(address: operatorAddress),
                amount: amount,
                profile: paymentProfile,
                expiresAt: Date().addingTimeInterval(15 * 60)
            )
            let rpc = try EthereumRPCClientPool.shared.client(
                for: configuration.rpcEndpoints[0]
            )
            // These proofs are independent read-only operations. Launch them together so the
            // slowest fixed-head proof, rather than their sum, controls checkout latency. Every
            // result remains mandatory and the settings/operator snapshot is checked before any
            // invoice is persisted or shown.
            async let configurationProof: Void = validate(
                configuration,
                fingerprint: settingsSnapshot.validationFingerprint,
                allowCachedBackgroundProof: false
            )
            async let statusProof = fetchOperatorStatus(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            async let freshnessProof = ReceiverFreshnessValidator(rpc: rpc).validate(
                receiver: request.receiver,
                token: token.address,
                expectedChainID: configuration.chainID
            )
            let concurrentProofs: (Void, OperatorChainStatus, ReceiverFreshnessProof)
            do {
                concurrentProofs = try await (
                    configurationProof,
                    statusProof,
                    freshnessProof
                )
            } catch {
                // A concurrently failing endpoint must not hide a settings/operator mutation
                // that invalidated the entire locally derived request while proofs were running.
                guard settings == settingsSnapshot,
                      self.operatorAddress == operatorAddress
                else { throw AppSafetyError.configurationChanged }
                throw error
            }
            let (_, liveStatus, freshness) = concurrentProofs
            guard settings == settingsSnapshot,
                  self.operatorAddress == operatorAddress
            else { throw AppSafetyError.configurationChanged }
            validatedConfigurationFingerprint = settingsSnapshot.validationFingerprint
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
            guard freshness.receiverCode.isEmpty else {
                throw AppSafetyError.receiverAlreadyDeployed
            }
            guard freshness.tokenBalance.isZero else {
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

        await withTaskGroup(
            of: (UUID, ForegroundInvoiceReconciliationOutcome).self
        ) { group in
            var nextIndex = 0
            let backgroundDeadline = backgroundRPCUnitDeadline
            func enqueue(_ candidate: ForegroundInvoiceReconciliationCandidate, token: UUID) {
                group.addTask {
                    let outcome = await RPCRequestDeadline.withDeadline(
                        after: backgroundDeadline
                    ) {
                        await Self.sampleForegroundInvoice(candidate, now: now)
                    }
                    return (token, outcome)
                }
            }
            while nextIndex < candidates.count,
                  let token = backgroundRPCWorkGate.acquire(
                      interactiveOperationBusy: operationBusy
                  ) {
                enqueue(candidates[nextIndex], token: token)
                nextIndex += 1
            }
            for await (token, outcome) in group {
                backgroundRPCWorkGate.release(token)
                persistForegroundInvoiceReconciliation(outcome, at: Date())
                if nextIndex < candidates.count,
                   let nextToken = backgroundRPCWorkGate.acquire(
                       interactiveOperationBusy: operationBusy
                   ) {
                    let candidate = candidates[nextIndex]
                    nextIndex += 1
                    enqueue(candidate, token: nextToken)
                }
            }
            // An interactive operation may have started while a background sample was in
            // flight. Release the durable reservation for every unit that was not launched so
            // the next foreground pass can retry it promptly.
            while nextIndex < candidates.count {
                persistForegroundInvoiceReconciliation(
                    .cancelled(invoiceID: candidates[nextIndex].invoiceID),
                    at: Date()
                )
                nextIndex += 1
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
            await backgroundRPCWorkGate.waitUntilIdle()
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
            await refreshOperatorStatusWithinInteractiveOperation()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func resetOperatorWallet() async {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
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
            await backgroundRPCWorkGate.waitUntilIdle()
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
            let operationalConfigurations = try settings.configurations()
            // Key deletion must not trust an admin-editable or previously persisted RPC. A
            // compromised endpoint could otherwise report zero for a funded operator address.
            // Check every network enabled by this app build, not only currently saved profiles:
            // an admin may have removed the last profile for a chain that still holds operator gas.
            let resetTargets = try TerminalKnownChainProfile.all.map { trustedProfile in
                let operational = operationalConfigurations.first {
                    $0.chainID == trustedProfile.chainID
                }
                let deployment = try operational?.deployment ?? OPKDeployment(
                    factory: trustedProfile.factory,
                    receiverImplementation: trustedProfile.receiverImplementation,
                    vault: trustedProfile.create2TestVector.vault
                )
                let resetOnlyToken = try PaymentToken(
                    address: trustedProfile.factory,
                    symbol: "RESET",
                    decimals: 18
                )
                let configuration = try TerminalConfiguration(
                    chainID: trustedProfile.chainID,
                    rpcEndpoints: [trustedProfile.rpcEndpoint],
                    protocolVersion: trustedProfile.protocolVersion,
                    deployment: deployment,
                    tokens: operational?.tokens ?? [resetOnlyToken],
                    confirmationPolicy: operational?.confirmationPolicy
                        ?? .init(requiredBlocks: trustedProfile.defaultConfirmationBlocks),
                    create2TestVector: trustedProfile.create2TestVector
                )
                return (
                    configuration: configuration,
                    network: OperatorResetNetworkContext(trustedProfile)
                )
            }
            let readResetBalances: @Sendable () async throws -> [OperatorResetNetworkBalance]
            if let operatorResetBalanceReader {
                readResetBalances = {
                    var balances = [OperatorResetNetworkBalance]()
                    for target in resetTargets {
                        do {
                            let snapshot = try await operatorResetBalanceReader(
                                target.configuration,
                                operatorAddress
                            )
                            balances.append(OperatorResetNetworkBalance(
                                network: target.network,
                                snapshot: snapshot
                            ))
                        } catch is CancellationError {
                            throw CancellationError()
                        } catch {
                            throw OperatorResetSafety.networkReadFailure(
                                error,
                                network: target.network
                            )
                        }
                    }
                    return balances
                }
            } else {
                let coordinators = try resetTargets.map { target in
                    (
                        target,
                        try coordinator(
                            configuration: target.configuration,
                            operatorAddress: operatorAddress
                        )
                    )
                }
                readResetBalances = {
                    var balances = [OperatorResetNetworkBalance]()
                    for (target, resetCoordinator) in coordinators {
                        do {
                            let snapshot = try await resetCoordinator.resetSafetyBalances(
                                expectedChainID: target.configuration.chainID
                            )
                            balances.append(OperatorResetNetworkBalance(
                                network: target.network,
                                snapshot: snapshot
                            ))
                        } catch is CancellationError {
                            throw CancellationError()
                        } catch {
                            throw OperatorResetSafety.networkReadFailure(
                                error,
                                network: target.network
                            )
                        }
                    }
                    return balances
                }
            }
            for balance in try await readResetBalances() {
                try OperatorResetSafety.requireEmptyNativeBalance(balance)
            }
            let sessionGate = adminSessionGate
            try await operatorWalletLifecycle.reset(
                reason: "Permanently reset this terminal's empty settlement operator wallet",
                beforeDeletion: {
                    // Re-read after device authentication so a pending withdrawal cannot make a
                    // funded key look empty during the destructive confirmation window.
                    for balance in try await readResetBalances() {
                        try OperatorResetSafety.requireEmptyNativeBalance(balance)
                    }
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
        // UI and other independent callers must never infer that another task's lifecycle lock
        // means background work was drained on their behalf. Either acquire a background token
        // atomically while the terminal is idle or defer this nonessential refresh.
        guard !operationBusy,
              let backgroundToken = backgroundRPCWorkGate.acquire(
                  interactiveOperationBusy: operationBusy
              )
        else { return }
        defer { backgroundRPCWorkGate.release(backgroundToken) }
        await RPCRequestDeadline.withDeadline(after: backgroundRPCUnitDeadline) {
            await refreshOperatorStatusWithoutRPCGate()
        }
    }

    private func refreshOperatorStatusWithinInteractiveOperation() async {
        assert(operationBusy)
        await refreshOperatorStatusWithoutRPCGate()
    }

    private func refreshOperatorStatusWithoutRPCGate() async {
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
        preparedSettlementValidationProof = nil
        do {
            await backgroundRPCWorkGate.waitUntilIdle()
            guard invoices.count <= 20 else { throw AppSettlementError.invalidSelection }
            guard settlementBatchSnapshotsMatch(invoices) else {
                throw AppSettlementError.mixedSnapshots
            }

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
            guard invoiceOperatorSnapshotsMatch(
                requests.map(\.terminalIdentifier.address),
                currentOperator: operatorAddress
            ) else { throw AppSettlementError.walletMismatch }
            try enforceDurableNonceGate(
                operatorAddress: operatorAddress,
                chainID: configuration.chainID
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
            let confirmationSamples = try sweepableConfirmationSamples(
                invoices: invoices,
                expectedSnapshots: confirmationSnapshots
            )
            // Historical provenance and payment confirmations are independent, read-only
            // fixed-head proofs. Run their three-wave brackets together; both must complete
            // before any transaction preparation can begin.
            async let historicalProof = historicalConfigurationValidator
                .validateHistoricalConfiguration(configuration)
            async let confirmationProof: Void = Self.validateSweepableConfirmationSamples(
                confirmationSamples,
                configuration: configuration,
                expectedSnapshots: confirmationSnapshots
            )
            _ = try await (historicalProof, confirmationProof)
            // Age the reusable proof from the moment its last on-chain validation completed,
            // not from the end of potentially slow gas estimation/simulation in `prepare`.
            let preparedValidationTimestamp = validationNow()
            try requireSweepableConfirmationSnapshotsUnchanged(
                invoices: invoices,
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
            preparedSettlementValidationProof = PreparedSettlementValidationProof(
                configuration: configuration,
                intent: prepared.intent,
                confirmationSnapshots: confirmationSnapshots,
                validatedAt: preparedValidationTimestamp
            )
            errorMessage = nil
        } catch {
            clearPreparedSettlementState()
            errorMessage = error.localizedDescription
        }
    }

    func cancelPreparedSettlement() {
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        clearPreparedSettlementState()
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
            await backgroundRPCWorkGate.waitUntilIdle()
            let reusablePreparedValidationProof = preparedSettlementValidationProof
            let canReusePreparedValidation = reusablePreparedValidationProof?.isReusable(
                configuration: configuration,
                intent: preparedSettlement.intent,
                confirmationSnapshots: expectedSnapshots,
                at: validationNow(),
                maximumAge: preparedSettlementValidationTTL
            ) == true
            var historicalValidationCompletedAt = canReusePreparedValidation
                ? reusablePreparedValidationProof?.validatedAt
                : nil
            // Single-use: any failed, cancelled, or completed signing attempt must perform a full
            // fresh pre-prompt proof on the next attempt. The authenticated callback below always
            // repeats live balances and canonical confirmation cursors regardless of this reuse.
            preparedSettlementValidationProof = nil
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
            if !canReusePreparedValidation {
                let confirmationSamples = try sweepableConfirmationSamples(
                    invoices: invoices,
                    expectedSnapshots: expectedSnapshots
                )
                async let historicalProof = historicalConfigurationValidator
                    .validateHistoricalConfiguration(configuration)
                async let confirmationProof: Void = Self.validateSweepableConfirmationSamples(
                    confirmationSamples,
                    configuration: configuration,
                    expectedSnapshots: expectedSnapshots
                )
                _ = try await (historicalProof, confirmationProof)
                historicalValidationCompletedAt = validationNow()
                try requireSweepableConfirmationSnapshotsUnchanged(
                    invoices: invoices,
                    expectedSnapshots: expectedSnapshots
                )
            }
            guard let finalHistoricalValidationCompletedAt = historicalValidationCompletedAt else {
                throw AppSettlementError.validationProofExpired
            }
            let validationClock = validationNow
            let validationTTL = preparedSettlementValidationTTL
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
                },
                postAuthenticationFinalValidation: {
                    let now = validationClock()
                    guard validationTTL > 0,
                          now >= finalHistoricalValidationCompletedAt,
                          now.timeIntervalSince(finalHistoricalValidationCompletedAt) <= validationTTL
                    else { throw AppSettlementError.validationProofExpired }
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
            clearPreparedSettlementState()
            errorMessage = submission.broadcastError
            await refreshOperatorStatusWithinInteractiveOperation()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Automatic screen appearance is background maintenance, not an interactive cashier action.
    /// Keep it to the periodic one-record budget; explicit pull-to-refresh can still request the
    /// full interactive pass through `reconcileSettlements()`.
    func reconcileSettlementsOnAppearance() async {
        await reconcileSettlements(mode: .periodic)
    }

    func reconcileSettlements(mode: SettlementReconciliationRunMode = .interactive) async {
        let backgroundToken: UUID?
        switch mode {
        case .interactive:
            guard beginExclusiveOperation() else { return }
            backgroundToken = nil
        case .periodic:
            guard let token = backgroundRPCWorkGate.acquire(
                interactiveOperationBusy: operationBusy
            ) else { return }
            backgroundToken = token
        }
        defer {
            if let backgroundToken {
                backgroundRPCWorkGate.release(backgroundToken)
            } else {
                endExclusiveOperation()
            }
        }
        if mode == .interactive {
            await backgroundRPCWorkGate.waitUntilIdle()
            await reconcileSettlementRecords(mode: mode)
        } else {
            await RPCRequestDeadline.withDeadline(after: backgroundRPCUnitDeadline) {
                await reconcileSettlementRecords(mode: mode)
            }
        }
    }

    private func reconcileSettlementRecords(mode: SettlementReconciliationRunMode) async {
        do {
            let records = try container.mainContext.fetch(
                SettlementReconciliationPolicy.activeFetchDescriptor(
                    limit: mode == .periodic ? 1 : SettlementReconciliationPolicy.activeBatchLimit
                )
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
                    let rpc = try OperatorRPCClientPool.shared.client(for: endpoint)
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
                        // retryPersistedBroadcast intentionally converts ambiguous network
                        // errors into an unknown submission. A scheduling deadline is different:
                        // defer this maintenance row without recording a synthetic RPC failure.
                        try RPCRequestDeadline.check()
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
                } catch is RPCRequestDeadlineError {
                    return
                } catch let error as URLError where error.code == .cancelled {
                    return
                } catch let error as URLError where
                    mode == .periodic && error.code == .timedOut {
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
        } catch is RPCRequestDeadlineError {
            return
        } catch let error as URLError where error.code == .cancelled {
            return
        } catch let error as URLError where
            mode == .periodic && error.code == .timedOut {
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
            let rpc = try EthereumRPCClientPool.shared.client(
                for: configuration.rpcEndpoints[0]
            )
            let monitor = PaymentMonitor(
                rpc: rpc,
                confirmationPolicy: configuration.confirmationPolicy
            )
            let observation = try await monitor.sample(
                candidate.request,
                previousThresholdCursor: candidate.previousThresholdCursor,
                additionalCursors: candidate.additionalCursors,
                expectedChainID: configuration.chainID,
                now: now
            )
            try Task.checkCancellation()
            return .success(invoiceID: candidate.invoiceID, observation: observation)
        } catch is CancellationError {
            return .cancelled(invoiceID: candidate.invoiceID)
        } catch is RPCRequestDeadlineError {
            return .cancelled(invoiceID: candidate.invoiceID)
        } catch let error as URLError where error.code == .cancelled {
            return .cancelled(invoiceID: candidate.invoiceID)
        } catch let error as URLError where error.code == .timedOut {
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
        let samples = try sweepableConfirmationSamples(
            invoices: invoices,
            expectedSnapshots: expectedSnapshots
        )
        try await Self.validateSweepableConfirmationSamples(
            samples,
            configuration: configuration,
            expectedSnapshots: expectedSnapshots
        )
        try requireSweepableConfirmationSnapshotsUnchanged(
            invoices: invoices,
            expectedSnapshots: expectedSnapshots
        )
    }

    private func sweepableConfirmationSamples(
        invoices: [StoredInvoice],
        expectedSnapshots: [SweepableConfirmationSnapshot]
    ) throws -> [PaymentSampleInput] {
        guard invoices.count == expectedSnapshots.count else {
            throw AppSettlementError.mixedSnapshots
        }
        var samples = [PaymentSampleInput]()
        samples.reserveCapacity(invoices.count)
        for (invoice, snapshot) in zip(invoices, expectedSnapshots) {
            guard invoice.invoiceID == snapshot.invoiceID,
                  let cursor = snapshot.confirmationCursor
            else { throw AppSettlementError.unconfirmedSweepableBalance }
            samples.append(PaymentSampleInput(
                request: try invoice.paymentRequest(),
                previousThresholdCursor: invoice.paymentThresholdCursor,
                additionalCursors: [cursor]
            ))
        }
        return samples
    }

    private nonisolated static func validateSweepableConfirmationSamples(
        _ samples: [PaymentSampleInput],
        configuration: TerminalConfiguration,
        expectedSnapshots: [SweepableConfirmationSnapshot]
    ) async throws {
        let rpc = try EthereumRPCClientPool.shared.client(
            for: configuration.rpcEndpoints[0]
        )
        let monitor = PaymentMonitor(
            rpc: rpc,
            confirmationPolicy: configuration.confirmationPolicy
        )
        try Task.checkCancellation()
        let observations = try await monitor.sampleBatch(
            samples,
            expectedChainID: configuration.chainID
        )
        guard observations.count == expectedSnapshots.count else {
            throw AppSettlementError.confirmedBalanceChanged
        }
        for (snapshot, observation) in zip(expectedSnapshots, observations) {
            guard snapshot.isRevalidated(by: observation) else {
                throw AppSettlementError.confirmedBalanceChanged
            }
        }
    }

    private func requireSweepableConfirmationSnapshotsUnchanged(
        invoices: [StoredInvoice],
        expectedSnapshots: [SweepableConfirmationSnapshot]
    ) throws {
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
        if let operatorStatusReader {
            return try await operatorStatusReader(configuration, operatorAddress)
        }
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
        settings.rpcOverride(for: chainID)
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
        let rpc = try OperatorRPCClientPool.shared.client(for: endpoint)
        let value = SettlementCoordinator(
            rpc: rpc,
            wallet: operatorWallet,
            operatorAddress: operatorAddress
        )
        settlementCoordinator = value
        settlementCoordinatorKey = key
        return value
    }

    private func validate(
        _ configuration: TerminalConfiguration,
        fingerprint: String,
        allowCachedBackgroundProof: Bool
    ) async throws {
        if allowCachedBackgroundProof,
           configurationValidationProof?.isReusable(
               configuration: configuration,
               fingerprint: fingerprint,
               at: validationNow(),
               maximumAge: configurationValidationTTL
           ) == true {
            validationMessage = "On-chain validation passed"
            return
        }
        do {
            try await currentConfigurationValidation(configuration)
            configurationValidationProof = ConfigurationValidationProof(
                configuration: configuration,
                fingerprint: fingerprint,
                validatedAt: validationNow()
            )
            validationMessage = "On-chain validation passed"
        } catch {
            configurationValidationProof = nil
            validationMessage = "Validation failed"
            throw error
        }
    }

    private func clearPreparedSettlementState() {
        preparedSettlement = nil
        preparedConfiguration = nil
        preparedConfirmationSnapshots = nil
        preparedSettlementValidationProof = nil
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

    func startMonitoring(_ request: PaymentRequest, configuration: TerminalConfiguration) {
        monitoringTask?.cancel()
        monitoringTask = Task { [weak self] in
            guard let self else { return }
            do {
                while !Task.isCancelled {
                    let cursors = try self.confirmationCursors(
                        for: request.invoiceID.hex
                    )
                    let token = try await self.acquireBackgroundRPCWork()
                    let observation: PaymentObservation
                    do {
                        observation = try await RPCRequestDeadline.withDeadline(
                            after: self.backgroundRPCUnitDeadline
                        ) {
                            try await self.paymentObservationSampler(
                                request,
                                configuration,
                                cursors.payment,
                                cursors.sweepable.map { [$0] } ?? []
                            )
                        }
                    } catch {
                        self.backgroundRPCWorkGate.release(token)
                        if PaymentMonitorRetryPolicy.shouldRetry(error) {
                            // Leave activeObservation and the persisted confirmation cursor at
                            // their last verified values. A transient read is not evidence.
                            try await Task.sleep(
                                nanoseconds: self.paymentMonitorPollIntervalNanoseconds
                            )
                            continue
                        }
                        throw error
                    }
                    self.backgroundRPCWorkGate.release(token)
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
                        nanoseconds: self.paymentMonitorPollIntervalNanoseconds
                    )
                }
            } catch is CancellationError {
                return
            } catch let error as URLError where error.code == .cancelled {
                // URLSession reports task cancellation through URLError rather than
                // CancellationError on some paths. This is intentional shutdown, not a sale
                // failure to surface to the cashier.
                return
            } catch {
                self.errorMessage = error.localizedDescription
            }
        }
    }

    /// Internal lifecycle seam used by deterministic app tests and future orderly shutdown paths.
    /// Capturing the task first ensures a later monitor replacement cannot change what is awaited.
    func waitForMonitoringToFinish() async {
        let task = monitoringTask
        await task?.value
    }

    private func acquireBackgroundRPCWork() async throws -> UUID {
        while !Task.isCancelled {
            if let token = backgroundRPCWorkGate.acquire(
                interactiveOperationBusy: operationBusy
            ) {
                return token
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw CancellationError()
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

struct ConfigurationValidationProof: Equatable, Sendable {
    let configuration: TerminalConfiguration
    let fingerprint: String
    let validatedAt: Date

    func isReusable(
        configuration: TerminalConfiguration,
        fingerprint: String,
        at now: Date,
        maximumAge: TimeInterval
    ) -> Bool {
        guard maximumAge > 0, now >= validatedAt else { return false }
        return self.configuration == configuration
            && self.fingerprint == fingerprint
            && now.timeIntervalSince(validatedAt) <= maximumAge
    }
}

struct PreparedSettlementValidationProof: Equatable, Sendable {
    let configuration: TerminalConfiguration
    let intent: SettlementIntent
    let confirmationSnapshots: [SweepableConfirmationSnapshot]
    let validatedAt: Date

    func isReusable(
        configuration: TerminalConfiguration,
        intent: SettlementIntent,
        confirmationSnapshots: [SweepableConfirmationSnapshot],
        at now: Date,
        maximumAge: TimeInterval
    ) -> Bool {
        guard maximumAge > 0, now >= validatedAt else { return false }
        return self.configuration == configuration
            && self.intent == intent
            && self.confirmationSnapshots == confirmationSnapshots
            && now.timeIntervalSince(validatedAt) <= maximumAge
    }
}

enum SettlementReconciliationRunMode: Equatable, Sendable {
    case interactive
    case periodic
}

/// A selected batch may be settled only by the device EOA that derived every invoice identifier.
internal func invoiceOperatorSnapshotsMatch(
    _ storedOperators: [EthereumAddress],
    currentOperator: EthereumAddress
) -> Bool {
    guard let first = storedOperators.first,
          first == currentOperator
    else { return false }
    return storedOperators.allSatisfy { $0 == first }
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
    case validationProofExpired

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
        case .validationProofExpired:
            "The settlement review expired during device authentication. Review the live settlement details and confirm again."
        }
    }
}
