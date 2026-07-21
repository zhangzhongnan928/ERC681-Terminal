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
    func tokenBalances(
        token: EthereumAddress,
        accounts: [EthereumAddress]
    ) async throws -> [UInt256]
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

    /// Alternate and test transports retain the simple single-read contract. The production
    /// client overrides this with strict bounded JSON-RPC batching.
    func tokenBalances(
        token: EthereumAddress,
        accounts: [EthereumAddress]
    ) async throws -> [UInt256] {
        var balances = [UInt256]()
        balances.reserveCapacity(accounts.count)
        for account in accounts {
            balances.append(try await tokenBalance(token: token, account: account))
        }
        return balances
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
        transport: any RPCTransport = URLSessionRPCTransport.shared
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
        guard try decodeUInt64(requiredString(object["number"])) == blockNumber else {
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

    func tokenBalances(
        token: EthereumAddress,
        accounts: [EthereumAddress]
    ) async throws -> [UInt256] {
        guard !accounts.isEmpty else { return [] }
        let calls = accounts.map { account in
            OperatorBatchCall(
                method: "eth_call",
                params: [
                    transactionObject(
                        from: nil,
                        to: token,
                        data: ABI.encodeCall(
                            selector: ABI.balanceOfSelector,
                            words: [ABI.word(account)]
                        )
                    ),
                    .string("latest"),
                ]
            )
        }
        let chunks = stride(from: 0, to: calls.count, by: 10).map { start in
            Array(calls[start..<min(start + 10, calls.count)])
        }
        let results = try await withThrowingTaskGroup(
            of: (Int, [OperatorBatchResult]).self,
            returning: [[OperatorBatchResult]].self
        ) { group in
            var nextChunk = 0
            let maximumConcurrentBatches = min(4, chunks.count)
            func enqueue(_ index: Int) {
                let chunk = chunks[index]
                group.addTask { (index, try await self.requestBatch(chunk)) }
            }
            while nextChunk < maximumConcurrentBatches {
                enqueue(nextChunk)
                nextChunk += 1
            }
            var resolved = Array<[OperatorBatchResult]?>(repeating: nil, count: chunks.count)
            for try await (index, values) in group {
                guard values.count == chunks[index].count else {
                    throw OperatorRPCError.malformedResponse
                }
                resolved[index] = values
                if nextChunk < chunks.count {
                    enqueue(nextChunk)
                    nextChunk += 1
                }
            }
            guard resolved.allSatisfy({ $0 != nil }) else {
                throw OperatorRPCError.malformedResponse
            }
            return resolved.map { $0! }
        }
        return try results.flatMap { chunk in
            try chunk.map { try ABI.decodeUInt256(decodeBatchData($0)) }
        }
    }

    func vaultAuthorization(
        vault: EthereumAddress,
        operatorAddress: EthereumAddress
    ) async throws -> VaultAuthorization {
        let results = try await requestBatch([
            OperatorBatchCall(
                method: "eth_call",
                params: [
                    transactionObject(
                        from: nil,
                        to: vault,
                        data: SettlementABI.encodeIsOperator(operatorAddress)
                    ),
                    .string("latest"),
                ]
            ),
            OperatorBatchCall(
                method: "eth_call",
                params: [
                    transactionObject(
                        from: nil,
                        to: vault,
                        data: SettlementABI.encodeOwner()
                    ),
                    .string("latest"),
                ]
            ),
        ])
        let operatorResult = try decodeBatchData(results[0])
        let isOperator = try ABI.decodeBool(operatorResult)

        do {
            let ownerResult = try decodeBatchData(results[1])
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
        let results = try await requestBatch([
            OperatorBatchCall(method: "eth_gasPrice", params: []),
            OperatorBatchCall(
                method: "eth_getBlockByNumber",
                params: [.string("pending"), .bool(false)]
            ),
            OperatorBatchCall(method: "eth_maxPriorityFeePerGas", params: []),
        ])
        let gasPrice = try decodeUInt64(try decodeBatchString(results[0]))

        let pendingBlock = try? decodeBatchValue(results[1])
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

        let remotePriority = try? decodeUInt64(try decodeBatchString(results[2]))
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
        let requestToSend = request

        let response = try await RPCOriginRequestLimiter.shared.withPermit(for: endpoint) {
            var boundedRequest = requestToSend
            boundedRequest.timeoutInterval = try RPCRequestDeadline.boundedRequestTimeout(
                default: 20
            )
            return try await transport.send(boundedRequest)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw OperatorRPCError.invalidHTTPStatus(response.statusCode)
        }
        let strictID = try StrictOperatorJSONRPCResponseID.single(in: response.body)
        let decoded = try decoder.decode(OperatorJSONRPCResponse.self, from: response.body)
        guard decoded.jsonrpc == "2.0" else { throw OperatorRPCError.malformedResponse }
        guard strictID == id, decoded.id == strictID else {
            throw OperatorRPCError.mismatchedID
        }
        if let error = decoded.error {
            throw OperatorRPCError.server(code: error.code, message: error.message)
        }
        if decoded.result == nil, !allowsNull { throw OperatorRPCError.malformedResponse }
        return decoded.result
    }

    private func requestBatch(
        _ calls: [OperatorBatchCall]
    ) async throws -> [OperatorBatchResult] {
        guard !calls.isEmpty else { return [] }
        guard calls.count <= 10 else { throw OperatorRPCError.malformedResponse }
        var payloads = [OperatorJSONRPCRequest]()
        var expectedIDs = [UInt64]()
        payloads.reserveCapacity(calls.count)
        expectedIDs.reserveCapacity(calls.count)
        for call in calls {
            let id = nextID
            nextID &+= 1
            expectedIDs.append(id)
            payloads.append(OperatorJSONRPCRequest(
                id: id,
                method: call.method,
                params: call.params
            ))
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(payloads)
        let requestToSend = request

        let response = try await RPCOriginRequestLimiter.shared.withPermit(for: endpoint) {
            var boundedRequest = requestToSend
            boundedRequest.timeoutInterval = try RPCRequestDeadline.boundedRequestTimeout(
                default: 20
            )
            return try await transport.send(boundedRequest)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw OperatorRPCError.invalidHTTPStatus(response.statusCode)
        }
        let strictIDs = try StrictOperatorJSONRPCResponseID.batch(in: response.body)
        let decoded = try decoder.decode([OperatorJSONRPCResponse].self, from: response.body)
        guard decoded.count == expectedIDs.count,
              strictIDs.count == decoded.count
        else { throw OperatorRPCError.malformedResponse }
        var byID = [UInt64: OperatorJSONRPCResponse]()
        for (item, strictID) in zip(decoded, strictIDs) {
            guard item.jsonrpc == "2.0",
                  item.id == strictID,
                  expectedIDs.contains(strictID),
                  byID.updateValue(item, forKey: strictID) == nil,
                  (item.result == nil) != (item.error == nil)
            else { throw OperatorRPCError.mismatchedID }
        }
        return try expectedIDs.map { id in
            guard let item = byID[id] else { throw OperatorRPCError.mismatchedID }
            if let error = item.error {
                return .failure(.server(code: error.code, message: error.message))
            }
            guard let result = item.result else { throw OperatorRPCError.malformedResponse }
            return .success(result)
        }
    }

    private func decodeBatchValue(_ result: OperatorBatchResult) throws -> OperatorJSONValue {
        switch result {
        case let .success(value): value
        case let .failure(error): throw error
        }
    }

    private func decodeBatchString(_ result: OperatorBatchResult) throws -> String {
        try requiredString(decodeBatchValue(result))
    }

    private func decodeBatchData(_ result: OperatorBatchResult) throws -> Data {
        let value = try decodeBatchString(result)
        do {
            return try Data(hex: value)
        } catch {
            throw SettlementOperatorError.invalidRPCData(value)
        }
    }
}

public final class OperatorRPCClientPool: @unchecked Sendable {
    public static let shared = OperatorRPCClientPool()

    private let lock = NSLock()
    private let transport: any RPCTransport
    private var clients = [URL: OperatorRPCClient]()

    public init(transport: any RPCTransport = URLSessionRPCTransport.shared) {
        self.transport = transport
    }

    public func client(for endpoint: URL) throws -> OperatorRPCClient {
        try lock.withLock {
            if let client = clients[endpoint] { return client }
            let client = try OperatorRPCClient(endpoint: endpoint, transport: transport)
            clients[endpoint] = client
            return client
        }
    }
}

private struct OperatorBatchCall: Sendable {
    let method: String
    let params: [OperatorJSONValue]
}

private enum OperatorBatchResult: Sendable {
    case success(OperatorJSONValue)
    case failure(OperatorRPCError)
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

private enum StrictOperatorJSONRPCResponseID {
    static func single(in data: Data) throws -> UInt64 {
        let value: Any
        do {
            value = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw OperatorRPCError.malformedResponse
        }
        guard let object = value as? [String: Any] else {
            throw OperatorRPCError.malformedResponse
        }
        return try parse(object["id"])
    }

    static func batch(in data: Data) throws -> [UInt64] {
        let value: Any
        do {
            value = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw OperatorRPCError.malformedResponse
        }
        guard let objects = value as? [[String: Any]] else {
            throw OperatorRPCError.malformedResponse
        }
        return try objects.map { try parse($0["id"]) }
    }

    private static func parse(_ raw: Any?) throws -> UInt64 {
        guard let number = raw as? NSNumber else {
            throw OperatorRPCError.mismatchedID
        }
        let numericType = String(cString: number.objCType)
        guard numericType == "q" || numericType == "Q",
              let value = UInt64(number.stringValue)
        else { throw OperatorRPCError.mismatchedID }
        return value
    }
}

private extension Character {
    var isOperatorHexDigit: Bool {
        switch self {
        case "0"..."9", "a"..."f", "A"..."F": true
        default: false
        }
    }
}
