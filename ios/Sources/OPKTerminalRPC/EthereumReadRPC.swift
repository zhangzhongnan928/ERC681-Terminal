// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

import Foundation
import OPKTerminalCore

public enum RPCBlockTag: Hashable, Sendable {
    case latest
    case number(UInt64)

    var parameter: String {
        switch self {
        case .latest: "latest"
        case let .number(number): "0x" + String(number, radix: 16)
        }
    }
}

public enum RPCDecodingError: Error, Equatable, Sendable {
    case invalidQuantity(String)
    case invalidData(String)
    case quantityOverflow
}

public protocol EthereumReadRPC: Sendable {
    func chainID() async throws -> UInt64
    func blockNumber() async throws -> UInt64
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32
    func code(at address: EthereumAddress, block: RPCBlockTag) async throws -> Data
    func call(to address: EthereumAddress, data: Data, block: RPCBlockTag) async throws -> Data
    func balance(of address: EthereumAddress, block: RPCBlockTag) async throws -> UInt256
}

public extension EthereumReadRPC {
    func balance(of address: EthereumAddress, block: RPCBlockTag) async throws -> UInt256 {
        throw RPCDecodingError.invalidData("eth_getBalance is unavailable")
    }
}

public enum EthereumReadBatchRequest: Hashable, Sendable {
    case chainID
    case blockNumber
    case latestBlockIdentity
    case canonicalBlockHash(UInt64)
    case code(address: EthereumAddress, block: RPCBlockTag)
    case call(address: EthereumAddress, data: Data, block: RPCBlockTag)
    case balance(address: EthereumAddress, block: RPCBlockTag)
}

public enum EthereumReadBatchResult: Hashable, Sendable {
    case quantity(UInt64)
    case blockIdentity(number: UInt64, hash: Bytes32)
    case blockHash(Bytes32)
    case data(Data)
    case uint256(UInt256)
}

public protocol EthereumBatchReadRPC: EthereumReadRPC {
    /// Production clients cap each HTTP batch at ten requests. Larger logical reads are split
    /// into ordered bounded batches without silently falling back to unverified partial results.
    func batch(_ requests: [EthereumReadBatchRequest]) async throws -> [EthereumReadBatchResult]
}

