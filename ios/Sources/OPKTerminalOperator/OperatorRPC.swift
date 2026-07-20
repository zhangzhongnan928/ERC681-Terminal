import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import OPKTerminalCore
import OPKTerminalRPC

public enum OperatorRPCError: Error, Equatable, Sendable {
    case invalidHTTPStatus(Int)
    case malformedResponse
    case mismatchedID
    case server(code: Int, message: String)
}

extension OperatorRPCError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .invalidHTTPStatus(status):
            "The settlement RPC returned HTTP \(status)."
        case .malformedResponse:
            "The settlement RPC returned malformed JSON-RPC data."
        case .mismatchedID:
            "The settlement RPC response ID did not match the request."
        case let .server(code, message):
            "RPC error \(code): \(message)"
        }
    }
}

protocol EthereumOperatorRPC: Sendable {
    func chainID() async throws -> UInt64
    func blockNumber() async throws -> UInt64
    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32
    func balance(of address: EthereumAddress) async throws -> UInt256
    func latestBalance(of address: EthereumAddress) async throws -> UInt256
    func tokenBalance(token: EthereumAddress, account: EthereumAddress) async throws -> UInt256
    func vaultAuthorization(
        vault: EthereumAddress,
        operatorAddress: EthereumAddress
    ) async throws -> VaultAuthorization
    func simulate(from: EthereumAddress, to: EthereumAddress, data: Data) async throws
    func estimateGas(from: EthereumAddress, to: EthereumAddress, data: Data) async throws -> UInt64
    func feeQuote() async throws -> EIP1559FeeQuote
    func pendingNonce(of address: EthereumAddress) async throws -> UInt64
    func sendRawTransaction(_ rawTransaction: Data) async throws -> Bytes32
    func receipt(transactionHash: Bytes32) async throws -> EthereumTransactionReceipt?
}

extension EthereumOperatorRPC {
    /// Test and alternate transports may expose only one coherent balance view. The production
    /// RPC client overrides this with an explicit `latest` read.
    func latestBalance(of address: EthereumAddress) async throws -> UInt256 {
        try await balance(of: address)
    }
}

