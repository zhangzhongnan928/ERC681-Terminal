#if canImport(XCTest)
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalRPC

private actor QueueTransport: RPCTransport {
    private var responses: [RPCTransportResponse]
    private(set) var requestBodies = [Data]()
    private(set) var requestTimeouts = [TimeInterval]()

    init(_ bodies: [String], statusCode: Int = 200) {
        responses = bodies.map {
            RPCTransportResponse(statusCode: statusCode, body: Data($0.utf8))
        }
    }

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        requestBodies.append(request.httpBody ?? Data())
        requestTimeouts.append(request.timeoutInterval)
        guard !responses.isEmpty else { throw URLError(.badServerResponse) }
        return responses.removeFirst()
    }
}

final class JSONRPCClientTests: XCTestCase {
    func testEthereumRPCDecodesQuantitiesDataAndCall() async throws {
        let transport = QueueTransport([
            #"{"jsonrpc":"2.0","id":1,"result":"0x14a34"}"#,
            #"{"jsonrpc":"2.0","id":2,"result":"0x10"}"#,
            #"{"jsonrpc":"2.0","id":3,"result":{"number":"0x10","hash":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"#,
            #"{"jsonrpc":"2.0","id":4,"result":"0x6001"}"#,
            #"{"jsonrpc":"2.0","id":5,"result":"0x000000000000000000000000000000000000000000000000000000000000002a"}"#,
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        let address = try EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
        let chainID = try await client.chainID()
        let blockNumber = try await client.blockNumber()
        let blockHash = try await client.canonicalBlockHash(at: blockNumber)
        let code = try await client.code(at: address, block: .latest)
        XCTAssertEqual(chainID, 84_532)
        XCTAssertEqual(blockNumber, 16)
        XCTAssertEqual(
            blockHash.hex,
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        )
        XCTAssertEqual(code.hexString, "0x6001")
        let result = try await client.call(
            to: address,
            data: ABI.encodeCall(selector: ABI.decimalsSelector),
            block: .number(16)
        )
        XCTAssertEqual(try ABI.decodeUInt256(result), UInt256(42))

        let bodies = await transport.requestBodies
        XCTAssertEqual(bodies.count, 5)
        let blockBody = String(decoding: bodies[2], as: UTF8.self)
        XCTAssertTrue(blockBody.contains("eth_getBlockByNumber"))
        XCTAssertTrue(blockBody.contains("0x10"))
        let callBody = String(decoding: bodies[4], as: UTF8.self)
        XCTAssertTrue(callBody.contains("eth_call"))
        XCTAssertTrue(callBody.contains("0x10"))
        XCTAssertTrue(callBody.contains(ABI.decimalsSelector.hexString))
    }

    func testServerErrorIsPreserved() async throws {
        let transport = QueueTransport([
            #"{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"execution reverted"}}"#,
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        do {
            _ = try await client.chainID()
            XCTFail("Expected server error")
        } catch let JSONRPCError.server(error) {
            XCTAssertEqual(error.code, -32_000)
            XCTAssertEqual(error.message, "execution reverted")
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testStrictQuantityDecoder() throws {
        XCTAssertEqual(try JSONRPCEthereumClient.decodeQuantity("0x0"), 0)
        XCTAssertEqual(try JSONRPCEthereumClient.decodeQuantity("0xff"), 255)
        XCTAssertThrowsError(try JSONRPCEthereumClient.decodeQuantity("0x00"))
        XCTAssertThrowsError(try JSONRPCEthereumClient.decodeQuantity("255"))
    }

    func testCanonicalBlockHashRejectsMismatchedReturnedBlockNumber() async throws {
        let transport = QueueTransport([
            #"{"jsonrpc":"2.0","id":1,"result":{"number":"0x11","hash":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"#,
            #"[{"jsonrpc":"2.0","id":2,"result":{"number":"0x11","hash":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]"#,
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        await XCTAssertThrowsErrorAsync(
            try await client.canonicalBlockHash(at: 16)
        )
        await XCTAssertThrowsErrorAsync(
            try await client.batch([.canonicalBlockHash(16)])
        )
    }

    func testStrictBatchAcceptsOutOfOrderCompleteResponsesAndUsesOneRequest() async throws {
        let transport = QueueTransport([
            #"[{"jsonrpc":"2.0","id":2,"result":"0x10"},{"jsonrpc":"2.0","id":1,"result":"0x14a34"}]"#,
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        let values = try await client.batch([.chainID, .blockNumber])

        XCTAssertEqual(values, [.quantity(84_532), .quantity(16)])
        let bodies = await transport.requestBodies
        XCTAssertEqual(bodies.count, 1)
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: bodies[0]) as? [[String: Any]]
        )
        XCTAssertEqual(json.count, 2)
        XCTAssertEqual(Set(json.compactMap { $0["method"] as? String }), [
            "eth_chainId", "eth_blockNumber",
        ])
    }

    func testStrictBatchRejectsDuplicateMissingAndUnexpectedIDs() async throws {
        for body in [
            #"[{"jsonrpc":"2.0","id":1,"result":"0x1"},{"jsonrpc":"2.0","id":1,"result":"0x2"}]"#,
            #"[{"jsonrpc":"2.0","id":1,"result":"0x1"}]"#,
            #"[{"jsonrpc":"2.0","id":1,"result":"0x1"},{"jsonrpc":"2.0","id":99,"result":"0x2"}]"#,
        ] {
            let transport = QueueTransport([body])
            let client = try JSONRPCEthereumClient(
                endpoint: URL(string: "https://rpc.example")!,
                transport: transport
            )
            await XCTAssertThrowsErrorAsync(
                try await client.batch([.chainID, .blockNumber])
            )
        }
    }

    func testResponseIDsRejectDecimalExponentStringAndBooleanForms() async throws {
        let invalidIDs = ["1.0", "1e0", "\"1\"", "true"]
        for invalidID in invalidIDs {
            let single = QueueTransport([
                "{\"jsonrpc\":\"2.0\",\"id\":\(invalidID),\"result\":\"0x14a34\"}",
            ])
            let singleClient = try JSONRPCEthereumClient(
                endpoint: URL(string: "https://rpc.example")!,
                transport: single
            )
            await XCTAssertThrowsErrorAsync(try await singleClient.chainID())

            let batch = QueueTransport([
                "[{\"jsonrpc\":\"2.0\",\"id\":\(invalidID),\"result\":\"0x14a34\"},"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"0x10\"}]",
            ])
            let batchClient = try JSONRPCEthereumClient(
                endpoint: URL(string: "https://rpc.example")!,
                transport: batch
            )
            await XCTAssertThrowsErrorAsync(
                try await batchClient.batch([.chainID, .blockNumber])
            )
        }
    }

    func testEndpointPoolReusesClientIdentityWithoutSharingAcrossEndpoints() throws {
        let pool = EthereumRPCClientPool(transport: QueueTransport([]))
        let firstEndpoint = URL(string: "https://rpc.example")!
        let secondEndpoint = URL(string: "https://rpc-two.example")!

        let first = try pool.client(for: firstEndpoint)
        XCTAssertTrue(first === (try pool.client(for: firstEndpoint)))
        XCTAssertFalse(first === (try pool.client(for: secondEndpoint)))
    }

    func testTaskLocalDeadlineBoundsPhysicalRequestTimeout() async throws {
        let transport = QueueTransport([
            #"{"jsonrpc":"2.0","id":1,"result":"0x14a34"}"#,
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        let chainID = try await RPCRequestDeadline.withDeadline(after: .seconds(5)) {
            try await client.chainID()
        }

        XCTAssertEqual(chainID, 84_532)
        let timeouts = await transport.requestTimeouts
        let timeout = try XCTUnwrap(timeouts.first)
        XCTAssertGreaterThan(timeout, 0)
        XCTAssertLessThanOrEqual(timeout, 5)
        XCTAssertLessThan(timeout, 20)
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected an error", file: file, line: line)
    } catch {}
}
#endif
