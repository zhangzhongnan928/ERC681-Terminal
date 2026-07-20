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
}

public actor JSONRPCEthereumClient: EthereumReadRPC {
    private let client: JSONRPCClient

    public init(endpoint: URL, transport: any RPCTransport = URLSessionRPCTransport()) throws {
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

private struct RPCBlockIdentity: Decodable, Sendable {
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
