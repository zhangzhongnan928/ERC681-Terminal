// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

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

    public func protocolVersion(for paymentAsset: EthereumAddress) -> OPKProtocolVersion {
        NativeAsset.isNative(paymentAsset) ? .v1_6 : protocolVersion
    }

    /// EVM networks whose deployment trust anchors are enabled in the production app. Adding a
    /// network is an explicit release action: a provisioning QR can select only an entry in this
    /// registry. The reusable payment-profile catalog remains EVM-generic. Ordering is for stable
    /// presentation only; an application must still choose its default explicitly.
    public static var all: [TerminalKnownChainProfile] { [baseMainnet, baseSepolia] }

    public static var supportedChainIDs: Set<UInt64> { Set(all.map(\.chainID)) }

    public static let baseMainnet: TerminalKnownChainProfile = {
        let factory = try! EthereumAddress(
            hex: "0x5418ab1790eaf96a20e26146c5b7765cb99328da",
            allowZero: false
        )
        let implementation = try! EthereumAddress(
            hex: "0xe6393f6176865cc62cd08d8b8f0c38d35af55254",
            allowZero: false
        )
        let vector = Create2TestVector(
            vault: try! EthereumAddress(
                hex: "0x1111111111111111111111111111111111111111",
                allowZero: false
            ),
            invoiceID: try! Bytes32(
                hex: "0xd5ab0fb2beaa1c3d789ae8a50b9429257b7f830830c8c4e23177a0fb2e116c77"
            ),
            salt: try! Bytes32(
                hex: "0x8b43abe81bab80f024d08540d6ffed9dab76ebd2f0096a53671e7c9aa94462ab"
            ),
            initCodeHash: try! Bytes32(
                hex: "0x3b2db354080b627c0b567ce3b408da0bd1ad3c63d0cbe675ee0bfd1a34817f1a"
            ),
            expectedReceiver: try! EthereumAddress(
                hex: "0x3da3df1635ef2334e5b26bee7b87e34d01454d8b",
                allowZero: false
            )
        )
        return TerminalKnownChainProfile(
            chainID: 8_453,
            networkName: "Base Mainnet",
            isTestnet: false,
            nativeCurrencySymbol: "ETH",
            nativeCurrencyDecimals: 18,
            minimumConfirmationBlocks: 1,
            defaultConfirmationBlocks: 1,
            minimumOperatorNativeReserve: UInt256(100_000_000_000_000),
            rpcEndpoint: URL(string: "https://mainnet.base.org")!,
            protocolVersion: .v1_6,
            factory: factory,
            receiverImplementation: implementation,
            vaultRuntimeCodeHash: try! Bytes32(
                hex: "0x8c3a56b5606e44613d50c898acf67a3689afc478b47e9a38326699b0df111cbd"
            ),
            create2TestVector: vector
        )
    }()

    public static let baseSepolia: TerminalKnownChainProfile = {
        let factory = try! EthereumAddress(
            hex: "0x2592fbab9707e65e21ea14d8a9fe298f5e68a37f",
            allowZero: false
        )
        let implementation = try! EthereumAddress(
            hex: "0xf2e0d5fc47761cac0eedee6cb1af5f31843a0a18",
            allowZero: false
        )
        let vector = Create2TestVector(
            vault: try! EthereumAddress(
                hex: "0x1111111111111111111111111111111111111111",
                allowZero: false
            ),
            invoiceID: try! Bytes32(
                hex: "0xd5ab0fb2beaa1c3d789ae8a50b9429257b7f830830c8c4e23177a0fb2e116c77"
            ),
            salt: try! Bytes32(
                hex: "0x8b43abe81bab80f024d08540d6ffed9dab76ebd2f0096a53671e7c9aa94462ab"
            ),
            initCodeHash: try! Bytes32(
                hex: "0xd237f12377830073f2b667364b744f01cc0f00724e949159e2665134248ca4ad"
            ),
            expectedReceiver: try! EthereumAddress(
                hex: "0xd7bb9c5f5a337b9d9ebcd65e1f840f782985291d",
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
            protocolVersion: .v1_6,
            factory: factory,
            receiverImplementation: implementation,
            vaultRuntimeCodeHash: try! Bytes32(
                hex: "0x32ad6b6076f449fbc39e115afc2645c65071280af2d461dc315544ac0a1d7e58"
            ),
            create2TestVector: vector
        )
    }()

}
