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
    var provisionedOperatorAddress: String?

    var isProvisioned: Bool { provisionedOperatorAddress != nil }

    func configuration() throws -> TerminalConfiguration {
        guard let chainID = UInt64(chainID), chainID > 0,
              chainID <= UInt64(Int64.max),
              let endpoint = URL(string: rpcURL),
              let decimals = UInt8(tokenDecimals),
              let blocks = UInt64(confirmationBlocks), blocks > 0,
              blocks <= UInt64(Int64.max)
        else { throw AppSettingsError.invalidValue }
        guard let profile = TerminalKnownChainProfile.profile(for: chainID) else {
            throw AppSettingsError.unsupportedChain
        }
        guard protocolVersion == profile.protocolVersion.rawValue else {
            throw AppSettingsError.unsupportedProtocol
        }
        guard let configuredFactory = try? EthereumAddress(hex: factory, allowZero: false),
              configuredFactory == profile.factory,
              let configuredImplementation = try? EthereumAddress(
                  hex: receiverImplementation,
                  allowZero: false
              ),
              configuredImplementation == profile.receiverImplementation
        else { throw AppSettingsError.pinMismatch }
        guard ABI.isSafeTokenSymbol(tokenSymbol) else {
            throw AppSettingsError.invalidValue
        }

        let deployment = try OPKDeployment(
            factory: profile.factory,
            receiverImplementation: profile.receiverImplementation,
            vault: EthereumAddress(hex: vault, allowZero: false)
        )
        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol,
            decimals: decimals
        )
        return try TerminalConfiguration(
            chainID: chainID,
            rpcEndpoints: [endpoint],
            protocolVersion: profile.protocolVersion,
            deployment: deployment,
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: blocks),
            create2TestVector: profile.create2TestVector
        )
    }

    func applying(
        _ configuration: TerminalConfiguration,
        boundTo operatorAddress: EthereumAddress
    ) throws -> AppSettings {
        guard let profile = TerminalKnownChainProfile.profile(for: configuration.chainID),
              configuration.protocolVersion == profile.protocolVersion,
              configuration.deployment.factory == profile.factory,
              configuration.deployment.receiverImplementation == profile.receiverImplementation,
              configuration.create2TestVector == profile.create2TestVector,
              configuration.tokens.count == 1,
              let endpoint = configuration.rpcEndpoints.first,
              let token = configuration.tokens.first,
              ABI.isSafeTokenSymbol(token.symbol),
              configuration.chainID <= UInt64(Int64.max),
              configuration.confirmationPolicy.requiredBlocks
                  == UInt64(confirmationBlocks)
        else { throw AppSettingsError.invalidValue }

        var candidate = self
        candidate.rpcURL = endpoint.absoluteString
        candidate.chainID = String(configuration.chainID)
        candidate.protocolVersion = profile.protocolVersion.rawValue
        candidate.factory = profile.factory.hex
        candidate.receiverImplementation = profile.receiverImplementation.hex
        candidate.vault = configuration.deployment.vault.hex
        candidate.tokenAddress = token.address.hex
        candidate.tokenSymbol = token.symbol
        candidate.tokenDecimals = String(token.decimals)
        candidate.provisionedOperatorAddress = operatorAddress.hex
        return candidate
    }

    func clearingProvisioning() -> AppSettings {
        var candidate = self
        candidate.provisionedOperatorAddress = nil
        return candidate
    }

    func operatorFundingPayload(for operatorAddress: EthereumAddress) -> String? {
        guard isProvisioned,
              let boundValue = provisionedOperatorAddress,
              let boundAddress = try? EthereumAddress(hex: boundValue, allowZero: false),
              boundAddress == operatorAddress,
              let chainID = UInt64(chainID),
              chainID > 0,
              chainID <= UInt64(Int64.max)
        else { return nil }
        return "ethereum:\(operatorAddress.hex)@\(chainID)"
    }

    var validationFingerprint: String {
        [
            rpcURL,
            chainID,
            protocolVersion,
            factory.lowercased(),
            receiverImplementation.lowercased(),
            vault.lowercased(),
            tokenAddress.lowercased(),
            tokenSymbol,
            tokenDecimals,
            confirmationBlocks,
            provisionedOperatorAddress?.lowercased() ?? "unprovisioned",
        ].joined(separator: "|")
    }
}

enum AppSettingsError: LocalizedError {
    case invalidValue
    case unsupportedChain
    case unsupportedProtocol
    case pinMismatch

    var errorDescription: String? {
        switch self {
        case .invalidValue: "One or more settings are invalid."
        case .unsupportedChain: "This chain does not have a trusted terminal deployment profile."
        case .unsupportedProtocol: "The app is pinned to the deployed Base Sepolia 1.4.1 stack."
        case .pinMismatch: "The saved deployment pins do not match the trusted Base Sepolia profile."
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

    @discardableResult
    static func saveSettings(_ settings: AppSettings) -> Bool {
        guard let data = try? JSONEncoder().encode(settings) else { return false }
        UserDefaults.standard.set(data, forKey: settingsKey)
        return true
    }

#if DEBUG
    static func resetForUITesting() {
        UserDefaults.standard.removeObject(forKey: settingsKey)
    }
#endif
}
