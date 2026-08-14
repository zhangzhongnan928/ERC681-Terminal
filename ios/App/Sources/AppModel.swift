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

typealias RPCEndpointValidation = @Sendable (
    _ chainID: UInt64,
    _ endpoint: URL,
    _ configurations: [TerminalConfiguration]
) async throws -> Void

enum AdminPINConfigurationState: Equatable {
    case configured
    case notConfigured
    case unavailable(String)
}

@MainActor
final class AppModel: ObservableObject {
    private static let legacyRPCEndpointMigrationFailureMessage =
        "A saved RPC endpoint could not be migrated to Keychain. Its old value was kept but will not be used. Restore Keychain access, then configure or remove the dedicated endpoint in Settings."

    @Published var settings: AppSettings {
        didSet {
            // A corrupt persisted catalog is kept byte-for-byte until the device admin explicitly
            // quarantines it. Incidental readiness or UI mutations must never overwrite the only
            // recovery evidence with an empty default configuration.
            if !settingsRecoveryRequired {
                AppPreferences.saveSettings(settings)
            }
            if settings.autoSweepEnabled != oldValue.autoSweepEnabled {
                autoSweepAttemptGate.invalidate()
            }
            guard !settings.hasSamePaymentConfiguration(as: oldValue) else { return }
            validatedConfigurationFingerprint = nil
            configurationValidationProof = nil
            preparedSettlementValidationProof = nil
            validationMessage = "On-chain validation required"
            settlementCoordinator = nil
            settlementCoordinatorKey = nil
            operatorStatus = nil
            clearReadinessPreservation()
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
    /// Non-nil while readiness rests on a preserved (not freshly re-proven) validation proof or
    /// operator status because the RPC provider could not be reached. Rendered by Checkout,
    /// Settings, and Settlement alongside the cached values.
    @Published private(set) var preservedReadinessNotice: String?
    private var validationProofIsPreserved = false
    private var operatorStatusIsPreserved = false
    @Published private(set) var validatedConfigurationFingerprint: String?
    @Published private(set) var provisioningMessage: String?
    @Published private(set) var rpcEndpointMessage: String?
    @Published private(set) var isProvisioning = false
    @Published private(set) var adminPINConfigurationState: AdminPINConfigurationState
    @Published private(set) var adminUnlocked: Bool
    @Published private(set) var preparedSettlement: PreparedSettlement?
    @Published private(set) var autoSweepReviewSequence: UInt64 = 0
    @Published private(set) var autoSweepMessage: String?
    @Published private(set) var settingsRecoveryRequired = false
    @Published private(set) var rpcEndpointStatuses = [UInt64: RPCEndpointConfigurationStatus]()
    @Published var errorMessage: String?

    private let container: ModelContainer
    private let operatorWallet: KeychainOperatorWallet
    private let operatorWalletLifecycle: any OperatorWalletLifecycleManaging
    private let provisioningValidator: any TerminalProvisioningValidating
    private let historicalConfigurationValidator: any HistoricalTerminalConfigurationValidating
    private let adminPINStore: any AdminPINManaging
    private let rpcEndpointStore: any RPCEndpointManaging
    private let rpcEndpointValidation: RPCEndpointValidation
    private let persistMainContext: (ModelContext) throws -> Void
    private let currentConfigurationValidation: @Sendable (
        TerminalConfiguration
    ) async throws -> Void
    private let operatorStatusReader: (@Sendable (
        TerminalConfiguration,
        EthereumAddress
    ) async throws -> OperatorChainStatus)?
    private let receiverFreshnessReader: (@Sendable (
        TerminalConfiguration,
        EthereumAddress,
        EthereumAddress
    ) async throws -> ReceiverFreshnessProof)?
    private let operatorResetBalanceReader: (@Sendable (
        TerminalConfiguration,
        EthereumAddress
    ) async throws -> OperatorNativeBalanceSnapshot)?
    private let adminSessionGate = AdminSessionGate()
    private var monitoringTask: Task<Void, Never>?
    private var settlementCoordinator: SettlementCoordinator?
    private var settlementCoordinatorKey: String?
    private var preparedConfiguration: TerminalConfiguration?
    private var preparedPersistentConfiguration: TerminalConfiguration?
    private var preparedConfirmationSnapshots: [SweepableConfirmationSnapshot]?
    private let lifecycleOperationGate = AppModelOperationGate()
    private let foregroundInvoiceReconciliationGate = ForegroundInvoiceReconciliationGate()
    private let backgroundRPCWorkGate: BackgroundRPCWorkGate
    private let backgroundRPCUnitDeadline: Duration
    private let paymentEvidenceResolutionDeadline: Duration
    private let paymentObservationSampler: PaymentObservationSampling
    private let paymentEvidenceResolver: AppPaymentEvidenceResolving
    private let paymentMonitorPollIntervalNanoseconds: UInt64
    private let validationNow: @Sendable () -> Date
    private let configurationValidationTTL: TimeInterval
    private let preparedSettlementValidationTTL: TimeInterval
    private var configurationValidationProof: ConfigurationValidationProof?
    private var preparedSettlementValidationProof: PreparedSettlementValidationProof?
    private var preparedAutoSweepFingerprint: String?
    private var suppressedAutoSweepFingerprints = Set<String>()
    private var autoSweepRetryAfter = [String: Date]()
    private var autoSweepAttemptGate = AutoSweepAttemptGate()
    private var rpcEndpointMigrationFailures = [UInt64: String]()

    init(
        container: ModelContainer,
        operatorWallet: KeychainOperatorWallet = KeychainOperatorWallet(),
        operatorWalletLifecycle: (any OperatorWalletLifecycleManaging)? = nil,
        provisioningValidator: any TerminalProvisioningValidating = TerminalProvisioner(),
        historicalConfigurationValidator: any HistoricalTerminalConfigurationValidating = TerminalProvisioner(),
        adminPINStore: any AdminPINManaging = KeychainAdminPINStore(),
        rpcEndpointStore: any RPCEndpointManaging = KeychainRPCEndpointStore(),
        rpcEndpointValidation: @escaping RPCEndpointValidation = { chainID, endpoint, configurations in
            let rpc = try EthereumRPCClientPool.shared.client(for: endpoint)
            let actualChainID = try await rpc.chainID()
            guard actualChainID == chainID else {
                throw ConfigurationValidationError.wrongChain(
                    expected: chainID,
                    actual: actualChainID
                )
            }
            let historicalValidator = TerminalProvisioner()
            for configuration in configurations {
                let operational = try configuration.replacingRPCEndpoint(with: endpoint)
                _ = try await historicalValidator.validateHistoricalConfiguration(operational)
            }
        },
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
        receiverFreshnessReader: (@Sendable (
            TerminalConfiguration,
            EthereumAddress,
            EthereumAddress
        ) async throws -> ReceiverFreshnessProof)? = nil,
        operatorResetBalanceReader: (@Sendable (
            TerminalConfiguration,
            EthereumAddress
        ) async throws -> OperatorNativeBalanceSnapshot)? = nil,
        validationNow: @escaping @Sendable () -> Date = Date.init,
        configurationValidationTTL: TimeInterval = 5 * 60,
        preparedSettlementValidationTTL: TimeInterval = 60,
        interactiveBackgroundDrainTimeout: Duration = .seconds(5),
        backgroundRPCUnitDeadline: Duration = .seconds(5),
        paymentEvidenceResolutionDeadline: Duration = .seconds(60),
        paymentObservationSampler: PaymentObservationSampling? = nil,
        paymentEvidenceResolver: AppPaymentEvidenceResolving? = nil,
        paymentMonitorPollIntervalNanoseconds: UInt64 =
            PaymentMonitor.defaultPollIntervalNanoseconds
    ) {
        precondition(interactiveBackgroundDrainTimeout > .zero)
        precondition(backgroundRPCUnitDeadline > .zero)
        precondition(paymentEvidenceResolutionDeadline > .zero)
        precondition(paymentMonitorPollIntervalNanoseconds > 0)
        self.container = container
        self.operatorWallet = operatorWallet
        self.operatorWalletLifecycle = operatorWalletLifecycle ?? operatorWallet
        self.provisioningValidator = provisioningValidator
        self.historicalConfigurationValidator = historicalConfigurationValidator
        self.adminPINStore = adminPINStore
        self.rpcEndpointStore = rpcEndpointStore
        self.rpcEndpointValidation = rpcEndpointValidation
        self.persistMainContext = persistMainContext
        self.currentConfigurationValidation = currentConfigurationValidation
        self.operatorStatusReader = operatorStatusReader
        self.receiverFreshnessReader = receiverFreshnessReader
        self.operatorResetBalanceReader = operatorResetBalanceReader
        self.validationNow = validationNow
        self.configurationValidationTTL = max(0, configurationValidationTTL)
        self.preparedSettlementValidationTTL = max(0, preparedSettlementValidationTTL)
        backgroundRPCWorkGate = BackgroundRPCWorkGate(
            maximumInteractiveDrainWait: interactiveBackgroundDrainTimeout
        )
        self.backgroundRPCUnitDeadline = backgroundRPCUnitDeadline
        self.paymentEvidenceResolutionDeadline = paymentEvidenceResolutionDeadline
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
        self.paymentEvidenceResolver = paymentEvidenceResolver
            ?? { request, configuration in
                try await AppPaymentEvidenceResolver.resolve(
                    request,
                    configuration: configuration
                )
            }
        self.paymentMonitorPollIntervalNanoseconds = paymentMonitorPollIntervalNanoseconds
        let settingsLoadResult = AppPreferences.loadSettingsResult()
        settings = settingsLoadResult.settings
        suppressedAutoSweepFingerprints = Set(
            settingsLoadResult.settings.dismissedAutoSweepFingerprints
        )
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
        } else {
            migrateLegacyRPCEndpointsIfNeeded()
        }
        refreshRPCEndpointStatuses()
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

    func rpcEndpointStatus(for chainID: UInt64) -> RPCEndpointConfigurationStatus {
        rpcEndpointStatuses[chainID] ?? .builtIn
    }

    @discardableResult
    func updateRPCEndpoint(_ rawValue: String, for chainID: UInt64) async -> Bool {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return false
        }
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before changing an RPC endpoint."
            return false
        }
        guard let adminSession = adminSessionGate.capture() else {
            errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
            return false
        }
        guard TerminalKnownChainProfile.profile(for: chainID) != nil else {
            errorMessage = AppSettingsError.unsupportedChain.localizedDescription
            return false
        }
        guard activeRequest == nil, preparedSettlement == nil else {
            errorMessage = "Close the active payment or settlement review before changing an RPC endpoint."
            return false
        }
        let endpoint: URL
        do {
            endpoint = try RPCEndpointURLParser.parse(rawValue)
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return false
        }
        defer { endExclusiveOperation() }

        let settingsSnapshot = settings
        var endpointSaved = false
        do {
            await backgroundRPCWorkGate.waitUntilIdle()
            let configurations = try settingsSnapshot.configurations().filter {
                $0.chainID == chainID
            }
            try await rpcEndpointValidation(chainID, endpoint, configurations)
            guard settings == settingsSnapshot else {
                throw AppSafetyError.configurationChanged
            }
            try adminSessionGate.requireCurrent(adminSession)
            try rpcEndpointStore.save(endpoint, for: chainID)
            endpointSaved = true
            rpcEndpointMigrationFailures.removeValue(forKey: chainID)
            try normalizePersistedRPCEndpoints(for: [chainID])
            rpcEndpointStatuses[chainID] = .configured(
                provider: RPCEndpointURLParser.providerLabel(for: endpoint)
            )
            invalidateRPCDependentState()
            rpcEndpointMessage = "Dedicated RPC endpoint verified and saved in this device's Keychain."
            errorMessage = nil
            return true
        } catch {
            refreshRPCEndpointStatuses()
            rpcEndpointMessage = endpointSaved
                ? "The dedicated endpoint was saved, but legacy transport metadata cleanup did not complete. The app will retry on its next launch."
                : "RPC endpoint was not changed."
            errorMessage = error.localizedDescription
            return false
        }
    }

