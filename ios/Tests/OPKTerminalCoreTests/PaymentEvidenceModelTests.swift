#if canImport(XCTest)
import Foundation
import XCTest
@testable import OPKTerminalCore

final class PaymentEvidenceModelTests: XCTestCase {
    func testRequestRequiresCanonicalFundingBracketAndRoundTrips() throws {
        let request = try fixtureRequest()
        let encoded = try JSONEncoder().encode(request)
        XCTAssertEqual(try JSONDecoder().decode(PaymentEvidenceRequest.self, from: encoded), request)

        XCTAssertThrowsError(
            try PaymentEvidenceRequest(
                chainID: request.chainID,
                receiver: request.receiver,
                asset: request.asset,
                expectedAmount: request.expectedAmount,
                publicationCursor: request.publicationCursor,
                fundingCursor: request.publicationCursor
            )
        ) { error in
            XCTAssertEqual(
                error as? PaymentEvidenceRequestError,
                .fundingDoesNotFollowPublication
            )
        }
    }

    func testIncomingEvidenceRoundTripsWithoutSettlementIdentity() throws {
        let request = try fixtureRequest()
        let evidence = try PaymentTransactionEvidence(
            transactionHash: try Bytes32(hex: "0x" + String(repeating: "ab", count: 32)),
            payer: try EthereumAddress(hex: "0x2222222222222222222222222222222222222222"),
            blockNumber: request.fundingCursor.blockNumber,
            blockHash: request.fundingCursor.blockHash,
            blockTimestamp: 1_750_000_000
        )
        let encoded = try JSONEncoder().encode(evidence)

        XCTAssertEqual(
            try JSONDecoder().decode(PaymentTransactionEvidence.self, from: encoded),
            evidence
        )
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        XCTAssertNil(object["settlementTransactionHash"])
        XCTAssertNil(object["sweepTransactionHash"])

        let zero = try EthereumAddress(hex: "0x" + String(repeating: "00", count: 20))
        XCTAssertThrowsError(
            try PaymentTransactionEvidence(
                transactionHash: evidence.transactionHash,
                payer: zero,
                blockNumber: evidence.blockNumber,
                blockHash: evidence.blockHash,
                blockTimestamp: evidence.blockTimestamp
            )
        ) { error in
            XCTAssertEqual(error as? PaymentTransactionEvidenceError, .zeroPayer)
        }
    }

    private func fixtureRequest() throws -> PaymentEvidenceRequest {
        try PaymentEvidenceRequest(
            chainID: 84_532,
            receiver: EthereumAddress(hex: "0x3333333333333333333333333333333333333333"),
            asset: EthereumAddress(hex: "0x4444444444444444444444444444444444444444"),
            expectedAmount: UInt256(100),
            publicationCursor: PaymentConfirmationCursor(
                blockNumber: 10,
                blockHash: Bytes32(data: Data(repeating: 0x55, count: 32))
            ),
            fundingCursor: PaymentConfirmationCursor(
                blockNumber: 20,
                blockHash: Bytes32(data: Data(repeating: 0x66, count: 32))
            )
        )
    }
}
#endif
