#if canImport(XCTest)
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import OPKTerminalCore
@testable import OPKTerminalRPC

private actor PaymentEvidenceQueueTransport: RPCTransport {
    private var responses: [RPCTransportResponse]
    private(set) var requestBodies = [Data]()

    init(_ bodies: [String]) {
        responses = bodies.map {
            RPCTransportResponse(statusCode: 200, body: Data($0.utf8))
        }
    }

    func send(_ request: URLRequest) async throws -> RPCTransportResponse {
        requestBodies.append(request.httpBody ?? Data())
        guard !responses.isEmpty else { throw URLError(.badServerResponse) }
        return responses.removeFirst()
    }
}

final class PaymentEvidenceJSONRPCTests: XCTestCase {
    func testProductionClientDecodesEvidenceReadsAndCanonicalTimestamp() async throws {
        let blockHash = "0x" + String(repeating: "15", count: 32)
        let transactionHash = "0x" + String(repeating: "77", count: 32)
        let transferHash = "0x" + String(repeating: "88", count: 32)
        let token = try EthereumAddress(
            hex: "0x4444444444444444444444444444444444444444"
        )
        let receiver = try EthereumAddress(
            hex: "0x3333333333333333333333333333333333333333"
        )
        let payer = try EthereumAddress(
            hex: "0x2222222222222222222222222222222222222222"
        )
        let amountWord = "0x" + String(repeating: "0", count: 62) + "50"
        let transferTopic = Keccak256.hash(
            utf8: "Transfer(address,address,uint256)"
        ).hex
        let payerTopic = ABI.word(payer).hexString
        let receiverTopic = ABI.word(receiver).hexString
        let transport = PaymentEvidenceQueueTransport([
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"\(amountWord)\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{"
                + "\"number\":\"0xf\",\"hash\":\"\(blockHash)\","
                + "\"timestamp\":\"0x96\",\"transactions\":[]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{"
                + "\"number\":\"0xf\",\"hash\":\"\(blockHash)\","
                + "\"timestamp\":\"0x96\",\"transactions\":[{"
                + "\"hash\":\"\(transactionHash)\",\"from\":\"\(payer.hex)\","
                + "\"to\":\"\(receiver.hex)\",\"value\":\"0x50\","
                + "\"blockNumber\":\"0xf\",\"blockHash\":\"\(blockHash)\","
                + "\"transactionIndex\":\"0x2\"}]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":[{"
                + "\"address\":\"\(token.hex)\",\"transactionHash\":\"\(transferHash)\","
                + "\"blockNumber\":\"0xf\",\"blockHash\":\"\(blockHash)\","
                + "\"logIndex\":\"0x3\",\"topics\":["
                + "\"\(transferTopic)\",\"\(payerTopic)\",\"\(receiverTopic)\"],"
                + "\"data\":\"\(amountWord)\",\"removed\":false}]}",
        ])
        let client = try JSONRPCEthereumClient(
            endpoint: URL(string: "https://rpc.example")!,
            transport: transport
        )

        let balance = try await client.paymentEvidenceAssetBalance(
            asset: token,
            holder: receiver,
            blockNumber: 15
        )
        XCTAssertEqual(balance, UInt256(80))
        let header = try await client.paymentEvidenceBlock(
            at: 15,
            includeTransactions: false
        )
        XCTAssertEqual(header.number, 15)
        XCTAssertEqual(header.timestamp, 150)
        XCTAssertTrue(header.transactions.isEmpty)

        let full = try await client.paymentEvidenceBlock(
            at: 15,
            includeTransactions: true
        )
        XCTAssertEqual(full.hash.hex, blockHash)
        XCTAssertEqual(full.timestamp, 150)
        XCTAssertEqual(full.transactions.count, 1)
        XCTAssertEqual(full.transactions[0].hash.hex, transactionHash)
        XCTAssertEqual(full.transactions[0].from, payer)
        XCTAssertEqual(full.transactions[0].to, receiver)
        XCTAssertEqual(full.transactions[0].transactionIndex, 2)

        let transfers = try await client.paymentEvidenceERC20Transfers(
            token: token,
            recipient: receiver,
            blockNumber: 15
        )
        XCTAssertEqual(transfers.count, 1)
        XCTAssertEqual(transfers[0].transactionHash.hex, transferHash)
        XCTAssertEqual(transfers[0].payer, payer)
        XCTAssertEqual(transfers[0].recipient, receiver)
        XCTAssertEqual(transfers[0].amount, UInt256(80))
        XCTAssertEqual(transfers[0].blockNumber, 15)
        XCTAssertEqual(transfers[0].logIndex, 3)

        let bodies = await transport.requestBodies
        XCTAssertEqual(bodies.count, 4)
        XCTAssertTrue(String(decoding: bodies[0], as: UTF8.self).contains("eth_call"))
        XCTAssertTrue(
            String(decoding: bodies[2], as: UTF8.self).contains("true")
        )
        let logBody = String(decoding: bodies[3], as: UTF8.self)
        XCTAssertTrue(logBody.contains("eth_getLogs"))
        XCTAssertTrue(logBody.contains(ABI.word(receiver).hexString))
    }

    func testProductionDecodersRejectMissingBlockIdentityAndZeroPayer() async throws {
        let blockHash = "0x" + String(repeating: "15", count: 32)
        let transactionHash = "0x" + String(repeating: "77", count: 32)
        let token = try EthereumAddress(
            hex: "0x4444444444444444444444444444444444444444"
        )
        let receiver = try EthereumAddress(
            hex: "0x3333333333333333333333333333333333333333"
        )
        let payer = try EthereumAddress(
            hex: "0x2222222222222222222222222222222222222222"
        )
        let transferTopic = Keccak256.hash(
            utf8: "Transfer(address,address,uint256)"
        ).hex
        let payerTopic = ABI.word(payer).hexString
        let receiverTopic = ABI.word(receiver).hexString
        let zeroTopic = "0x" + String(repeating: "00", count: 32)
        let unitWord = "0x" + String(repeating: "0", count: 63) + "1"

        let malformedBodies: [(String, (JSONRPCEthereumClient) async throws -> Void)] = [
            (
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                    + "\"number\":\"0xf\",\"hash\":\"\(blockHash)\"}}",
                { client in
                    _ = try await client.paymentEvidenceBlock(
                        at: 15,
                        includeTransactions: false
                    )
                }
            ),
            (
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
                    + "\"number\":\"0xf\",\"hash\":\"\(blockHash)\","
                    + "\"timestamp\":\"0x96\",\"transactions\":[{"
                    + "\"hash\":\"\(transactionHash)\",\"from\":\"\(payer.hex)\","
                    + "\"to\":\"\(receiver.hex)\",\"value\":\"0x1\","
                    + "\"blockNumber\":\"0xf\",\"transactionIndex\":\"0x0\"}]}}",
                { client in
                    _ = try await client.paymentEvidenceBlock(
                        at: 15,
                        includeTransactions: true
                    )
                }
            ),
            (
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[{"
                    + "\"address\":\"\(token.hex)\",\"transactionHash\":\"\(transactionHash)\","
                    + "\"blockNumber\":\"0xf\",\"logIndex\":\"0x0\","
                    + "\"topics\":["
                    + "\"\(transferTopic)\",\"\(payerTopic)\",\"\(receiverTopic)\"],"
                    + "\"data\":\"\(unitWord)\"}]}",
                { client in
                    _ = try await client.paymentEvidenceERC20Transfers(
                        token: token,
                        recipient: receiver,
                        blockNumber: 15
                    )
                }
            ),
            (
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[{"
                    + "\"address\":\"\(token.hex)\",\"transactionHash\":\"\(transactionHash)\","
                    + "\"blockNumber\":\"0xf\",\"blockHash\":\"\(blockHash)\","
                    + "\"logIndex\":\"0x0\",\"topics\":["
                    + "\"\(transferTopic)\",\"\(zeroTopic)\",\"\(receiverTopic)\"],"
                    + "\"data\":\"\(unitWord)\"}]}",
                { client in
                    _ = try await client.paymentEvidenceERC20Transfers(
                        token: token,
                        recipient: receiver,
                        blockNumber: 15
                    )
                }
            ),
        ]

        for (body, operation) in malformedBodies {
            let client = try JSONRPCEthereumClient(
                endpoint: URL(string: "https://rpc.example")!,
                transport: PaymentEvidenceQueueTransport([body])
            )
            do {
                try await operation(client)
                XCTFail("Expected malformed payment evidence response to fail")
            } catch {}
        }
    }
}
#endif
