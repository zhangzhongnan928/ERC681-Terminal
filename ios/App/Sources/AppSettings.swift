import Foundation
import OPKTerminalCore

struct AppSettings: Codable, Equatable {
    var rpcURL = "https://sepolia.base.org"
    var chainID = "84532"
    var protocolVersion = OPKProtocolVersion.v1_4_1.rawValue
    var factory = "0x062e3b5d3107e4d1b8dda314e16b9f8ca6eb63d5"
    var receiverImplementation = "0xdaa292b1bf533737c5ce5d27f220273971db3bdc"
    var vault = "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1"
    var tokenAddress = "0x7ffba642bc902880a737cb1c18a4e9540879e211"
    var tokenSymbol = "AUD"
    var tokenDecimals = "18"
    var confirmationBlocks = "2"

    func configuration() throws -> TerminalConfiguration {
        guard let chainID = UInt64(chainID), chainID > 0,
              chainID <= UInt64(Int64.max),
              let endpoint = URL(string: rpcURL),
              let decimals = UInt8(tokenDecimals),
              let blocks = UInt64(confirmationBlocks), blocks > 0,
              blocks <= UInt64(Int64.max)
        else { throw AppSettingsError.invalidValue }
        guard protocolVersion == OPKProtocolVersion.v1_4_1.rawValue else {
            throw AppSettingsError.unsupportedProtocol
        }

        let deployment = try OPKDeployment(
            factory: EthereumAddress(hex: factory, allowZero: false),
            receiverImplementation: EthereumAddress(hex: receiverImplementation, allowZero: false),
            vault: EthereumAddress(hex: vault, allowZero: false)
        )
        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol.trimmingCharacters(in: .whitespacesAndNewlines),
            decimals: decimals
        )
        return try TerminalConfiguration(
            chainID: chainID,
            rpcEndpoints: [endpoint],
            protocolVersion: .v1_4_1,
            deployment: deployment,
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: blocks)
        )
    }
}

struct OperatorActivation: Codable, Equatable {
    let address: String
    let chainID: UInt64
    let vault: String

    init(address: EthereumAddress, configuration: TerminalConfiguration) {
        self.address = address.hex
        chainID = configuration.chainID
        vault = configuration.deployment.vault.hex
    }

    func matches(address: EthereumAddress, configuration: TerminalConfiguration) -> Bool {
        self.address.lowercased() == address.hex.lowercased()
            && chainID == configuration.chainID
            && vault.lowercased() == configuration.deployment.vault.hex.lowercased()
    }
}

enum AppSettingsError: LocalizedError {
    case invalidValue
    case unsupportedProtocol

    var errorDescription: String? {
        switch self {
        case .invalidValue: "One or more settings are invalid."
        case .unsupportedProtocol: "The app is pinned to the deployed Base Sepolia 1.4.1 stack."
        }
    }
}

enum AppPreferences {
    private static let settingsKey = "opk.app.settings.v1"
    private static let terminalKey = "opk.terminal.identifier.v1"
    private static let operatorActivationKey = "opk.operator.activation.v1"

    static func loadSettings() -> AppSettings {
        guard let data = UserDefaults.standard.data(forKey: settingsKey),
              let value = try? JSONDecoder().decode(AppSettings.self, from: data)
        else { return AppSettings() }
        return value
    }

    static func saveSettings(_ settings: AppSettings) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        UserDefaults.standard.set(data, forKey: settingsKey)
    }

    static func terminalIdentifier() -> TerminalIdentifier {
        if let stored = UserDefaults.standard.string(forKey: terminalKey),
           let address = try? EthereumAddress(hex: stored, allowZero: false) {
            return TerminalIdentifier(address: address)
        }
        let identifier = TerminalIdentifier.random()
        UserDefaults.standard.set(identifier.address.hex, forKey: terminalKey)
        return identifier
    }

    static func loadOperatorActivation() -> OperatorActivation? {
        guard let data = UserDefaults.standard.data(forKey: operatorActivationKey),
              let value = try? JSONDecoder().decode(OperatorActivation.self, from: data),
              (try? EthereumAddress(hex: value.address, allowZero: false)) != nil,
              (try? EthereumAddress(hex: value.vault, allowZero: false)) != nil,
              value.chainID > 0
        else { return nil }
        return value
    }

    static func saveOperatorActivation(_ activation: OperatorActivation?) {
        guard let activation else {
            UserDefaults.standard.removeObject(forKey: operatorActivationKey)
            return
        }
        guard let data = try? JSONEncoder().encode(activation) else { return }
        UserDefaults.standard.set(data, forKey: operatorActivationKey)
    }
}
