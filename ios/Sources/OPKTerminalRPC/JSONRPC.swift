import Foundation
import OPKTerminalCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

enum JSONValue: Hashable, Sendable, Codable {
    case string(String)
    case number(Int64)
    case bool(Bool)
    case array([JSONValue])
    case object([String: JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode(Int64.self) { self = .number(value) }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode([JSONValue].self) { self = .array(value) }
        else { self = .object(try container.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
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

public struct RPCTransportResponse: Sendable {
    public let statusCode: Int
    public let body: Data

    public init(statusCode: Int, body: Data) {
        self.statusCode = statusCode
        self.body = body
    }
}

public enum RPCTransportSecurityError: Error, Equatable, Sendable {
    case responseTooLarge(maximumBytes: Int)
}

public protocol RPCTransport: Sendable {
    func send(_ request: URLRequest) async throws -> RPCTransportResponse
}

public enum RPCRequestDeadlineError: Error, Equatable, Sendable {
    case expired
}

extension RPCRequestDeadlineError: LocalizedError {
    public var errorDescription: String? {
        "The RPC work deadline expired before the request completed."
    }
}

/// An absolute task-local budget for cooperative background RPC units. Every physical request
/// derives its timeout immediately before transport, so time already spent in earlier proof waves
/// or waiting for the shared origin limiter reduces the next request's budget. Interactive calls
/// do not install this value and retain the normal transport timeouts.
public enum RPCRequestDeadline {
    @TaskLocal public static var current: ContinuousClock.Instant?

    private static let clock = ContinuousClock()

    public static func withDeadline<Value>(
        after duration: Duration,
        isolation: isolated (any Actor)? = #isolation,
        operation: () async throws -> Value
    ) async rethrows -> Value {
        precondition(duration > .zero)
        let proposed = clock.now.advanced(by: duration)
        let effective = current.map { min($0, proposed) } ?? proposed
        return try await $current.withValue(effective) {
            try await operation()
        }
    }

    public static func boundedRequestTimeout(
        default defaultTimeout: TimeInterval
    ) throws -> TimeInterval {
        guard let current else { return defaultTimeout }
        let remaining = clock.now.duration(to: current)
        guard remaining > .zero else { throw RPCRequestDeadlineError.expired }
        let components = remaining.components
        let seconds = Double(components.seconds)
            + Double(components.attoseconds) / 1_000_000_000_000_000_000
        guard seconds > 0 else { throw RPCRequestDeadlineError.expired }
        return min(defaultTimeout, max(0.001, seconds))
    }

    public static func check() throws {
        guard let current, clock.now >= current else { return }
        throw RPCRequestDeadlineError.expired
    }
}

/// Process-wide public-RPC concurrency budget shared by read and operator clients. Limits are
/// isolated per normalized origin (scheme, host, effective port), so independent networks do not
/// block each other while helper-local task groups cannot accidentally stack beyond six physical
/// HTTP requests to one free endpoint.
public actor RPCOriginRequestLimiter {
    public static let shared = RPCOriginRequestLimiter()

    private struct Waiter {
        let id: UUID
        let continuation: CheckedContinuation<Void, any Error>
    }

    private struct OriginState {
        var active = 0
        var waiters = [Waiter]()
    }

    private let maximumConcurrentRequests: Int
    private var states = [String: OriginState]()

    public init(maximumConcurrentRequests: Int = 6) {
        self.maximumConcurrentRequests = max(1, maximumConcurrentRequests)
    }

    public func withPermit<Value: Sendable>(
        for endpoint: URL,
        operation: @Sendable () async throws -> Value
    ) async throws -> Value {
        let origin = Self.originKey(endpoint)
        try await acquire(origin)
        defer { release(origin) }
        return try await operation()
    }

    /// Waiting is cancellation-aware: a cancelled waiter leaves the queue immediately and
    /// throws, so an abandoned advisory read or a cancelled background unit can never remain
    /// parked behind saturated slow requests. Only a waiter that actually received the permit
    /// reaches the caller's release path.
    private func acquire(_ origin: String) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (
                continuation: CheckedContinuation<Void, any Error>
            ) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                var state = states[origin] ?? OriginState()
                if state.active < maximumConcurrentRequests {
                    state.active += 1
                    states[origin] = state
                    continuation.resume(returning: ())
                    return
                }
                state.waiters.append(Waiter(id: id, continuation: continuation))
                states[origin] = state
            }
        } onCancel: {
            Task { await self.cancelWaiter(origin: origin, id: id) }
        }
    }

    private func cancelWaiter(origin: String, id: UUID) {
        guard var state = states[origin],
              let index = state.waiters.firstIndex(where: { $0.id == id })
        else { return }
        let waiter = state.waiters.remove(at: index)
        states[origin] = state
        waiter.continuation.resume(throwing: CancellationError())
    }

    private func release(_ origin: String) {
        guard var state = states[origin], state.active > 0 else { return }
        if !state.waiters.isEmpty {
            let next = state.waiters.removeFirst()
            states[origin] = state
            next.continuation.resume(returning: ())
        } else {
            state.active -= 1
            if state.active == 0 {
                states.removeValue(forKey: origin)
            } else {
                states[origin] = state
            }
        }
    }

    private nonisolated static func originKey(_ endpoint: URL) -> String {
        let scheme = endpoint.scheme?.lowercased() ?? ""
        let host = endpoint.host?.lowercased() ?? ""
        let effectivePort = endpoint.port ?? (scheme == "https" ? 443 : 80)
        return "\(scheme)://\(host):\(effectivePort)"
    }
}

private final class NoRedirectSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

public struct URLSessionRPCTransport: RPCTransport {
    public static let shared = URLSessionRPCTransport()

    private let session: URLSession
    private let maximumResponseBytes: Int

    public init(maximumResponseBytes: Int = 1_048_576) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        session = URLSession(
            configuration: configuration,
            delegate: NoRedirectSessionDelegate(),
            delegateQueue: nil
        )
        self.maximumResponseBytes = max(1, maximumResponseBytes)
    }

    public func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard data.count <= maximumResponseBytes else {
            throw RPCTransportSecurityError.responseTooLarge(maximumBytes: maximumResponseBytes)
        }
        return RPCTransportResponse(statusCode: http.statusCode, body: data)
    }
}

