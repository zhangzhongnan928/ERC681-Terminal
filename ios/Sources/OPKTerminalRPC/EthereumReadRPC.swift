import Foundation
import OPKTerminalCore

public enum RPCBlockTag: Hashable, Sendable {
    case latest
    /// Mempool/preconfirmation view. Never part of a fixed-head proof: pending state can be
    /// replaced before inclusion, so it is only suitable for advisory reads such as the
    /// payment-detected UI hint.
    case pending
    case number(UInt64)

    var parameter: String {
        switch self {
        case .latest: "latest"
        case .pending: "pending"
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
}

public enum EthereumReadBatchRequest: Hashable, Sendable {
    case chainID
    case blockNumber
    case latestBlockIdentity
    case canonicalBlockHash(UInt64)
    case code(address: EthereumAddress, block: RPCBlockTag)
    case call(address: EthereumAddress, data: Data, block: RPCBlockTag)
}

public enum EthereumReadBatchResult: Hashable, Sendable {
    case quantity(UInt64)
    case blockIdentity(number: UInt64, hash: Bytes32)
    case blockHash(Bytes32)
    case data(Data)
}

public protocol EthereumBatchReadRPC: EthereumReadRPC {
    /// Production clients cap each HTTP batch at ten requests. Larger logical reads are split
    /// into ordered bounded batches without silently falling back to unverified partial results.
    func batch(_ requests: [EthereumReadBatchRequest]) async throws -> [EthereumReadBatchResult]
}

public actor JSONRPCEthereumClient: EthereumBatchReadRPC {
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

extension Character {
    fileprivate var isHexDigit: Bool {
        switch self {
        case "0"..."9", "a"..."f", "A"..."F": true
        default: false
        }
    }
}
