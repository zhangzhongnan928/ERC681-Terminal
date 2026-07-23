import Foundation

public enum TerminalProvisioningPayloadError: Error, Equatable, Sendable {
    case malformed
    case invalidChainID
    case invalidAddress(field: String)
}

extension TerminalProvisioningPayloadError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .malformed:
            "The setup QR is not a canonical OPK terminal provisioning payload."
        case .invalidChainID:
            "The setup QR contains an unsupported chain ID."
        case let .invalidAddress(field):
            "The setup QR contains an invalid non-zero \(field) address."
        }
    }
}

/// Canonical owner-to-terminal provisioning data. Parsing is deliberately performed on the raw
/// ASCII payload: no URL parser, trimming, case folding, or percent decoding is involved.
public struct TerminalProvisioningPayload: Hashable, Sendable {
    public let chainID: UInt64
    public let vault: EthereumAddress
    public let token: EthereumAddress
    public let operatorAddress: EthereumAddress

    public init(
        chainID: UInt64,
        vault: EthereumAddress,
        token: EthereumAddress,
        operatorAddress: EthereumAddress
    ) throws {
        guard chainID > 0, chainID <= UInt64(Int64.max) else {
            throw TerminalProvisioningPayloadError.invalidChainID
        }
        guard !vault.isZero else {
            throw TerminalProvisioningPayloadError.invalidAddress(field: "vault")
        }
        guard !token.isZero else {
            throw TerminalProvisioningPayloadError.invalidAddress(field: "token")
        }
        guard !operatorAddress.isZero else {
            throw TerminalProvisioningPayloadError.invalidAddress(field: "operator")
        }
        self.chainID = chainID
        self.vault = vault
        self.token = token
        self.operatorAddress = operatorAddress
    }

    public static func parse(_ rawPayload: String) throws -> TerminalProvisioningPayload {
        guard !rawPayload.isEmpty, rawPayload.utf8.allSatisfy({ $0 <= 0x7f }) else {
            throw TerminalProvisioningPayloadError.malformed
        }
        let fields = rawPayload.split(separator: "&", omittingEmptySubsequences: false)
        guard fields.count == 5,
              fields[0] == "opk-terminal:provision?v=1",
              fields[1].hasPrefix("chainId="),
              fields[2].hasPrefix("vault="),
              fields[3].hasPrefix("token="),
              fields[4].hasPrefix("operator=")
        else {
            throw TerminalProvisioningPayloadError.malformed
        }

        let chainText = String(fields[1].dropFirst("chainId=".count))
        guard !chainText.isEmpty,
              chainText.first != "0",
              chainText.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }),
              let chainID = UInt64(chainText),
              chainID <= UInt64(Int64.max)
        else {
            throw TerminalProvisioningPayloadError.invalidChainID
        }

        return try TerminalProvisioningPayload(
            chainID: chainID,
            vault: parseAddress(String(fields[2].dropFirst("vault=".count)), field: "vault"),
            token: parseAddress(String(fields[3].dropFirst("token=".count)), field: "token"),
            operatorAddress: parseAddress(
                String(fields[4].dropFirst("operator=".count)),
                field: "operator"
            )
        )
    }

    public var canonicalString: String {
        "opk-terminal:provision?v=1&chainId=\(chainID)&vault=\(vault.hex)&token=\(token.hex)&operator=\(operatorAddress.hex)"
    }

    fileprivate static func parseAddress(_ value: String, field: String) throws -> EthereumAddress {
        guard value.count == 42,
              value.hasPrefix("0x"),
              value.dropFirst(2).utf8.allSatisfy({ byte in
                  (byte >= 0x30 && byte <= 0x39)
                      || (byte >= 0x41 && byte <= 0x46)
                      || (byte >= 0x61 && byte <= 0x66)
              })
        else {
            throw TerminalProvisioningPayloadError.invalidAddress(field: field)
        }
        do {
            return try EthereumAddress(hex: value, allowZero: false)
        } catch {
            throw TerminalProvisioningPayloadError.invalidAddress(field: field)
        }
    }
}

public enum TerminalOperatorPairingPayload {
    public static func encode(address: EthereumAddress) throws -> String {
        guard !address.isZero else {
            throw TerminalProvisioningPayloadError.invalidAddress(field: "operator")
        }
        return "opk-terminal:operator?v=1&address=\(address.hex)"
    }

    public static func parse(_ rawPayload: String) throws -> EthereumAddress {
        guard !rawPayload.isEmpty, rawPayload.utf8.allSatisfy({ $0 <= 0x7f }) else {
            throw TerminalProvisioningPayloadError.malformed
        }
        let fields = rawPayload.split(separator: "&", omittingEmptySubsequences: false)
        guard fields.count == 2,
              fields[0] == "opk-terminal:operator?v=1",
              fields[1].hasPrefix("address=")
        else {
            throw TerminalProvisioningPayloadError.malformed
        }
        return try TerminalProvisioningPayload.parseAddress(
            String(fields[1].dropFirst("address=".count)),
            field: "operator"
        )
    }
}