struct RPCServerError: Error, Hashable, Sendable, Codable {
    public let code: Int
    public let message: String

    public init(code: Int, message: String) {
        self.code = code
        self.message = message
    }
}

enum JSONRPCError: Error, Equatable, Sendable {
    case invalidHTTPStatus(Int)
    /// The remote body was not syntactically valid JSON and may have been truncated or replaced
    /// by a transient gateway response. Semantic JSON-RPC/proof violations use malformedResponse.
    case remoteResponseDecodeFailure
    case malformedResponse
    case mismatchedID
    case batchLimitExceeded(maximum: Int)
    case server(RPCServerError)
}

private struct JSONRPCRequest: Encodable {
    let jsonrpc = "2.0"
    let id: UInt64
    let method: String
    let params: [JSONValue]
}

private struct JSONRPCResponse<Result: Decodable>: Decodable {
    let jsonrpc: String
    let id: UInt64
    let result: Result?
    let error: RPCServerError?
}

/// `JSONDecoder` intentionally accepts JSON `1.0` and `1e0` when decoding `UInt64`. JSON-RPC
/// correlation is a trust boundary, so inspect Foundation's parsed numeric representation first:
/// lexical integer JSON numbers are `q`/`Q`, while decimal/exponent numbers are `d` and booleans
/// are `c`. This uses APIs available in Foundation on Darwin and Linux.
private enum StrictJSONRPCResponseID {
    static func single(in data: Data) throws -> UInt64 {
        let value: Any
        do {
            value = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw JSONRPCError.remoteResponseDecodeFailure
        }
        guard let object = value as? [String: Any] else {
            throw JSONRPCError.malformedResponse
        }
        return try parse(object["id"])
    }

    static func batch(in data: Data) throws -> [UInt64] {
        let value: Any
        do {
            value = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw JSONRPCError.remoteResponseDecodeFailure
        }
        guard let objects = value as? [[String: Any]] else {
            throw JSONRPCError.malformedResponse
        }
        return try objects.map { try parse($0["id"]) }
    }

    private static func parse(_ raw: Any?) throws -> UInt64 {
        guard let number = raw as? NSNumber else {
            throw JSONRPCError.mismatchedID
        }
        let numericType = String(cString: number.objCType)
        guard numericType == "q" || numericType == "Q",
              let value = UInt64(number.stringValue)
        else { throw JSONRPCError.mismatchedID }
        return value
    }
}

struct JSONRPCBatchCall: Sendable {
    let method: String
    let params: [JSONValue]
}

