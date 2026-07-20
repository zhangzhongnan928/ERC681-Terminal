import Foundation
import OPKTerminalCore

public enum SettlementABI {
    public static let ownerSelector = ABI.selector("owner()")
    public static let sweepSessionsSelector = ABI.selector("sweepSessions(bytes32[],uint256[],address)")
    public static let sweptEventTopic = Keccak256.hash(
        utf8: "Swept(address,address,bytes32,address,uint256,uint256,uint256)"
    )

    public static func encodeIsOperator(_ operatorAddress: EthereumAddress) -> Data {
        ABI.encodeCall(selector: ABI.isOperatorSelector, words: [ABI.word(operatorAddress)])
    }

    public static func encodeOwner() -> Data {
        ABI.encodeCall(selector: ownerSelector)
    }

    public static func encodeSweepSessions(_ intent: SettlementIntent) -> Data {
        let count = intent.sessions.count
        let firstTailOffset = UInt64(32 * 3)
        let secondTailOffset = firstTailOffset + UInt64(32 * (count + 1))

        var encoded = ABI.encodeCall(
            selector: sweepSessionsSelector,
            words: [
                ABI.word(firstTailOffset),
                ABI.word(secondTailOffset),
                ABI.word(intent.token),
            ]
        )
        encoded.append(ABI.word(UInt64(count)))
        for session in intent.sessions {
            encoded.append(ABI.word(session.invoiceID))
        }
        encoded.append(ABI.word(UInt64(count)))
        for session in intent.sessions {
            encoded.append(ABI.word(session.expectedAmount))
        }
        return encoded
    }

    public static func verifySweptEvents(
        receipt: EthereumTransactionReceipt,
        intent: SettlementIntent
    ) throws -> [VerifiedSweep] {
        var decoded = [DecodedSweptEvent]()
        for log in receipt.logs where log.address == intent.vault && log.topics.first == sweptEventTopic {
            guard !log.removed else {
                throw SettlementOperatorError.invalidReceipt("a matching log was marked removed")
            }
            guard let logTransactionHash = log.transactionHash,
                  let logBlockHash = log.blockHash,
                  let logIndex = log.logIndex
            else {
                throw SettlementOperatorError.invalidReceipt(
                    "a matching log was missing transaction hash, block hash, or log index"
                )
            }
            if logTransactionHash != receipt.transactionHash {
                throw SettlementOperatorError.transactionHashMismatch(
                    expected: receipt.transactionHash,
                    actual: logTransactionHash
                )
            }
            if logBlockHash != receipt.blockHash {
                throw SettlementOperatorError.invalidReceipt(
                    "Swept log block hash did not match its receipt"
                )
            }
            decoded.append(
                try decodeSweptEvent(log, logIndex: logIndex, blockHash: logBlockHash)
            )
        }

        return intent.sessions.compactMap { session in
            let matches = decoded.filter {
                $0.invoiceID == session.invoiceID
                    && $0.receiver == session.receiver
                    && $0.vault == intent.vault
                    && $0.token == intent.token
                    && $0.expectedAmount == session.expectedAmount
                    && !$0.sweptAmount.isZero
            }
            // A successful batch can legitimately produce only a subset of useful proofs
            // (for example, one receiver swept zero while another swept a positive balance).
            // Preserve each session's single positive, immutable-field-bound event. Missing,
            // zero-only, or ambiguous positive matches remain unproven and are reviewed by the
            // coordinator without discarding proofs belonging to other sessions.
            guard matches.count == 1, let event = matches.first else { return nil }
            return VerifiedSweep(
                invoiceID: event.invoiceID,
                receiver: event.receiver,
                token: event.token,
                sweptAmount: event.sweptAmount,
                expectedAmount: event.expectedAmount,
                fee: event.fee,
                logIndex: event.logIndex,
                blockHash: event.blockHash,
                transactionHash: receipt.transactionHash
            )
        }
    }

    private static func decodeSweptEvent(
        _ log: EthereumLog,
        logIndex: UInt64,
        blockHash: Bytes32
    ) throws -> DecodedSweptEvent {
        guard log.topics.count == 3, log.data.count == 32 * 5 else {
            throw SettlementOperatorError.invalidReceipt("malformed Swept log shape")
        }
        let receiver = try decodeIndexedAddress(log.topics[1])
        let vault = try decodeIndexedAddress(log.topics[2])
        let words = stride(from: 0, to: log.data.count, by: 32).map {
            Data(log.data[$0..<($0 + 32)])
        }
        let invoiceID = try Bytes32(data: words[0])
        guard words[1].prefix(12).allSatisfy({ $0 == 0 }) else {
            throw SettlementOperatorError.invalidReceipt("non-canonical token address word")
        }
        let token = try EthereumAddress(data: words[1].suffix(20), allowZero: false)
        return DecodedSweptEvent(
            receiver: receiver,
            vault: vault,
            invoiceID: invoiceID,
            token: token,
            sweptAmount: UInt256(bigEndian: words[2]),
            expectedAmount: UInt256(bigEndian: words[3]),
            fee: UInt256(bigEndian: words[4]),
            logIndex: logIndex,
            blockHash: blockHash
        )
    }

    private static func decodeIndexedAddress(_ topic: Bytes32) throws -> EthereumAddress {
        guard topic.data.prefix(12).allSatisfy({ $0 == 0 }) else {
            throw SettlementOperatorError.invalidReceipt("non-canonical indexed address")
        }
        return try EthereumAddress(data: topic.data.suffix(20), allowZero: false)
    }
}

private struct DecodedSweptEvent: Sendable {
    let receiver: EthereumAddress
    let vault: EthereumAddress
    let invoiceID: Bytes32
    let token: EthereumAddress
    let sweptAmount: UInt256
    let expectedAmount: UInt256
    let fee: UInt256
    let logIndex: UInt64
    let blockHash: Bytes32
}
