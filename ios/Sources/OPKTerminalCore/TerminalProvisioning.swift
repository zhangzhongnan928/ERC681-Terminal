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
    public let rpcEndpoint: URL
    public let protocolVersion: OPKProtocolVersion
    public let factory: EthereumAddress
    public let receiverImplementation: EthereumAddress
    public let vaultRuntimeCodeHash: Bytes32
    public let create2TestVector: Create2TestVector

    private init(
        chainID: UInt64,
        networkName: String,
        rpcEndpoint: URL,
        protocolVersion: OPKProtocolVersion,
        factory: EthereumAddress,
        receiverImplementation: EthereumAddress,
        vaultRuntimeCodeHash: Bytes32,
        create2TestVector: Create2TestVector
    ) {
        self.chainID = chainID
        self.networkName = networkName
        self.rpcEndpoint = rpcEndpoint
        self.protocolVersion = protocolVersion
        self.factory = factory
        self.receiverImplementation = receiverImplementation
        self.vaultRuntimeCodeHash = vaultRuntimeCodeHash
        self.create2TestVector = create2TestVector
    }

    public static func profile(for chainID: UInt64) -> TerminalKnownChainProfile? {
        chainID == baseSepolia.chainID ? baseSepolia : nil
    }

    public static let baseSepolia: TerminalKnownChainProfile = {
        let factory = try! EthereumAddress(
            hex: "0x062e3b5d3107e4d1b8dDA314E16b9F8cA6EB63D5",
            allowZero: false
        )
        let implementation = try! EthereumAddress(
            hex: "0xDAa292B1bf533737C5cE5d27F220273971Db3Bdc",
            allowZero: false
        )
        let vector = Create2TestVector(
            vault: try! EthereumAddress(
                hex: "0x1ed67e540e6ab92dc3537a7bba3bcab6fdd69da1",
                allowZero: false
            ),
            invoiceID: try! Bytes32(
                hex: "0x294d3b9eb0136d18f2a3e8aa9c10224029893c244e822a15902256d778f7f729"
            ),
            salt: try! Bytes32(
                hex: "0x87810f9819659ca2d4dd62a4e7b43c87f611148a2ea26782b9b8da39a63353ce"
            ),
            initCodeHash: try! Bytes32(
                hex: "0x59a3a359c30137feff57a746e7430ee4aef036fe41906d52b4f60a78948a2051"
            ),
            expectedReceiver: try! EthereumAddress(
                hex: "0x9107decd2cb06c57c40a663648e19cde1d52f606",
                allowZero: false
            )
        )
        return TerminalKnownChainProfile(
            chainID: 84_532,
            networkName: "Base Sepolia",
            rpcEndpoint: URL(string: "https://sepolia.base.org")!,
            protocolVersion: .v1_4_1,
            factory: factory,
            receiverImplementation: implementation,
            vaultRuntimeCodeHash: try! Bytes32(
                hex: "0xe7310159a3c109346b137a989bfd213e65fe48ded6eb84dbe57a37d7a047513e"
            ),
            create2TestVector: vector
        )
    }()
}