struct JSONRPCBatchResponse: Decodable {
    let jsonrpc: String
    let id: UInt64
    let result: JSONValue?
    let error: RPCServerError?
}

actor JSONRPCClient {
    let endpoint: URL
    private let transport: any RPCTransport
    private var nextID: UInt64 = 1
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(endpoint: URL, transport: any RPCTransport = URLSessionRPCTransport.shared) throws {
        try RPCURLPolicy.validate(endpoint)
        self.endpoint = endpoint
        self.transport = transport
    }

    func call<Result: Decodable & Sendable>(
        _ method: String,
        params: [JSONValue] = [],
        as: Result.Type = Result.self
    ) async throws -> Result {
        let id = nextID
        nextID &+= 1
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(JSONRPCRequest(id: id, method: method, params: params))
        let requestToSend = request

        let response = try await RPCOriginRequestLimiter.shared.withPermit(for: endpoint) {
            var boundedRequest = requestToSend
            boundedRequest.timeoutInterval = try RPCRequestDeadline.boundedRequestTimeout(
                default: 20
            )
            return try await transport.send(boundedRequest)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw JSONRPCError.invalidHTTPStatus(response.statusCode)
        }
        let strictID = try StrictJSONRPCResponseID.single(in: response.body)
        let decoded: JSONRPCResponse<Result>
        do {
            decoded = try decoder.decode(JSONRPCResponse<Result>.self, from: response.body)
        } catch {
            // JSONSerialization already proved the body is syntactically valid JSON while
            // extracting its strict ID. A typed decoding failure is therefore a semantic
            // JSON-RPC/result-shape violation, not transient wire corruption.
            throw JSONRPCError.malformedResponse
        }
        guard decoded.jsonrpc == "2.0" else { throw JSONRPCError.malformedResponse }
        guard strictID == id, decoded.id == strictID else { throw JSONRPCError.mismatchedID }
        if let error = decoded.error { throw JSONRPCError.server(error) }
        guard let result = decoded.result else { throw JSONRPCError.malformedResponse }
        return result
    }

    /// Sends a small heterogeneous JSON-RPC batch. Responses may arrive in any order, but the
    /// returned values always match the input order. A partial, duplicate, unexpected, or
    /// individually failed response rejects the entire batch; callers must never continue with
    /// an incomplete on-chain proof.
    func callBatch(_ calls: [JSONRPCBatchCall]) async throws -> [JSONValue] {
        guard !calls.isEmpty else { return [] }
        let maximumBatchSize = 10
        guard calls.count <= maximumBatchSize else {
            throw JSONRPCError.batchLimitExceeded(maximum: maximumBatchSize)
        }

        var requests = [JSONRPCRequest]()
        requests.reserveCapacity(calls.count)
        var expectedIDs = [UInt64]()
        expectedIDs.reserveCapacity(calls.count)
        for call in calls {
            let id = nextID
            nextID &+= 1
            expectedIDs.append(id)
            requests.append(JSONRPCRequest(id: id, method: call.method, params: call.params))
        }

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(requests)
        let requestToSend = request

        let response = try await RPCOriginRequestLimiter.shared.withPermit(for: endpoint) {
            var boundedRequest = requestToSend
            boundedRequest.timeoutInterval = try RPCRequestDeadline.boundedRequestTimeout(
                default: 20
            )
            return try await transport.send(boundedRequest)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw JSONRPCError.invalidHTTPStatus(response.statusCode)
        }
        let strictIDs = try StrictJSONRPCResponseID.batch(in: response.body)
        let decoded: [JSONRPCBatchResponse]
        do {
            decoded = try decoder.decode([JSONRPCBatchResponse].self, from: response.body)
        } catch {
            throw JSONRPCError.malformedResponse
        }
        guard decoded.count == expectedIDs.count,
              strictIDs.count == decoded.count
        else { throw JSONRPCError.malformedResponse }

        var byID = [UInt64: JSONRPCBatchResponse]()
        for (item, strictID) in zip(decoded, strictIDs) {
            guard item.jsonrpc == "2.0",
                  item.id == strictID,
                  expectedIDs.contains(strictID),
                  byID.updateValue(item, forKey: strictID) == nil
            else { throw JSONRPCError.mismatchedID }
        }
        return try expectedIDs.map { id in
            guard let item = byID[id] else { throw JSONRPCError.mismatchedID }
            if let error = item.error { throw JSONRPCError.server(error) }
            guard let result = item.result else { throw JSONRPCError.malformedResponse }
            return result
        }
    }
}