public actor JSONRPCEthereumClient: EthereumBatchReadRPC, PaymentEvidenceChainClient {
    private let client: JSONRPCClient

    public init(endpoint: URL, transport: any RPCTransport = URLSessionRPCTransport.shared) throws {
        client = try JSONRPCClient(endpoint: endpoint, transport: transport)
    }

    public func chainID() async throws -> UInt64 {
        let result: String = try await client.call("eth_chainId")
        return try Self.decodeQuantity(result)
    }

    public func blockNumber() async throws -> UInt64 {
        let result: String = try await client.call("eth_blockNumber")
        return try Self.decodeQuantity(result)
    }

    public func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        let result: RPCBlockIdentity = try await client.call(
            "eth_getBlockByNumber",
            params: [
                .string("0x" + String(blockNumber, radix: 16)),
                .bool(false),
            ]
        )
        guard try Self.decodeQuantity(result.number) == blockNumber else {
            throw JSONRPCError.malformedResponse
        }
        return try Bytes32(hex: result.hash)
    }

    public func code(at address: EthereumAddress, block: RPCBlockTag = .latest) async throws -> Data {
        let result: String = try await client.call(
            "eth_getCode",
            params: [.string(address.hex), .string(block.parameter)]
        )
        return try Self.decodeData(result)
    }

    public func call(to address: EthereumAddress, data: Data, block: RPCBlockTag = .latest) async throws -> Data {
        let call: JSONValue = .object([
            "to": .string(address.hex),
            "data": .string(data.hexString),
        ])
        let result: String = try await client.call(
            "eth_call",
            params: [call, .string(block.parameter)]
        )
        return try Self.decodeData(result)
    }

    public func balance(
        of address: EthereumAddress,
        block: RPCBlockTag = .latest
    ) async throws -> UInt256 {
        let result: String = try await client.call(
            "eth_getBalance",
            params: [.string(address.hex), .string(block.parameter)]
        )
        return try Self.decodeUInt256Quantity(result)
    }

    public func paymentEvidenceAssetBalance(
        asset: EthereumAddress,
        holder: EthereumAddress,
        blockNumber: UInt64
    ) async throws -> UInt256 {
        if NativeAsset.isNative(asset) {
            return try await balance(of: holder, block: .number(blockNumber))
        }
        let result = try await call(
            to: asset,
            data: ABI.encodeCall(
                selector: ABI.balanceOfSelector,
                words: [ABI.word(holder)]
            ),
            block: .number(blockNumber)
        )
        return try ABI.decodeUInt256(result)
    }

    public func paymentEvidenceBlock(
        at blockNumber: UInt64,
        includeTransactions: Bool
    ) async throws -> PaymentEvidenceBlock {
        let blockParameter = "0x" + String(blockNumber, radix: 16)
        if includeTransactions {
            let result: RPCPaymentEvidenceFullBlock = try await client.call(
                "eth_getBlockByNumber",
                params: [.string(blockParameter), .bool(true)]
            )
            return try Self.decodePaymentEvidenceBlock(result, expected: blockNumber)
        }
        let result: RPCPaymentEvidenceBlockHeader = try await client.call(
            "eth_getBlockByNumber",
            params: [.string(blockParameter), .bool(false)]
        )
        let number = try Self.decodeQuantity(result.number)
        guard number == blockNumber else { throw JSONRPCError.malformedResponse }
        return PaymentEvidenceBlock(
            number: number,
            hash: try Bytes32(hex: result.hash),
            timestamp: try Self.decodeQuantity(result.timestamp)
        )
    }

    public func paymentEvidenceERC20Transfers(
        token: EthereumAddress,
        recipient: EthereumAddress,
        blockNumber: UInt64
    ) async throws -> [PaymentEvidenceERC20Transfer] {
        let blockParameter = "0x" + String(blockNumber, radix: 16)
        let filter: JSONValue = .object([
            "address": .string(token.hex),
            "fromBlock": .string(blockParameter),
            "toBlock": .string(blockParameter),
            "topics": .array([
                .string(Keccak256.hash(utf8: "Transfer(address,address,uint256)").hex),
                .null,
                .string(ABI.word(recipient).hexString),
            ]),
        ])
        let logs: [RPCPaymentEvidenceLog] = try await client.call(
            "eth_getLogs",
            params: [filter]
        )
        return try logs.map(Self.decodePaymentEvidenceTransfer)
    }

    public func batch(
        _ requests: [EthereumReadBatchRequest]
    ) async throws -> [EthereumReadBatchResult] {
        var resolved = [EthereumReadBatchResult]()
        resolved.reserveCapacity(requests.count)
        for start in stride(from: 0, to: requests.count, by: 10) {
            let end = min(start + 10, requests.count)
            let slice = Array(requests[start..<end])
            let values = try await client.callBatch(slice.map(Self.batchCall))
            for (request, value) in zip(slice, values) {
                resolved.append(try Self.decodeBatchResult(value, for: request))
            }
        }
        return resolved
    }

    private static func batchCall(_ request: EthereumReadBatchRequest) -> JSONRPCBatchCall {
        switch request {
        case .chainID:
            JSONRPCBatchCall(method: "eth_chainId", params: [])
        case .blockNumber:
            JSONRPCBatchCall(method: "eth_blockNumber", params: [])
        case .latestBlockIdentity:
            JSONRPCBatchCall(
                method: "eth_getBlockByNumber",
                params: [.string("latest"), .bool(false)]
            )
        case let .canonicalBlockHash(blockNumber):
            JSONRPCBatchCall(
                method: "eth_getBlockByNumber",
                params: [
                    .string("0x" + String(blockNumber, radix: 16)),
                    .bool(false),
                ]
            )
        case let .code(address, block):
            JSONRPCBatchCall(
                method: "eth_getCode",
                params: [.string(address.hex), .string(block.parameter)]
            )
        case let .call(address, data, block):
            JSONRPCBatchCall(
                method: "eth_call",
                params: [
                    .object([
                        "to": .string(address.hex),
                        "data": .string(data.hexString),
                    ]),
                    .string(block.parameter),
                ]
            )
        case let .balance(address, block):
            JSONRPCBatchCall(
                method: "eth_getBalance",
                params: [.string(address.hex), .string(block.parameter)]
            )
        }
    }

    private static func decodeBatchResult(
        _ value: JSONValue,
        for request: EthereumReadBatchRequest
    ) throws -> EthereumReadBatchResult {
        switch request {
        case .chainID, .blockNumber:
            guard case let .string(quantity) = value else {
                throw JSONRPCError.malformedResponse
            }
            return .quantity(try decodeQuantity(quantity))
        case .latestBlockIdentity:
            guard case let .object(object) = value,
                  case let .string(number)? = object["number"],
                  case let .string(hash)? = object["hash"]
            else { throw JSONRPCError.malformedResponse }
            return .blockIdentity(
                number: try decodeQuantity(number),
                hash: try Bytes32(hex: hash)
            )
        case let .canonicalBlockHash(expectedBlockNumber):
            guard case let .object(object) = value,
                  case let .string(number)? = object["number"],
                  case let .string(hash)? = object["hash"]
            else { throw JSONRPCError.malformedResponse }
            guard try decodeQuantity(number) == expectedBlockNumber else {
                throw JSONRPCError.malformedResponse
            }
            return .blockHash(try Bytes32(hex: hash))
        case .code, .call:
            guard case let .string(data) = value else {
                throw JSONRPCError.malformedResponse
            }
            return .data(try decodeData(data))
        case .balance:
            guard case let .string(quantity) = value else {
                throw JSONRPCError.malformedResponse
            }
            return .uint256(try decodeUInt256Quantity(quantity))
        }
    }

    public static func decodeQuantity(_ value: String) throws -> UInt64 {
        guard value.hasPrefix("0x"), value.count > 2 else {
            throw RPCDecodingError.invalidQuantity(value)
        }
        let digits = value.dropFirst(2)
        guard digits == "0" || !digits.hasPrefix("0"),
              digits.allSatisfy({ $0.isHexDigit }),
              let decoded = UInt64(digits, radix: 16)
        else { throw RPCDecodingError.invalidQuantity(value) }
        return decoded
    }

    public static func decodeData(_ value: String) throws -> Data {
        do {
            return try Data(hex: value)
        } catch {
            throw RPCDecodingError.invalidData(value)
        }
    }

    public static func decodeUInt256Quantity(_ value: String) throws -> UInt256 {
        guard value.hasPrefix("0x"), value.count > 2 else {
            throw RPCDecodingError.invalidQuantity(value)
        }
        let digits = String(value.dropFirst(2))
        guard digits == "0" || !digits.hasPrefix("0"),
              digits.count <= 64,
              digits.allSatisfy(\.isHexDigit)
        else { throw RPCDecodingError.invalidQuantity(value) }
        let padded = digits.count.isMultiple(of: 2) ? digits : "0" + digits
        do {
            return try UInt256(hex: "0x" + padded)
        } catch {
            throw RPCDecodingError.invalidQuantity(value)
        }
    }

    private static func decodePaymentEvidenceBlock(
        _ block: RPCPaymentEvidenceFullBlock,
        expected blockNumber: UInt64
    ) throws -> PaymentEvidenceBlock {
        let number = try decodeQuantity(block.number)
        guard number == blockNumber else { throw JSONRPCError.malformedResponse }
        let blockHash = try Bytes32(hex: block.hash)
        let transactions = try block.transactions.map { transaction in
            PaymentEvidenceTransaction(
                hash: try Bytes32(hex: transaction.hash),
                from: try EthereumAddress(hex: transaction.from, allowZero: false),
                to: try transaction.to.map { try EthereumAddress(hex: $0) },
                value: try decodeUInt256Quantity(transaction.value),
                blockNumber: try decodeQuantity(transaction.blockNumber),
                blockHash: try Bytes32(hex: transaction.blockHash),
                transactionIndex: try decodeQuantity(transaction.transactionIndex)
            )
        }
        return PaymentEvidenceBlock(
            number: number,
            hash: blockHash,
            timestamp: try decodeQuantity(block.timestamp),
            transactions: transactions
        )
    }

    private static func decodePaymentEvidenceTransfer(
        _ log: RPCPaymentEvidenceLog
    ) throws -> PaymentEvidenceERC20Transfer {
        guard log.topics.count == 3,
              try Bytes32(hex: log.topics[0])
                == Keccak256.hash(utf8: "Transfer(address,address,uint256)")
        else { throw JSONRPCError.malformedResponse }
        let payer = try decodeIndexedAddress(log.topics[1])
        let recipient = try decodeIndexedAddress(log.topics[2])
        let amountData = try decodeData(log.data)
        guard amountData.count == 32 else { throw JSONRPCError.malformedResponse }
        return PaymentEvidenceERC20Transfer(
            token: try EthereumAddress(hex: log.address, allowZero: false),
            transactionHash: try Bytes32(hex: log.transactionHash),
            payer: payer,
            recipient: recipient,
            amount: UInt256(bigEndian: amountData),
            blockNumber: try decodeQuantity(log.blockNumber),
            blockHash: try Bytes32(hex: log.blockHash),
            logIndex: try decodeQuantity(log.logIndex),
            removed: log.removed ?? false
        )
    }

    private static func decodeIndexedAddress(_ topic: String) throws -> EthereumAddress {
        let word = try Bytes32(hex: topic)
        guard word.data.prefix(12).allSatisfy({ $0 == 0 }) else {
            throw JSONRPCError.malformedResponse
        }
        return try EthereumAddress(data: word.data.suffix(20), allowZero: false)
    }
}

