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
    case malformedResponse
    case mismatchedID
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

actor JSONRPCClient {
    let endpoint: URL
    private let transport: any RPCTransport
    private var nextID: UInt64 = 1
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(endpoint: URL, transport: any RPCTransport = URLSessionRPCTransport()) throws {
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

        let response = try await transport.send(request)
        guard (200..<300).contains(response.statusCode) else {
            throw JSONRPCError.invalidHTTPStatus(response.statusCode)
        }
        let decoded = try decoder.decode(JSONRPCResponse<Result>.self, from: response.body)
        guard decoded.jsonrpc == "2.0" else { throw JSONRPCError.malformedResponse }
        guard decoded.id == id else { throw JSONRPCError.mismatchedID }
        if let error = decoded.error { throw JSONRPCError.server(error) }
        guard let result = decoded.result else { throw JSONRPCError.malformedResponse }
        return result
    }
}