public actor OperatorRPCClient: EthereumOperatorRPC {
    private let endpoint: URL
    private let transport: any RPCTransport
    private var nextID: UInt64 = 1
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(
        endpoint: URL,
        transport: any RPCTransport = URLSessionRPCTransport()
    ) throws {
        try RPCURLPolicy.validate(endpoint)
        self.endpoint = endpoint
        self.transport = transport
    }

    func chainID() async throws -> UInt64 {
        try decodeUInt64(try await requiredString("eth_chainId"))
    }

    func blockNumber() async throws -> UInt64 {
        try decodeUInt64(try await requiredString("eth_blockNumber"))
    }

    func canonicalBlockHash(at blockNumber: UInt64) async throws -> Bytes32 {
        guard let value = try await request(
            "eth_getBlockByNumber",
            params: [.string("0x" + String(blockNumber, radix: 16)), .bool(false)]
        ), case let .object(object) = value else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        return try Bytes32(hex: requiredString(object["hash"]))
    }

    func balance(of address: EthereumAddress) async throws -> UInt256 {
        try decodeUInt256(
            try await requiredString(
                "eth_getBalance",
                params: [.string(address.hex), .string("pending")]
            )
        )
    }

    func latestBalance(of address: EthereumAddress) async throws -> UInt256 {
        try decodeUInt256(
            try await requiredString(
                "eth_getBalance",
                params: [.string(address.hex), .string("latest")]
            )
        )
    }

    func tokenBalance(token: EthereumAddress, account: EthereumAddress) async throws -> UInt256 {
        let result = try await callData(
            from: nil,
            to: token,
            data: ABI.encodeCall(
                selector: ABI.balanceOfSelector,
                words: [ABI.word(account)]
            )
        )
        return try ABI.decodeUInt256(result)
    }

    func vaultAuthorization(
        vault: EthereumAddress,
        operatorAddress: EthereumAddress
    ) async throws -> VaultAuthorization {
        let operatorResult = try await callData(
            from: nil,
            to: vault,
            data: SettlementABI.encodeIsOperator(operatorAddress)
        )
        let isOperator = try ABI.decodeBool(operatorResult)

        do {
            let ownerResult = try await callData(
                from: nil,
                to: vault,
                data: SettlementABI.encodeOwner()
            )
            guard ownerResult.count == 32,
                  ownerResult.prefix(12).allSatisfy({ $0 == 0 })
            else {
                throw SettlementOperatorError.invalidReceipt("non-canonical owner() result")
            }
            let owner = try EthereumAddress(data: ownerResult.suffix(20))
            return VaultAuthorization(
                isOperator: isOperator,
                isOwner: owner == operatorAddress
            )
        } catch {
            if isOperator {
                return VaultAuthorization(isOperator: true, isOwner: false)
            }
            throw error
        }
    }

    func simulate(from: EthereumAddress, to: EthereumAddress, data: Data) async throws {
        do {
            _ = try await callData(from: from, to: to, data: data)
        } catch {
            throw SettlementOperatorError.simulationFailed(error.localizedDescription)
        }
    }

    func estimateGas(from: EthereumAddress, to: EthereumAddress, data: Data) async throws -> UInt64 {
        let call = transactionObject(from: from, to: to, data: data)
        return try decodeUInt64(
            try await requiredString("eth_estimateGas", params: [call])
        )
    }

    func feeQuote() async throws -> EIP1559FeeQuote {
        let gasPrice = try decodeUInt64(try await requiredString("eth_gasPrice"))

        let pendingBlock = try? await request(
            "eth_getBlockByNumber",
            params: [.string("pending"), .bool(false)]
        )
        let baseFee: UInt64? = try pendingBlock.flatMap { value in
            guard case let .object(object) = value,
                  case let .string(quantity)? = object["baseFeePerGas"]
            else { return nil }
            return try decodeUInt64(quantity)
        }

        guard let baseFee else {
            return EIP1559FeeQuote(
                maxPriorityFeePerGas: min(gasPrice, 1_000_000_000),
                maxFeePerGas: gasPrice,
                source: .gasPriceFallback
            )
        }

        let remotePriority: UInt64?
        do {
            remotePriority = try decodeUInt64(try await requiredString("eth_maxPriorityFeePerGas"))
        } catch {
            remotePriority = nil
        }
        let inferredPriority = gasPrice > baseFee
            ? gasPrice - baseFee
            : min(gasPrice, 1_000_000_000)
        let priority = remotePriority ?? inferredPriority
        let (doubledBase, multiplyOverflow) = baseFee.multipliedReportingOverflow(by: 2)
        let (maximumFee, addOverflow) = doubledBase.addingReportingOverflow(priority)
        guard !multiplyOverflow, !addOverflow else {
            throw SettlementOperatorError.arithmeticOverflow
        }
        return EIP1559FeeQuote(
            maxPriorityFeePerGas: priority,
            maxFeePerGas: max(maximumFee, priority),
            source: .eip1559
        )
    }

    func pendingNonce(of address: EthereumAddress) async throws -> UInt64 {
        try decodeUInt64(
            try await requiredString(
                "eth_getTransactionCount",
                params: [.string(address.hex), .string("pending")]
            )
        )
    }

    func sendRawTransaction(_ rawTransaction: Data) async throws -> Bytes32 {
        try Bytes32(
            hex: try requiredString(
                try await request(
                    "eth_sendRawTransaction",
                    params: [.string(rawTransaction.hexString)]
                )
            )
        )
    }

    func receipt(transactionHash: Bytes32) async throws -> EthereumTransactionReceipt? {
        guard let value = try await request(
            "eth_getTransactionReceipt",
            params: [.string(transactionHash.hex)],
            allowsNull: true
        ) else { return nil }
        guard case let .object(object) = value else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        let decodedHash = try Bytes32(hex: requiredString(object["transactionHash"]))
        guard decodedHash == transactionHash else {
            throw SettlementOperatorError.transactionHashMismatch(
                expected: transactionHash,
                actual: decodedHash
            )
        }
        let status = try decodeUInt64(requiredString(object["status"]))
        guard status == 0 || status == 1 else {
            throw SettlementOperatorError.invalidReceipt("status was not canonical 0 or 1")
        }
        let blockNumber = try decodeUInt64(requiredString(object["blockNumber"]))
        let blockHash = try Bytes32(hex: requiredString(object["blockHash"]))
        guard case let .array(rawLogs)? = object["logs"] else {
            throw SettlementOperatorError.invalidReceipt("missing logs")
        }
        let logs = try rawLogs.map(decodeLog)
        guard logs.allSatisfy({ $0.blockHash == nil || $0.blockHash == blockHash }) else {
            throw SettlementOperatorError.invalidReceipt("log block hash did not match receipt")
        }
        return EthereumTransactionReceipt(
            transactionHash: decodedHash,
            blockNumber: blockNumber,
            blockHash: blockHash,
            succeeded: status == 1,
            logs: logs
        )
    }

    private func callData(
        from: EthereumAddress?,
        to: EthereumAddress,
        data: Data
    ) async throws -> Data {
        let call = transactionObject(from: from, to: to, data: data)
        let result = try await requiredString(
            "eth_call",
            params: [call, .string("latest")]
        )
        do {
            return try Data(hex: result)
        } catch {
            throw SettlementOperatorError.invalidRPCData(result)
        }
    }

    private func transactionObject(
        from: EthereumAddress?,
        to: EthereumAddress,
        data: Data
    ) -> OperatorJSONValue {
        var object: [String: OperatorJSONValue] = [
            "to": .string(to.hex),
            "data": .string(data.hexString),
            "value": .string("0x0"),
        ]
        if let from { object["from"] = .string(from.hex) }
        return .object(object)
    }

    private func decodeLog(_ value: OperatorJSONValue) throws -> EthereumLog {
        guard case let .object(object) = value,
              case let .array(rawTopics)? = object["topics"]
        else { throw SettlementOperatorError.invalidReceipt("malformed log") }
        let address = try EthereumAddress(
            hex: try requiredString(object["address"]),
            allowZero: false
        )
        let topics = try rawTopics.map { try Bytes32(hex: requiredString($0)) }
        let rawData = try requiredString(object["data"])
        let data: Data
        do {
            data = try Data(hex: rawData)
        } catch {
            throw SettlementOperatorError.invalidRPCData(rawData)
        }
        let logIndex = try optionalQuantity(object["logIndex"])
        let blockHash = try optionalBytes32(object["blockHash"])
        let logTransactionHash = try optionalBytes32(object["transactionHash"])
        let removed: Bool
        if case let .bool(value)? = object["removed"] { removed = value } else { removed = false }
        return EthereumLog(
            address: address,
            topics: topics,
            data: data,
            logIndex: logIndex,
            blockHash: blockHash,
            removed: removed,
            transactionHash: logTransactionHash
        )
    }

    private func optionalQuantity(_ value: OperatorJSONValue?) throws -> UInt64? {
        guard let value, value != .null else { return nil }
        return try decodeUInt64(requiredString(value))
    }

    private func optionalBytes32(_ value: OperatorJSONValue?) throws -> Bytes32? {
        guard let value, value != .null else { return nil }
        return try Bytes32(hex: requiredString(value))
    }

    private func requiredString(
        _ method: String,
        params: [OperatorJSONValue] = []
    ) async throws -> String {
        try requiredString(try await request(method, params: params))
    }

    private func requiredString(_ value: OperatorJSONValue?) throws -> String {
        guard case let .string(string)? = value else {
            throw SettlementOperatorError.malformedRPCResponse
        }
        return string
    }

    private func decodeUInt64(_ value: String) throws -> UInt64 {
        guard value.hasPrefix("0x"), value.count > 2 else {
            throw SettlementOperatorError.invalidRPCQuantity(value)
        }
        let digits = value.dropFirst(2)
        guard digits == "0" || !digits.hasPrefix("0"),
              digits.allSatisfy({ $0.isOperatorHexDigit }),
              let decoded = UInt64(digits, radix: 16)
        else { throw SettlementOperatorError.invalidRPCQuantity(value) }
        return decoded
    }

    private func decodeUInt256(_ value: String) throws -> UInt256 {
        guard value.hasPrefix("0x"), value.count > 2 else {
            throw SettlementOperatorError.invalidRPCQuantity(value)
        }
        let digits = String(value.dropFirst(2))
        guard digits == "0" || !digits.hasPrefix("0"),
              digits.count <= 64,
              digits.allSatisfy({ $0.isOperatorHexDigit })
        else { throw SettlementOperatorError.invalidRPCQuantity(value) }
        let padded = digits.count.isMultiple(of: 2) ? digits : "0" + digits
        guard let data = try? Data(hex: "0x" + padded) else {
            throw SettlementOperatorError.invalidRPCQuantity(value)
        }
        return UInt256(bigEndian: data)
    }

    private func request(
        _ method: String,
        params: [OperatorJSONValue] = [],
        allowsNull: Bool = false
    ) async throws -> OperatorJSONValue? {
        let id = nextID
        nextID &+= 1
        let payload = OperatorJSONRPCRequest(id: id, method: method, params: params)
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(payload)

        let response = try await transport.send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw OperatorRPCError.invalidHTTPStatus(response.statusCode)
        }
        let decoded = try decoder.decode(OperatorJSONRPCResponse.self, from: response.body)
        guard decoded.jsonrpc == "2.0" else { throw OperatorRPCError.malformedResponse }
        guard decoded.id == id else { throw OperatorRPCError.mismatchedID }
        if let error = decoded.error {
            throw OperatorRPCError.server(code: error.code, message: error.message)
        }
        if decoded.result == nil, !allowsNull { throw OperatorRPCError.malformedResponse }
        return decoded.result
    }
}

private enum OperatorJSONValue: Hashable, Sendable, Codable {
    case string(String)
    case number(Int64)
    case bool(Bool)
    case array([OperatorJSONValue])
    case object([String: OperatorJSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode(Int64.self) { self = .number(value) }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode([OperatorJSONValue].self) { self = .array(value) }
        else { self = .object(try container.decode([String: OperatorJSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .string(value): try container.encode(value)
        case let .number(value): try container.encode(value)
        case let .bool(value): try container.encode(value)
        case let .array(value): try container.encode(value)
        case let .object(value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
}

private struct OperatorJSONRPCRequest: Encodable {
    let jsonrpc = "2.0"
    let id: UInt64
    let method: String
    let params: [OperatorJSONValue]
}

private struct OperatorJSONRPCError: Decodable {
    let code: Int
    let message: String
}

private struct OperatorJSONRPCResponse: Decodable {
    let jsonrpc: String
    let id: UInt64
    let result: OperatorJSONValue?
    let error: OperatorJSONRPCError?
}

private extension Character {
    var isOperatorHexDigit: Bool {
        switch self {
        case "0"..."9", "a"..."f", "A"..."F": true
        default: false
        }
    }
}
