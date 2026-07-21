import XCTest
@testable import OPKTerminalApp
import OPKTerminalCore
@testable import OPKTerminalOperator
import SwiftData

final class SettingsRecoveryAndSettlementGuardTests: XCTestCase {
    @MainActor
    func testUnreadableCatalogIsNotOverwrittenUntilExplicitAdminRecovery() throws {
        let defaults = UserDefaults.standard
        let settingsKey = "opk.app.settings.v1"
        let quarantineKey = "opk.app.settings.quarantine.v1"
        let savedSettings = defaults.object(forKey: settingsKey)
        let savedQuarantine = defaults.object(forKey: quarantineKey)
        defer {
            restore(savedSettings, key: settingsKey, defaults: defaults)
            restore(savedQuarantine, key: quarantineKey, defaults: defaults)
        }

        let operatorAddress = try address("1")
        let unreadable = try catalogWithOneInvalidProfileData(
            operatorAddress: operatorAddress
        )
        defaults.set(unreadable, forKey: settingsKey)
        defaults.removeObject(forKey: quarantineKey)

        let container = try testContainer()
        let historical = try storedInvoice(operatorAddress: operatorAddress, nonceDigit: "2")
        container.mainContext.insert(historical)
        try container.mainContext.save()
        let model = AppModel(
            container: container,
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.recovery-tests.\(UUID())"
            ),
            operatorWalletLifecycle: RecoveryTestWalletLifecycle(address: operatorAddress),
            adminPINStore: RecoveryTestAdminPINStore(pin: "123456")
        )

        XCTAssertTrue(model.settingsRecoveryRequired)
        XCTAssertFalse(model.settings.isProvisioned)
        XCTAssertEqual(defaults.data(forKey: settingsKey), unreadable)

        // Even an incidental published-settings mutation cannot destroy the original blob.
        model.settings = AppSettings()
        XCTAssertEqual(defaults.data(forKey: settingsKey), unreadable)

        model.resetUnreadableSettingsForRecovery()
        XCTAssertTrue(model.settingsRecoveryRequired)
        XCTAssertEqual(defaults.data(forKey: settingsKey), unreadable)
        XCTAssertTrue(model.errorMessage?.contains("Unlock Admin") == true)

        model.unlockAdmin(with: "123456")
        model.resetUnreadableSettingsForRecovery()

