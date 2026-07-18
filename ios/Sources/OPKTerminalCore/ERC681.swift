import Foundation

public enum ERC681Error: Error, Equatable, Sendable {
    case invalidScheme
    case invalidTarget
    case missingChainID
    case invalidChainID
    case wrongFunction
    case invalidQuery
    case nativeValueNotSupported
    case unexpectedParameter(String)
    case duplicateParameter(String)
    case nonCanonicalAmount
}

public struct ERC681TransferRequest: Hashable, Sendable, Codable {
    public let token: EthereumAddress
    public let chainID: UInt64
    public let recipient: EthereumAddress
    public let amount: UInt256

    public init(
        token: EthereumAddress,
        chainID: UInt64,
        recipient: EthereumAddress,
        amount: UInt256
    ) throws {
        guard !token.isZero, !recipient.isZero else { throw FixedBytesError.zeroAddress }
        guard chainID > 0 else { throw ERC681Error.invalidChainID }
        guard !amount.isZero else { throw ERC681Error.nonCanonicalAmount }
        self.token = token
        self.chainID = chainID
        self.recipient = recipient
        self.amount = amount
    }

    public var canonicalString: String {
        "ethereum:\(token.hex)@\(chainID)/transfer?address=\(recipient.hex)&uint256=\(amount.decimalString)"
    }

    public static func parse(
        _ value: String,
        expectedChainID: UInt64? = nil
    ) throws -> ERC681TransferRequest {
        guard value.hasPrefix("ethereum:") else { throw ERC681Error.invalidScheme }
        let body = value.dropFirst("ethereum:".count)
        guard !body.hasPrefix("pay-") else { throw ERC681Error.nativeValueNotSupported }
        guard let slash = body.firstIndex(of: "/") else { throw ERC681Error.wrongFunction }
        let targetAndChain = body[..<slash]
        let functionAndQuery = body[body.index(after: slash)...]

        guard let at = targetAndChain.lastIndex(of: "@") else { throw ERC681Error.missingChainID }
        let targetText = String(targetAndChain[..<at])
        let chainText = String(targetAndChain[targetAndChain.index(after: at)...])
        guard !chainText.isEmpty,
              chainText.allSatisfy({ $0 >= "0" && $0 <= "9" }),
              !chainText.hasPrefix("0") || chainText == "0",
              let chainID = UInt64(chainText),
              chainID > 0
        else { throw ERC681Error.invalidChainID }
        if let expectedChainID, chainID != expectedChainID {
            throw ERC681Error.invalidChainID
        }

        let token: EthereumAddress
        do {
            token = try EthereumAddress(hex: targetText, allowZero: false)
        } catch {
            throw ERC681Error.invalidTarget
        }

        guard let question = functionAndQuery.firstIndex(of: "?") else {
            throw ERC681Error.invalidQuery
        }
        let function = functionAndQuery[..<question]
        guard function == "transfer" else { throw ERC681Error.wrongFunction }
        let query = functionAndQuery[functionAndQuery.index(after: question)...]
        guard !query.isEmpty else { throw ERC681Error.invalidQuery }

        let fields = query.split(separator: "&", omittingEmptySubsequences: false)
        guard fields.count == 2 else { throw ERC681Error.invalidQuery }
        var parsed = [(key: String, value: String)]()
        for field in fields {
            let pieces = field.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard pieces.count == 2, !pieces[0].isEmpty, !pieces[1].isEmpty else {
                throw ERC681Error.invalidQuery
            }
            let key = String(pieces[0])
            if key == "value" { throw ERC681Error.nativeValueNotSupported }
            guard key == "address" || key == "uint256" else {
                throw ERC681Error.unexpectedParameter(key)
            }
            if parsed.contains(where: { $0.key == key }) {
                throw ERC681Error.duplicateParameter(key)
            }
            parsed.append((key, String(pieces[1])))
        }
        guard parsed[0].key == "address", parsed[1].key == "uint256" else {
            throw ERC681Error.invalidQuery
        }
        let recipientText = parsed[0].value
        let amountText = parsed[1].value

        let recipient: EthereumAddress
        do {
            recipient = try EthereumAddress(hex: recipientText, allowZero: false)
        } catch {
            throw ERC681Error.invalidTarget
        }
        guard amountText.allSatisfy({ $0 >= "0" && $0 <= "9" }),
              !amountText.isEmpty,
              amountText != "0",
              !amountText.hasPrefix("0")
        else { throw ERC681Error.nonCanonicalAmount }
        let amount: UInt256
        do {
            amount = try UInt256(decimalString: amountText)
        } catch {
            throw ERC681Error.nonCanonicalAmount
        }
        return try ERC681TransferRequest(
            token: token,
            chainID: chainID,
            recipient: recipient,
            amount: amount
        )
    }
}