/// Reuses one actor client per endpoint. The pool and its clients all share one URLSession-backed
/// transport, preserving DNS/TLS/HTTP connection state across readiness, sale, monitor, and
/// settlement phases without sharing any mutable validation result.
public final class EthereumRPCClientPool: @unchecked Sendable {
    public static let shared = EthereumRPCClientPool()

    private let lock = NSLock()
    private let transport: any RPCTransport
    private var clients = [URL: JSONRPCEthereumClient]()

    public init(transport: any RPCTransport = URLSessionRPCTransport.shared) {
        self.transport = transport
    }

    public func client(for endpoint: URL) throws -> JSONRPCEthereumClient {
        try lock.withLock {
            if let client = clients[endpoint] { return client }
            let client = try JSONRPCEthereumClient(endpoint: endpoint, transport: transport)
            clients[endpoint] = client
            return client
        }
    }
}

private struct RPCBlockIdentity: Decodable, Sendable {
    let number: String
    let hash: String
}

private struct RPCPaymentEvidenceBlockHeader: Decodable, Sendable {
    let number: String
    let hash: String
    let timestamp: String
}

private struct RPCPaymentEvidenceFullBlock: Decodable, Sendable {
    let number: String
    let hash: String
    let timestamp: String
    let transactions: [RPCPaymentEvidenceTransaction]
}

private struct RPCPaymentEvidenceTransaction: Decodable, Sendable {
    let hash: String
    let from: String
    let to: String?
    let value: String
    let blockNumber: String
    let blockHash: String
    let transactionIndex: String
}

private struct RPCPaymentEvidenceLog: Decodable, Sendable {
    let address: String
    let transactionHash: String
    let blockNumber: String
    let blockHash: String
    let logIndex: String
    let topics: [String]
    let data: String
    let removed: Bool?
}

extension Character {
    fileprivate var isHexDigit: Bool {
        switch self {
        case "0"..."9", "a"..."f", "A"..."F": true
        default: false
        }
    }
}
