import Foundation
import OPKTerminalCore
import SwiftData

@Model
final class StoredInvoice {
    @Attribute(.unique) var invoiceID: String
    var terminalIdentifier: String
    var rpcURL: String = ""
    var chainID: Int64
    var protocolVersion: String = OPKProtocolVersion.v1_4_1.rawValue
    var factory: String = ""
    var receiverImplementation: String = ""
    var vault: String
    var receiver: String
    var tokenAddress: String
    var tokenSymbol: String
    var tokenDecimals: Int
    var expectedAmount: String
    var erc681URI: String
    var createdAt: Date
    var expiresAt: Date?
    var statusLabel: String
    var observedBalance: String
    var observedBlock: Int64?
    var thresholdBlock: Int64?
    var confirmationBlocks: Int64 = 1
    var locallyClosed: Bool = false

    init(request: PaymentRequest, configuration: TerminalConfiguration) throws {
        guard let storedChainID = Int64(exactly: request.chainID),
              let storedConfirmationBlocks = Int64(exactly: configuration.confirmationPolicy.requiredBlocks)
        else { throw AppSettingsError.invalidValue }
        invoiceID = request.invoiceID.hex
        terminalIdentifier = request.terminalIdentifier.address.hex
        rpcURL = configuration.rpcEndpoints[0].absoluteString
        chainID = storedChainID
        protocolVersion = configuration.protocolVersion.rawValue
        factory = configuration.deployment.factory.hex
        receiverImplementation = configuration.deployment.receiverImplementation.hex
        vault = request.vault.hex
        receiver = request.receiver.hex
        tokenAddress = request.token.address.hex
        tokenSymbol = request.token.symbol
        tokenDecimals = Int(request.token.decimals)
        expectedAmount = request.expectedAmount.decimalString
        erc681URI = request.erc681URI
        createdAt = request.createdAt
        expiresAt = request.expiresAt
        statusLabel = "Waiting"
        observedBalance = "0"
        confirmationBlocks = storedConfirmationBlocks
    }

    func configurationSnapshot() throws -> TerminalConfiguration {
        guard let endpoint = URL(string: rpcURL),
              let version = OPKProtocolVersion(rawValue: protocolVersion),
              version == .v1_4_1,
              let storedChainID = UInt64(exactly: chainID), storedChainID > 0,
              let decimals = UInt8(exactly: tokenDecimals),
              let blocks = UInt64(exactly: confirmationBlocks), blocks > 0
        else { throw AppSettingsError.invalidValue }
        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol,
            decimals: decimals
        )
        return try TerminalConfiguration(
            chainID: storedChainID,
            rpcEndpoints: [endpoint],
            protocolVersion: version,
            deployment: OPKDeployment(
                factory: EthereumAddress(hex: factory, allowZero: false),
                receiverImplementation: EthereumAddress(hex: receiverImplementation, allowZero: false),
                vault: EthereumAddress(hex: vault, allowZero: false)
            ),
            tokens: [token],
            confirmationPolicy: .init(requiredBlocks: blocks)
        )
    }

    func paymentRequest() throws -> PaymentRequest {
        guard let storedChainID = UInt64(exactly: chainID), storedChainID > 0,
              let decimals = UInt8(exactly: tokenDecimals)
        else { throw AppSettingsError.invalidValue }
        let token = try PaymentToken(
            address: EthereumAddress(hex: tokenAddress, allowZero: false),
            symbol: tokenSymbol,
            decimals: decimals
        )
        let request = PaymentRequest(
            invoiceID: try Bytes32(hex: invoiceID),
            terminalIdentifier: TerminalIdentifier(
                address: try EthereumAddress(hex: terminalIdentifier, allowZero: false)
            ),
            chainID: storedChainID,
            vault: try EthereumAddress(hex: vault, allowZero: false),
            receiver: try EthereumAddress(hex: receiver, allowZero: false),
            token: token,
            expectedAmount: try UInt256(decimalString: expectedAmount),
            erc681URI: erc681URI,
            createdAt: createdAt,
            expiresAt: expiresAt
        )
        let parsedURI = try ERC681TransferRequest.parse(erc681URI, expectedChainID: storedChainID)
        guard parsedURI.token == request.token.address,
              parsedURI.recipient == request.receiver,
              parsedURI.amount == request.expectedAmount,
              parsedURI.canonicalString == erc681URI
        else { throw AppSettingsError.invalidValue }
        return request
    }

    func apply(_ observation: PaymentObservation) throws {
        guard let storedBlock = Int64(exactly: observation.blockNumber) else {
            throw AppSettingsError.invalidValue
        }
        let storedThreshold: Int64?
        if let threshold = observation.thresholdBlock {
            guard let value = Int64(exactly: threshold) else { throw AppSettingsError.invalidValue }
            storedThreshold = value
        } else {
            storedThreshold = nil
        }
        observedBalance = observation.balance.decimalString
        observedBlock = storedBlock
        thresholdBlock = storedThreshold
        switch observation.status {
        case .waiting:
            statusLabel = "Waiting"
        case .partial:
            statusLabel = "Partially funded"
        case let .confirming(_, confirmations, required):
            statusLabel = "Confirming \(confirmations)/\(required)"
        case .paid:
            statusLabel = "Paid"
        case .overpaid:
            statusLabel = "Overpaid"
        case .expired:
            statusLabel = "Expired"
        }
    }

    var formattedAmount: String {
        guard let raw = try? UInt256(decimalString: expectedAmount),
              let decimals = UInt8(exactly: tokenDecimals)
        else { return expectedAmount }
        return "\(TokenAmount(rawValue: raw, decimals: decimals).displayString()) \(tokenSymbol)"
    }

    var shouldPresentQRCode: Bool {
        guard !locallyClosed else { return false }
        return statusLabel == "Waiting"
            || statusLabel == "Partially funded"
            || statusLabel.hasPrefix("Confirming ")
    }
}