        XCTAssertFalse(model.settingsRecoveryRequired)
        XCTAssertFalse(model.settings.isProvisioned)
        XCTAssertEqual(model.operatorAddress, operatorAddress)
        XCTAssertEqual(
            try container.mainContext.fetch(FetchDescriptor<StoredInvoice>()).map(\.invoiceID),
            [historical.invoiceID]
        )
        XCTAssertNoThrow(
            try JSONDecoder().decode(
                AppSettings.self,
                from: XCTUnwrap(defaults.data(forKey: settingsKey))
            )
        )
        XCTAssertTrue(
            (defaults.array(forKey: quarantineKey) as? [Data])?.contains(unreadable) == true
        )
    }

    @MainActor
    func testUnavailableAdminPINStoreCannotQuarantineUnreadableCatalog() throws {
        let defaults = UserDefaults.standard
        let settingsKey = "opk.app.settings.v1"
        let quarantineKey = "opk.app.settings.quarantine.v1"
        let savedSettings = defaults.object(forKey: settingsKey)
        let savedQuarantine = defaults.object(forKey: quarantineKey)
        defer {
            restore(savedSettings, key: settingsKey, defaults: defaults)
            restore(savedQuarantine, key: quarantineKey, defaults: defaults)
        }

        let operatorAddress = try address("1")
        let unreadable = try catalogWithOneInvalidProfileData(
            operatorAddress: operatorAddress
        )
        defaults.set(unreadable, forKey: settingsKey)
        defaults.removeObject(forKey: quarantineKey)
        let model = AppModel(
            container: try testContainer(),
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.recovery-tests.\(UUID())"
            ),
            operatorWalletLifecycle: RecoveryTestWalletLifecycle(address: operatorAddress),
            adminPINStore: UnavailableRecoveryTestAdminPINStore()
        )

        XCTAssertTrue(model.settingsRecoveryRequired)
        XCTAssertFalse(model.canAccessAdmin)
        guard case .unavailable = model.adminPINConfigurationState else {
            return XCTFail("Expected unavailable admin PIN configuration state")
        }

        model.resetUnreadableSettingsForRecovery()

        XCTAssertTrue(model.settingsRecoveryRequired)
        XCTAssertEqual(defaults.data(forKey: settingsKey), unreadable)
        XCTAssertFalse(
            (defaults.array(forKey: quarantineKey) as? [Data])?.contains(unreadable) == true
        )
        XCTAssertTrue(model.errorMessage?.contains("unavailable") == true)
    }

    @MainActor
    func testPrepareSettlementRejectsMixedSnapshotsThroughAppModelGuard() async throws {
        let saved = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(saved) }
        AppPreferences.saveSettings(AppSettings())

        let operatorAddress = try address("1")
        let first = try storedInvoice(operatorAddress: operatorAddress, nonceDigit: "3")
        let second = try storedInvoice(operatorAddress: operatorAddress, nonceDigit: "4")
        second.confirmationBlocks += 1
        let model = try appModel(operatorAddress: operatorAddress)

        await model.prepareSettlement(for: [first, second])

        XCTAssertEqual(
            model.errorMessage,
            "The selected invoices do not share the same saved chain, vault, token, and RPC configuration."
        )
        XCTAssertNil(model.preparedSettlement)
        XCTAssertFalse(model.operationBusy)
    }

    @MainActor
    func testPrepareSettlementRejectsInvoiceFromDifferentDeviceWallet() async throws {
        let saved = AppPreferences.loadSettings()
        defer { AppPreferences.saveSettings(saved) }
        AppPreferences.saveSettings(AppSettings())

        let currentOperator = try address("1")
        let otherOperator = try address("2")
        let invoice = try storedInvoice(operatorAddress: otherOperator, nonceDigit: "5")
        let model = try appModel(operatorAddress: currentOperator)

        await model.prepareSettlement(for: [invoice])

        XCTAssertEqual(
            model.errorMessage,
            "The saved transaction belongs to a different device operator wallet and cannot be reconciled here."
        )
        XCTAssertNil(model.preparedSettlement)
        XCTAssertFalse(model.operationBusy)
    }

    @MainActor
    private func appModel(operatorAddress: EthereumAddress) throws -> AppModel {
        AppModel(
            container: try testContainer(),
            operatorWallet: KeychainOperatorWallet(
                service: "com.openpasskey.terminal.operator-wallet.settlement-guard-tests.\(UUID())"
            ),
            operatorWalletLifecycle: RecoveryTestWalletLifecycle(address: operatorAddress),
            adminPINStore: RecoveryTestAdminPINStore(pin: "123456")
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

    private func storedInvoice(
        operatorAddress: EthereumAddress,
        nonceDigit: String
    ) throws -> StoredInvoice {
        let known = TerminalKnownChainProfile.baseSepolia
        let token = try PaymentToken(
            address: try EthereumAddress(
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
            confirmationPolicy: .init(requiredBlocks: known.defaultConfirmationBlocks),
            create2TestVector: known.create2TestVector
        )
        let request = try InvoiceFactory.create(
            terminalIdentifier: TerminalIdentifier(address: operatorAddress),
            amount: UInt256(1_000),
            token: token,
            configuration: configuration,
            nonce: try Bytes32(hex: "0x" + String(repeating: nonceDigit, count: 64))
        )
        return try StoredInvoice(request: request, configuration: configuration)
    }

    private func catalogWithOneInvalidProfileData(
        operatorAddress: EthereumAddress
    ) throws -> Data {
        let first = try AppSettings().applying(
            paymentConfiguration(vaultDigit: "3", tokenDigit: "4", symbol: "AUDM"),
            boundTo: operatorAddress
        )
        let catalog = try first.applying(
            paymentConfiguration(vaultDigit: "5", tokenDigit: "6", symbol: "USDC"),
            boundTo: operatorAddress
        )
        var json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(catalog))
                as? [String: Any]
        )
        var profiles = try XCTUnwrap(json["paymentProfiles"] as? [[String: Any]])
        profiles[1]["factory"] = "0x" + String(repeating: "7", count: 40)
        json["paymentProfiles"] = profiles
        return try JSONSerialization.data(withJSONObject: json)
    }

    private func paymentConfiguration(
        vaultDigit: String,
        tokenDigit: String,
        symbol: String
    ) throws -> TerminalConfiguration {
        let known = TerminalKnownChainProfile.baseSepolia
        let token = try PaymentToken(
            address: address(tokenDigit),
            symbol: symbol,
            decimals: 6
        )
        return try TerminalConfiguration(
            chainID: known.chainID,
            rpcEndpoints: [known.rpcEndpoint],
            protocolVersion: known.protocolVersion,
            deployment: OPKDeployment(
                factory: known.factory,
                receiverImplementation: known.receiverImplementation,
                vault: address(vaultDigit)
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: known.defaultConfirmationBlocks),
            create2TestVector: known.create2TestVector
        )
    }

    private func address(_ digit: String) throws -> EthereumAddress {
        try EthereumAddress(
            hex: "0x" + String(repeating: digit, count: 40),
            allowZero: false
        )
    }

    private func restore(_ value: Any?, key: String, defaults: UserDefaults) {
        if let value {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

private final class RecoveryTestAdminPINStore: AdminPINManaging, @unchecked Sendable {
    private let pin: String

    init(pin: String) { self.pin = pin }

    var isConfigured: Bool { true }

    func setPIN(_ pin: String) throws { throw AdminPINError.alreadyConfigured }

    func verify(_ pin: String) throws {
        guard pin == self.pin else {
            throw AdminPINError.invalidPIN(retryAfterSeconds: nil)
        }
    }

    func secondsUntilNextAttempt() throws -> Int { 0 }
}

private struct UnavailableRecoveryTestAdminPINStore: AdminPINManaging {
    private struct UnavailableError: LocalizedError {
        var errorDescription: String? { "Admin PIN storage is unavailable." }
    }

    var isConfigured: Bool { get throws { throw UnavailableError() } }

    func setPIN(_ pin: String) throws { throw UnavailableError() }

    func verify(_ pin: String) throws { throw UnavailableError() }

    func secondsUntilNextAttempt() throws -> Int { throw UnavailableError() }
}

private actor RecoveryTestWalletLifecycle: OperatorWalletLifecycleManaging {
    nonisolated let address: EthereumAddress

    init(address: EthereumAddress) { self.address = address }

    nonisolated func existingAddress() throws -> EthereumAddress? { address }

    func create(
        reason: String,
        persistenceAuthorization: @Sendable (
            _ persistence: () throws -> EthereumAddress
        ) throws -> EthereumAddress
    ) async throws -> EthereumAddress {
        try persistenceAuthorization { address }
    }

    func reset(
        reason: String,
        beforeDeletion: @Sendable () async throws -> Void,
        deletionAuthorization: @Sendable (
            _ deletion: () throws -> Void
        ) throws -> Void
    ) async throws {
        throw OperatorWalletError.walletAlreadyExists
    }
}
