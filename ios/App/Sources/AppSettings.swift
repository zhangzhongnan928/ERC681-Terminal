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

}