/// Immutable deployment trust anchors compiled into the terminal. Persisted settings are never
/// consulted for these values.
public struct TerminalKnownChainProfile: Hashable, Sendable {
    public let chainID: UInt64
    public let networkName: String
    public let isTestnet: Bool
    public let nativeCurrencySymbol: String
    public let nativeCurrencyDecimals: UInt8
    public let minimumConfirmationBlocks: UInt64
    public let defaultConfirmationBlocks: UInt64
    public let minimumOperatorNativeReserve: UInt256
    public let rpcEndpoint: URL
    public let protocolVersion: OPKProtocolVersion
    public let factory: EthereumAddress
    public let receiverImplementation: EthereumAddress
    public let vaultRuntimeCodeHash: Bytes32
    public let create2TestVector: Create2TestVector

    private init(
        chainID: UInt64,
        networkName: String,
        isTestnet: Bool,
        nativeCurrencySymbol: String,
        nativeCurrencyDecimals: UInt8,
        minimumConfirmationBlocks: UInt64,
        defaultConfirmationBlocks: UInt64,
        minimumOperatorNativeReserve: UInt256,
        rpcEndpoint: URL,
        protocolVersion: OPKProtocolVersion,
        factory: EthereumAddress,
        receiverImplementation: EthereumAddress,
        vaultRuntimeCodeHash: Bytes32,
        create2TestVector: Create2TestVector
    ) {
        self.chainID = chainID
        self.networkName = networkName
        self.isTestnet = isTestnet
        self.nativeCurrencySymbol = nativeCurrencySymbol
        self.nativeCurrencyDecimals = nativeCurrencyDecimals
        self.minimumConfirmationBlocks = minimumConfirmationBlocks
        self.defaultConfirmationBlocks = defaultConfirmationBlocks
        self.minimumOperatorNativeReserve = minimumOperatorNativeReserve
        self.rpcEndpoint = rpcEndpoint
        self.protocolVersion = protocolVersion
        self.factory = factory
        self.receiverImplementation = receiverImplementation
        self.vaultRuntimeCodeHash = vaultRuntimeCodeHash
        self.create2TestVector = create2TestVector
    }

    public static func profile(for chainID: UInt64) -> TerminalKnownChainProfile? {
        all.first { $0.chainID == chainID }
    }

    /// EVM networks whose deployment trust anchors are enabled in the production app. Adding a
    /// network is an explicit release action: a provisioning QR can select only an entry in this
    /// registry. The reusable payment-profile catalog remains EVM-generic.
    public static var all: [TerminalKnownChainProfile] { [baseSepolia] }

    public static var supportedChainIDs: Set<UInt64> { Set(all.map(\.chainID)) }

    public static let baseSepolia: TerminalKnownChainProfile = {
        let factory = try! EthereumAddress(
            hex: "0xb69f725999266c6757284ca4169275c3ebde491a",
            allowZero: false
        )
        let implementation = try! EthereumAddress(
            hex: "0x8ba9739741ecc79b5d69fe5580d2966092e6f77f",
            allowZero: false
        )
        let vector = Create2TestVector(
            vault: try! EthereumAddress(
                hex: "0x1111111111111111111111111111111111111111",
                allowZero: false
            ),
            invoiceID: try! Bytes32(
                hex: "0x474614682f1d5e8e24396c2394a98425d4e8617fe699872c96182b89368e50d4"
            ),
            salt: try! Bytes32(
                hex: "0x6ebed91ff26055c5762437f3fe8f834dde34b0dae39fd3df75dcfc1d1e064e1d"
            ),
            initCodeHash: try! Bytes32(
                hex: "0xad563722da414e51edc3d8195e2f225d872f79ea5b511cb2c3a62d6fa1a66b02"
            ),
            expectedReceiver: try! EthereumAddress(
                hex: "0x8128e3A86962519877186c5F4F0920Ba7240f5B1",
                allowZero: false
            )
        )
        return TerminalKnownChainProfile(
            chainID: 84_532,
            networkName: "Base Sepolia",
            isTestnet: true,
            nativeCurrencySymbol: "ETH",
            nativeCurrencyDecimals: 18,
            minimumConfirmationBlocks: 1,
            defaultConfirmationBlocks: 1,
            minimumOperatorNativeReserve: UInt256(100_000_000_000_000),
            rpcEndpoint: URL(string: "https://sepolia.base.org")!,
            protocolVersion: .v1_5,
            factory: factory,
            receiverImplementation: implementation,
            vaultRuntimeCodeHash: try! Bytes32(
                hex: "0x2ceea713f7225b17e43487b8652d8582dadd5aabefc5b9f78d231777958655b9"
            ),
            create2TestVector: vector
        )
    }()

}