    @discardableResult
    func removeRPCEndpoint(for chainID: UInt64) async -> Bool {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return false
        }
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before changing an RPC endpoint."
            return false
        }
        guard let adminSession = adminSessionGate.capture() else {
            errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
            return false
        }
        guard TerminalKnownChainProfile.profile(for: chainID) != nil else {
            errorMessage = AppSettingsError.unsupportedChain.localizedDescription
            return false
        }
        guard activeRequest == nil, preparedSettlement == nil else {
            errorMessage = "Close the active payment or settlement review before changing an RPC endpoint."
            return false
        }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return false
        }
        defer { endExclusiveOperation() }

        do {
            await backgroundRPCWorkGate.waitUntilIdle()
            try adminSessionGate.requireCurrent(adminSession)
            // Keep the active Keychain value until every old fallback copy has been scrubbed. If
            // local persistence fails, the endpoint remains configured and removal can be retried.
            try normalizePersistedRPCEndpoints(for: [chainID])
            try adminSessionGate.requireCurrent(adminSession)
            try rpcEndpointStore.removeEndpoint(for: chainID)
            rpcEndpointMigrationFailures.removeValue(forKey: chainID)
            rpcEndpointStatuses[chainID] = .builtIn
            invalidateRPCDependentState()
            rpcEndpointMessage = "Dedicated endpoint removed. This network now uses its built-in public RPC."
            errorMessage = nil
            return true
        } catch {
            refreshRPCEndpointStatuses()
            rpcEndpointMessage = "RPC endpoint was not changed."
            errorMessage = error.localizedDescription
            return false
        }
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
        return "Safety update: confirmation depth was aligned with the strongest stored "
            + "policy or compiled minimum for \(count) payment profile"
            + "\(count == 1 ? "" : "s")."
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

    func updateConfirmationBlocks(_ requiredBlocks: UInt64, for chainID: UInt64) async {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before changing the confirmation policy."
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

        do {
            await backgroundRPCWorkGate.waitUntilIdle()
            let original = settings
            let candidate = try original.updatingConfirmationBlocks(
                for: chainID,
                to: requiredBlocks
            )
            guard settings == original else { throw AppSafetyError.configurationChanged }
            try adminSessionGate.requireCurrent(adminSession)
            settings = candidate
            let networkName = TerminalKnownChainProfile.profile(for: chainID)?.networkName
                ?? "chain \(chainID)"
            provisioningMessage = "\(networkName) now requires \(requiredBlocks) "
                + "confirmation\(requiredBlocks == 1 ? "" : "s") for new payments."
            errorMessage = nil
        } catch {
            endExclusiveOperation()
            errorMessage = error.localizedDescription
            return
        }

        endExclusiveOperation()
        await refreshReadiness()
    }

    func updateMerchantReceiptProfile(name: String, abn: String) {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before changing receipt details."
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
            let candidate = try original.updatingMerchantReceiptProfile(name: name, abn: abn)
            guard settings == original else { throw AppSafetyError.configurationChanged }
            try adminSessionGate.requireCurrent(adminSession)
            settings = candidate
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func updateAutoSweepEnabled(_ enabled: Bool) {
        guard !settingsRecoveryRequired else {
            errorMessage = settingsRecoveryMessage
            return
        }
        guard adminPINConfigured, adminUnlocked else {
            errorMessage = "Unlock Admin before changing auto-sweep."
            return
        }
        guard let adminSession = adminSessionGate.capture() else {
            errorMessage = AppSafetyError.adminSessionExpired.localizedDescription
            return
        }
        let original = settings
        guard original.autoSweepEnabled != enabled else {
            errorMessage = nil
            return
        }

        // Turning automation off is a cancellation signal, so it must remain available while an
        // evidence lookup or automatic preparation owns the normal lifecycle operation gate.
        if !enabled {
            do {
                try adminSessionGate.requireCurrent(adminSession)
                guard settings == original else { throw AppSafetyError.configurationChanged }
                settings = original.updatingAutoSweepEnabled(false)
                if preparedAutoSweepFingerprint != nil, !operationBusy {
                    clearPreparedSettlementState()
                }
                autoSweepMessage = nil
                errorMessage = nil
            } catch {
                errorMessage = error.localizedDescription
            }
            return
        }
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        do {
            try adminSessionGate.requireCurrent(adminSession)
            guard settings == original else { throw AppSafetyError.configurationChanged }
            settings = original.updatingAutoSweepEnabled(true)
            suppressedAutoSweepFingerprints.removeAll()
            autoSweepRetryAfter.removeAll()
            Task { [weak self] in
                await self?.attemptAutoSweepPreparation()
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
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
        clearReadinessPreservation()
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
                rpcEndpointOverride: try rpcEndpointStore.endpoint(for: payload.chainID)
            )
            let persistentConfiguration = try derived.configuration.replacingRPCEndpoint(
                with: knownNetwork.rpcEndpoint
            )
            let candidate = try original.applying(
                persistentConfiguration,
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
        let provenFingerprint = validatedConfigurationFingerprint
        do {
            let configuration = try operationalConfiguration(
                for: snapshot.configuration()
            )
            try await validate(
                configuration,
                fingerprint: snapshot.validationFingerprint,
                allowCachedBackgroundProof: true
            )
            guard settings == snapshot else { throw AppSafetyError.configurationChanged }
            validatedConfigurationFingerprint = snapshot.validationFingerprint
            validationProofIsPreserved = false
            updatePreservedReadinessNotice()
            errorMessage = nil
            return true
        } catch {
            // "The chain could not be asked" is not "the chain said no". A transient
            // re-validation failure for the unchanged readiness identity keeps the previously
            // proven fingerprint (and with it the preserved operator status) instead of
            // demoting checkout to validationRequired.
            if settings == snapshot,
               provenFingerprint != nil,
               provenFingerprint == snapshot.validationFingerprint,
               ReadinessRetryPolicy.isTransient(error) {
                validationProofIsPreserved = true
                updatePreservedReadinessNotice()
                validationMessage = "Last validation retained"
                return true
            }
            validatedConfigurationFingerprint = nil
            validationProofIsPreserved = false
            updatePreservedReadinessNotice()
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
            let persistentConfiguration = try settingsSnapshot.configuration()
            let configuration = try operationalConfiguration(for: persistentConfiguration)
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
            async let freshnessProof = fetchReceiverFreshness(
                configuration: configuration,
                receiver: request.receiver,
                token: token.address
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
            // The sale just proved configuration and operator status freshly; any earlier
            // preserved-readiness notice no longer describes the published state.
            clearReadinessPreservation()
            let readiness = TerminalReadiness.evaluate(
                settings: settingsSnapshot,
                operatorAddress: operatorAddress,
                validatedFingerprint: settingsSnapshot.validationFingerprint,
                operatorStatus: liveStatus
            )
            guard readiness.allowsCheckout else {
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
            let publicationCursor = PaymentConfirmationCursor(
                blockNumber: freshness.blockNumber,
                blockHash: freshness.blockHash
            )
            container.mainContext.insert(
                try StoredInvoice(
                    request: request,
                    configuration: persistentConfiguration,
                    publicationCursor: publicationCursor,
                    receiptProfile: settingsSnapshot.merchantReceiptProfile,
                    receiptNumber: try nextReceiptNumber(),
                    receiptEligible: true
                )
            )
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
                    invoice.closeLocally()
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
                    let persistentConfiguration = try invoice.configurationSnapshot()
                    try validateSnapshot(request, against: persistentConfiguration)
                    let configuration = try operationalConfiguration(
                        for: persistentConfiguration
                    )
                    candidates.append(
                        ForegroundInvoiceReconciliationCandidate(
                            invoiceID: invoice.invoiceID,
                            request: request,
                            configuration: configuration,
                            previousThresholdCursor: invoice.paymentThresholdCursor,
                            additionalCursors: Array(Set([
                                invoice.sweepableConfirmationCursor,
                                invoice.paymentEvidenceFundingCursor,
                            ].compactMap { $0 }))
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
                if let invoiceID = outcome.successfulPaymentInvoiceID {
                    do {
                        try await resolvePaymentEvidenceIfNeeded(invoiceID: invoiceID)
                    } catch {
                        // Payment status remains authoritative. Receipt evidence is retried on the
                        // next foreground pass and when History explicitly requests the receipt.
                        if settings.autoSweepEnabled {
                            autoSweepMessage = "Payment confirmed. Transaction receipt details will retry."
                        }
                    }
                }
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
        await attemptAutoSweepPreparation()
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
                // This scaffold is used only for the chain's native-balance reset check. Model the
                // actual native asset instead of misrepresenting a deployment address as a token.
                let resetOnlyToken = try PaymentToken(
                    address: NativeAsset.address,
                    symbol: trustedProfile.nativeCurrencySymbol,
                    decimals: trustedProfile.nativeCurrencyDecimals
                )
                let configuration = try TerminalConfiguration(
                    chainID: trustedProfile.chainID,
                    rpcEndpoints: [trustedProfile.rpcEndpoint],
                    protocolVersion: operational?.protocolVersion ?? trustedProfile.protocolVersion,
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
            clearReadinessPreservation()
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
            clearReadinessPreservation()
            operatorStatusMessage = "Create the operator wallet to enable native settlement."
            return
        }
        guard settings.isProvisioned else {
            operatorStatus = nil
            clearReadinessPreservation()
            operatorStatusMessage = "Scan the portal provisioning QR to bind a vault."
            return
        }
        let settingsSnapshot = settings
        do {
            let configuration = try operationalConfiguration(
                for: settingsSnapshot.configuration()
            )
            let status = try await fetchOperatorStatus(
                configuration: configuration,
                operatorAddress: operatorAddress
            )
            guard settings == settingsSnapshot,
                  self.operatorAddress == operatorAddress
            else { throw AppSafetyError.configurationChanged }
            operatorStatus = status
            operatorStatusIsPreserved = false
            updatePreservedReadinessNotice()
            operatorStatusMessage = nil
        } catch {
            // "The chain could not be asked" is not "the chain said no". A transient read
            // failure preserves the last proven checkout-capable status for the unchanged
            // configuration; only an explicit verdict or a local failure demotes it.
            if settings == settingsSnapshot,
               self.operatorAddress == operatorAddress,
               ReadinessRetryPolicy.isTransient(error),
               terminalReadiness.allowsCheckout {
                operatorStatusIsPreserved = true
                updatePreservedReadinessNotice()
                operatorStatusMessage = Self.preservedOperatorStatusNotice
                return
            }
            operatorStatus = nil
            operatorStatusIsPreserved = false
            updatePreservedReadinessNotice()
            operatorStatusMessage = error.localizedDescription
        }
    }

    static let preservedOperatorStatusNotice =
        "The latest status re-check could not reach the RPC provider; "
            + "showing the last validated result."

    private func updatePreservedReadinessNotice() {
        preservedReadinessNotice = if validationProofIsPreserved || operatorStatusIsPreserved {
            Self.preservedOperatorStatusNotice
        } else {
            nil
        }
    }

    private func clearReadinessPreservation() {
        validationProofIsPreserved = false
        operatorStatusIsPreserved = false
        updatePreservedReadinessNotice()
    }

    func prepareSettlement(
        for invoices: [StoredInvoice],
        automatic: Bool = false
    ) async {
        guard !invoices.isEmpty else { return }
        guard preparedSettlement == nil else { return }
        guard beginExclusiveOperation() else {
            if automatic {
                autoSweepMessage = "Auto-sweep preparation is waiting for the current operation."
            } else {
                errorMessage = AppSettlementError.operationInProgress.localizedDescription
            }
            return
        }
        defer { endExclusiveOperation() }
        let priorGlobalError = errorMessage
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
            let persistentConfigurations = try invoices.map {
                try $0.configurationSnapshot()
            }
            guard let persistentConfiguration = persistentConfigurations.first,
                  persistentConfigurations.allSatisfy({ $0 == persistentConfiguration }),
                  let firstRequest = requests.first
            else { throw AppSettlementError.mixedSnapshots }
            for (request, snapshot) in zip(requests, persistentConfigurations) {
                try validateSnapshot(request, against: snapshot)
            }
            let configuration = try operationalConfiguration(for: persistentConfiguration)
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
            preparedPersistentConfiguration = persistentConfiguration
            preparedConfirmationSnapshots = confirmationSnapshots
            preparedSettlementValidationProof = PreparedSettlementValidationProof(
                configuration: configuration,
                intent: prepared.intent,
                confirmationSnapshots: confirmationSnapshots,
                validatedAt: preparedValidationTimestamp
            )
            if !automatic { errorMessage = nil }
        } catch {
            clearPreparedSettlementState()
            if automatic {
                errorMessage = priorGlobalError
                autoSweepMessage = "Auto-sweep preparation was deferred: \(error.localizedDescription)"
            } else {
                errorMessage = error.localizedDescription
            }
        }
    }

    func cancelPreparedSettlement() {
        guard beginExclusiveOperation() else {
            errorMessage = AppSettlementError.operationInProgress.localizedDescription
            return
        }
        defer { endExclusiveOperation() }
        if let fingerprint = preparedAutoSweepFingerprint {
            suppressedAutoSweepFingerprints.insert(fingerprint)
            let priorSettings = settings
            let updatedSettings = settings.recordingAutoSweepDismissal(fingerprint)
            settings = updatedSettings
            if priorSettings.autoSweepEnabled, !updatedSettings.autoSweepEnabled {
                autoSweepMessage = "Automatic sweep review was dismissed. Auto-sweep was turned off to avoid losing protected dismissal history. Re-enable it explicitly to start a new automation session."
            } else {
                autoSweepMessage = "Automatic sweep review was dismissed. This payment will remain manual until auto-sweep is toggled again."
            }
        }
        clearPreparedSettlementState()
    }

    /// Foreground automation stops at the existing review sheet. Signing and broadcast remain in
    /// `confirmPreparedSettlement`, which always invokes device-owner authentication.
    func attemptAutoSweepPreparation(now: Date? = nil) async {
        guard let attemptToken = autoSweepAttemptGate.acquire(
            enabled: settings.autoSweepEnabled
        ) else { return }
        defer {
            let shouldRunNewGeneration = autoSweepAttemptGate.release(
                attemptToken,
                enabled: settings.autoSweepEnabled
            )
            if shouldRunNewGeneration {
                Task { [weak self] in
                    await self?.attemptAutoSweepPreparation()
                }
            }
        }
        guard autoSweepAttemptGate.isCurrent(
                  attemptToken,
                  enabled: settings.autoSweepEnabled
              ),
              preparedSettlement == nil,
              !operationBusy,
              operatorAddress != nil
        else { return }
        let currentDate = now ?? validationNow()
        var attemptedCandidate: AutoSweepCandidate?
        do {
            let invoices = try container.mainContext.fetch(FetchDescriptor<StoredInvoice>())
            let activeIDs = try activeSettlementInvoiceIDs()
            let liveFingerprints = Set(invoices.compactMap(\.autoSweepFingerprint))
            autoSweepRetryAfter = autoSweepRetryAfter.filter {
                liveFingerprints.contains($0.key)
            }
            suppressedAutoSweepFingerprints = suppressedAutoSweepFingerprints.intersection(
                liveFingerprints
            )
            guard let candidate = AutoSweepPolicy.selectCandidate(
                from: invoices,
                excludingActiveInvoiceIDs: activeIDs,
                suppressedFingerprints: suppressedAutoSweepFingerprints,
                retryAfter: autoSweepRetryAfter,
                now: currentDate
            ),
            let invoice = invoices.first(where: { $0.invoiceID == candidate.invoiceID })
            else { return }
            attemptedCandidate = candidate

            // A persisted BaseScan hash is never trusted merely because it exists. Re-resolve the
            // durable publication/funding bracket before automatic preparation can proceed.
            guard autoSweepAttemptGate.isCurrent(
                attemptToken,
                enabled: settings.autoSweepEnabled
            ) else { return }
            try await resolvePaymentEvidenceIfNeeded(
                invoiceID: candidate.invoiceID,
                forceRevalidation: true
            )
            guard autoSweepAttemptGate.isCurrent(
                      attemptToken,
                      enabled: settings.autoSweepEnabled
                  ),
                  preparedSettlement == nil,
                  !operationBusy,
                  invoice.autoSweepFingerprint == candidate.fingerprint
            else { return }

            guard autoSweepAttemptGate.isCurrent(
                attemptToken,
                enabled: settings.autoSweepEnabled
            ) else { return }
            await prepareSettlement(for: [invoice], automatic: true)
            guard autoSweepAttemptGate.isCurrent(
                attemptToken,
                enabled: settings.autoSweepEnabled
            ) else {
                if preparedSettlement != nil { clearPreparedSettlementState() }
                autoSweepMessage = nil
                return
            }
            guard preparedSettlement != nil else {
                let failureDate = now ?? validationNow()
                autoSweepRetryAfter[candidate.fingerprint] = failureDate.addingTimeInterval(
                    AutoSweepPolicy.retryDelay
                )
                autoSweepMessage = "Auto-sweep preparation was deferred. It will retry while the app is active."
                return
            }
            let candidateInvoiceID = candidate.invoiceID
            var descriptor = FetchDescriptor<StoredInvoice>(
                predicate: #Predicate { $0.invoiceID == candidateInvoiceID }
            )
            descriptor.fetchLimit = 1
            let activeIDsAfterPreparation = try activeSettlementInvoiceIDs()
            guard try container.mainContext.fetch(descriptor).first?.autoSweepFingerprint
                == candidate.fingerprint,
                !activeIDsAfterPreparation.contains(candidate.invoiceID)
            else {
                clearPreparedSettlementState()
                return
            }
            preparedAutoSweepFingerprint = candidate.fingerprint
            autoSweepRetryAfter.removeValue(forKey: candidate.fingerprint)
            autoSweepMessage = "Auto-sweep is ready for review. Device authentication is still required."
            autoSweepReviewSequence &+= 1
        } catch {
            guard autoSweepAttemptGate.isCurrent(
                attemptToken,
                enabled: settings.autoSweepEnabled
            ) else { return }
            if preparedSettlement != nil { clearPreparedSettlementState() }
            if let attemptedCandidate {
                let failureDate = now ?? validationNow()
                autoSweepRetryAfter[attemptedCandidate.fingerprint] = failureDate
                    .addingTimeInterval(AutoSweepPolicy.retryDelay)
            }
            autoSweepMessage = "Auto-sweep preparation was deferred: \(error.localizedDescription)"
        }
    }

    func confirmPreparedSettlement() async {
        guard let preparedSettlement,
              let configuration = preparedConfiguration,
              let persistentConfiguration = preparedPersistentConfiguration,
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
                rpcURL: persistentConfiguration.rpcEndpoints[0],
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
            autoSweepMessage = nil
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
                          let chainID = UInt64(exactly: record.chainID),
                          let required = UInt64(exactly: record.requiredConfirmations),
                          let nonce = UInt64(exactly: record.nonce)
                    else { throw AppSettlementError.walletMismatch }
                    let endpoint = try operationalEndpoint(for: chainID)
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
                invoice.refreshStatusLabelFromLifecycleEvidence()
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

    private func fetchReceiverFreshness(
        configuration: TerminalConfiguration,
        receiver: EthereumAddress,
        token: EthereumAddress
    ) async throws -> ReceiverFreshnessProof {
        if let receiverFreshnessReader {
            return try await receiverFreshnessReader(configuration, receiver, token)
        }
        let rpc = try EthereumRPCClientPool.shared.client(
            for: configuration.rpcEndpoints[0]
        )
        return try await ReceiverFreshnessValidator(rpc: rpc).validate(
            receiver: receiver,
            token: token,
            expectedChainID: configuration.chainID
        )
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

    private func nextReceiptNumber() throws -> Int64 {
        let invoices = try container.mainContext.fetch(FetchDescriptor<StoredInvoice>())
        let maximum = invoices.map(\.receiptNumber).max() ?? 0
        let next = maximum.addingReportingOverflow(1)
        guard !next.overflow, next.partialValue > 0 else {
            throw AppSettingsError.invalidValue
        }
        return next.partialValue
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

    /// Older releases stored custom per-network transport URLs in UserDefaults and immutable
    /// history snapshots. Move the endpoint that the old release would have selected into
    /// Keychain before deleting any redundant copy. A failed chain remains untouched and is
    /// explicitly unavailable, while runtime transport still fails over only to compiled pins.
    private func migrateLegacyRPCEndpointsIfNeeded() {
        let legacyOverrides = settings.legacyRPCEndpointOverrides()
        var failures = [UInt64: String]()
        for legacy in legacyOverrides {
            do {
                if try rpcEndpointStore.endpoint(for: legacy.chainID) == nil {
                    let endpoint = try RPCEndpointURLParser.parse(legacy.rawValue)
                    try rpcEndpointStore.save(endpoint, for: legacy.chainID)
                }
            } catch {
                failures[legacy.chainID] = Self.legacyRPCEndpointMigrationFailureMessage
            }
        }
        rpcEndpointMigrationFailures = failures

        let knownChains = Set(TerminalKnownChainProfile.all.map(\.chainID))
        let normalizableChains = knownChains.subtracting(failures.keys)
        let requiresNormalization = !legacyOverrides.isEmpty
            || AppPreferences.requiresLegacyRPCHistoryNormalization
        guard requiresNormalization else { return }
        do {
            let didNormalize = try normalizePersistedRPCEndpoints(
                for: normalizableChains
            )
            if failures.isEmpty {
                _ = AppPreferences.recordLegacyRPCHistoryNormalization()
            }
            if didNormalize, !legacyOverrides.isEmpty, failures.isEmpty {
                rpcEndpointMessage = "The legacy dedicated RPC endpoint was moved to this device's Keychain, and old local copies were removed."
            }
        } catch {
            rpcEndpointMessage = error.localizedDescription
            errorMessage = error.localizedDescription
        }

        if !failures.isEmpty {
            rpcEndpointMessage = Self.legacyRPCEndpointMigrationFailureMessage
            errorMessage = Self.legacyRPCEndpointMigrationFailureMessage
        }
    }

    /// Normalizes only chains whose active override is already safe in Keychain, or which have no
    /// legacy override. Settings are persisted before SwiftData history, so any interruption is
    /// retryable and can never remove the sole active endpoint copy before Keychain owns it.
    @discardableResult
    private func normalizePersistedRPCEndpoints(
        for chainIDs: Set<UInt64>
    ) throws -> Bool {
        guard !chainIDs.isEmpty else { return false }
        var didNormalize = false
        let normalizedSettings = settings.normalizingPersistedRPCEndpoints(
            for: chainIDs
        )
        if normalizedSettings != settings {
            guard AppPreferences.saveSettings(normalizedSettings) else {
                throw RPCEndpointMigrationError.settingsPersistenceFailed
            }
            settings = normalizedSettings
            didNormalize = true
        }

        do {
            let invoices = try container.mainContext.fetch(FetchDescriptor<StoredInvoice>())
            let settlements = try container.mainContext.fetch(
                FetchDescriptor<StoredSettlement>()
            )
            var historyChanged = false
            for invoice in invoices {
                guard let chainID = UInt64(exactly: invoice.chainID),
                      chainIDs.contains(chainID),
                      let known = TerminalKnownChainProfile.profile(for: chainID),
                      invoice.rpcURL != known.rpcEndpoint.absoluteString
                else { continue }
                invoice.rpcURL = known.rpcEndpoint.absoluteString
                historyChanged = true
            }
            for settlement in settlements {
                guard let chainID = UInt64(exactly: settlement.chainID),
                      chainIDs.contains(chainID),
                      let known = TerminalKnownChainProfile.profile(for: chainID),
                      settlement.rpcURL != known.rpcEndpoint.absoluteString
                else { continue }
                settlement.rpcURL = known.rpcEndpoint.absoluteString
                historyChanged = true
            }
            if historyChanged {
                try saveMainContextOrRollback()
                didNormalize = true
            }
        } catch {
            throw RPCEndpointMigrationError.historyPersistenceFailed
        }
        return didNormalize
    }

    private func operationalEndpoint(for chainID: UInt64) throws -> URL {
        guard let known = TerminalKnownChainProfile.profile(for: chainID) else {
            throw AppSettingsError.unsupportedChain
        }
        return try rpcEndpointStore.endpoint(for: chainID) ?? known.rpcEndpoint
    }

    private func operationalConfiguration(
        for persistentConfiguration: TerminalConfiguration
    ) throws -> TerminalConfiguration {
        let endpoint = try operationalEndpoint(for: persistentConfiguration.chainID)
        guard endpoint != persistentConfiguration.rpcEndpoints[0] else {
            return persistentConfiguration
        }
        return try persistentConfiguration.replacingRPCEndpoint(with: endpoint)
    }

    private func refreshRPCEndpointStatuses() {
        var refreshed = [UInt64: RPCEndpointConfigurationStatus]()
        for profile in TerminalKnownChainProfile.all {
            if let migrationFailure = rpcEndpointMigrationFailures[profile.chainID] {
                refreshed[profile.chainID] = .unavailable(migrationFailure)
                continue
            }
            do {
                if let endpoint = try rpcEndpointStore.endpoint(for: profile.chainID) {
                    refreshed[profile.chainID] = .configured(
                        provider: RPCEndpointURLParser.providerLabel(for: endpoint)
                    )
                } else {
                    refreshed[profile.chainID] = .builtIn
                }
            } catch {
                refreshed[profile.chainID] = .unavailable(error.localizedDescription)
            }
        }
        rpcEndpointStatuses = refreshed
    }

    private func invalidateRPCDependentState() {
        validatedConfigurationFingerprint = nil
        configurationValidationProof = nil
        preparedSettlementValidationProof = nil
        validationMessage = "On-chain validation required"
        settlementCoordinator = nil
        settlementCoordinatorKey = nil
        operatorStatus = nil
        clearReadinessPreservation()
        operatorStatusMessage = "RPC endpoint changed. Refresh readiness before accepting payments."
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
        preparedPersistentConfiguration = nil
        preparedConfirmationSnapshots = nil
        preparedSettlementValidationProof = nil
        preparedAutoSweepFingerprint = nil
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
                                Array(Set([
                                    cursors.sweepable,
                                    cursors.evidence,
                                ].compactMap { $0 }))
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
                    case .paid, .overpaid:
                        do {
                            try await self.resolvePaymentEvidenceIfNeeded(
                                invoiceID: request.invoiceID.hex
                            )
                        } catch {
                            if self.settings.autoSweepEnabled {
                                self.autoSweepMessage = "Payment confirmed. Transaction receipt details will retry."
                            }
                        }
                        await self.attemptAutoSweepPreparation()
                        return
                    case .expired:
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

    func receiptDocument(for invoiceID: String) throws -> ReceiptDocument? {
        var descriptor = FetchDescriptor<StoredInvoice>(
            predicate: #Predicate { $0.invoiceID == invoiceID }
        )
        descriptor.fetchLimit = 1
        return try container.mainContext.fetch(descriptor).first?.receiptDocument()
    }

    func ensureReceiptDocument(for invoiceID: String) async -> ReceiptDocument? {
        do {
            try await resolvePaymentEvidenceIfNeeded(
                invoiceID: invoiceID,
                forceRevalidation: true
            )
            let document = try receiptDocument(for: invoiceID)
            if document == nil {
                errorMessage = "The payment is confirmed, but its incoming transaction could not be attributed yet. Settlement transaction hashes are never used as receipt evidence."
            }
            return document
        } catch {
            errorMessage = "The payment is confirmed, but receipt evidence is unavailable: \(error.localizedDescription)"
            return nil
        }
    }

    private func resolvePaymentEvidenceIfNeeded(
        invoiceID: String,
        forceRevalidation: Bool = false
    ) async throws {
        var descriptor = FetchDescriptor<StoredInvoice>(
            predicate: #Predicate { $0.invoiceID == invoiceID }
        )
        descriptor.fetchLimit = 1
        guard let invoice = try container.mainContext.fetch(descriptor).first,
              invoice.receiptEligible,
              let publicationCursor = invoice.publicationCursor,
              let fundingCursor = invoice.hasIncomingPaymentEvidence
                ? invoice.paymentEvidenceFundingCursor
                : invoice.paymentThresholdCursor,
              forceRevalidation || !invoice.hasIncomingPaymentEvidence
        else { return }
        let paymentRequest = try invoice.paymentRequest()
        let configuration = try operationalConfiguration(
            for: invoice.configurationSnapshot()
        )
        let evidenceRequest = try PaymentEvidenceRequest(
            chainID: paymentRequest.chainID,
            receiver: paymentRequest.receiver,
            asset: paymentRequest.token.address,
            expectedAmount: paymentRequest.expectedAmount,
            publicationCursor: publicationCursor,
            fundingCursor: fundingCursor
        )

        let token = try await acquireBackgroundRPCWork()
        let evidence: PaymentTransactionEvidence?
        do {
            // Attribution validates both saved anchors, performs a sequential logarithmic balance
            // search, reads the crossing block/logs, then rechecks all anchors. Give that bounded
            // proof its own configurable budget instead of the five-second sampling budget.
            evidence = try await RPCRequestDeadline.withDeadline(
                after: paymentEvidenceResolutionDeadline
            ) {
                try await self.paymentEvidenceResolver(evidenceRequest, configuration)
            }
        } catch {
            backgroundRPCWorkGate.release(token)
            if let resolutionError = error as? PaymentEvidenceResolutionError,
               resolutionError.isDefinitiveStoredEvidenceInvalidation {
                try clearPaymentEvidenceIfMatching(
                    invoiceID: invoiceID,
                    publicationCursor: publicationCursor,
                    fundingCursor: fundingCursor
                )
            }
            throw error
        }
        backgroundRPCWorkGate.release(token)
        guard let evidence else {
            try clearPaymentEvidenceIfMatching(
                invoiceID: invoiceID,
                publicationCursor: publicationCursor,
                fundingCursor: fundingCursor
            )
            return
        }

        // Re-fetch after RPC work. A monitor or foreground reconciliation may have replaced the
        // threshold cursor while evidence was being resolved.
        guard let durable = try container.mainContext.fetch(descriptor).first,
              durable.receiptEligible,
              try durable.applyIncomingPaymentEvidence(
                  evidence,
                  expectedFundingCursor: fundingCursor
              )
        else { return }
        try saveMainContextOrRollback()
        autoSweepMessage = nil
    }

    private func clearPaymentEvidenceIfMatching(
        invoiceID: String,
        publicationCursor: PaymentConfirmationCursor,
        fundingCursor: PaymentConfirmationCursor
    ) throws {
        var descriptor = FetchDescriptor<StoredInvoice>(
            predicate: #Predicate { $0.invoiceID == invoiceID }
        )
        descriptor.fetchLimit = 1
        guard let durable = try container.mainContext.fetch(descriptor).first,
              durable.publicationCursor == publicationCursor,
              durable.clearIncomingPaymentEvidence(expectedFundingCursor: fundingCursor)
        else { return }
        try saveMainContextOrRollback()
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
            sweepable: invoice.sweepableConfirmationCursor,
            evidence: invoice.paymentEvidenceFundingCursor
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
    let evidence: PaymentConfirmationCursor?
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

    var successfulPaymentInvoiceID: String? {
        guard case let .success(invoiceID, observation) = self else { return nil }
        switch observation.status {
        case .paid, .overpaid:
            return invoiceID
        case .waiting, .partial, .confirming, .expired:
            return nil
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
