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

    init(_ bodies: [String], statusCode: Int = 200) {
        responses = bodies.map {
            RPCTransportResponse(statusCode: statusCode, body: Data($0.utf8))
        }
    }

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        requestBodies.append(request.httpBody ?? Data())
        guard !responses.isEmpty else { throw URLError(.badServerResponse) }
        return responses.removeFirst()
    }
}

final class JSONRPCClientTests: XCTestCase {
    func testEthereumRPCDecodesQuantitiesDataAndCall() async throws {
        let transport = QueueTransport([
            #"{"jsonrpc":"2.0","id":1,"result":"0x14a34"}"#,
            #"{"jsonrpc":"2.0","id":2,"result":"0x10"}"#,
            #"{"jsonrpc":"2.0","id":3,"result":"0x6001"}"#,
            #"{"jsonrpc":"2.0","id":4,"result":"0x000000000000000000000000000000000000000000000000000000000000002a"}"#,
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )
        let address = try EthereumAddress(hex: "0x1111111111111111111111111111111111111111")
        let chainID = try await client.chainID()
        let blockNumber = try await client.blockNumber()
        let code = try await client.code(at: address, block: .latest)
        XCTAssertEqual(chainID, 84_532)
        XCTAssertEqual(blockNumber, 16)
        XCTAssertEqual(code.hexString, "0x6001")
        let result = try await client.call(
            to: address,
            data: ABI.encodeCall(selector: ABI.decimalsSelector),
            block: .number(16)
        )
        XCTAssertEqual(try ABI.decodeUInt256(result), UInt256(42))

        let bodies = await transport.requestBodies
        XCTAssertEqual(bodies.count, 4)
        let callBody = String(decoding: bodies[3], as: UTF8.self)
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
}
#endif
