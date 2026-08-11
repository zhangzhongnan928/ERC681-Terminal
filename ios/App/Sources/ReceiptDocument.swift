import Foundation

struct ReceiptDocument: Equatable, Identifiable, Sendable {
    let merchantName: String
    let merchantABN: String?
    let displayAmount: String
    let tokenSymbol: String
    let networkName: String
    let terminalAddress: String
    let paymentTransactionHash: String
    let receiptNumber: Int64
    let paidAtEpochSeconds: Int64
    let explorerURL: URL

    var id: String { "\(receiptNumber)|\(paymentTransactionHash.lowercased())" }
}

enum ReceiptFormatter {
    static let receiptWidth = 32
    static let merchantNameWidth = 24

    static func format(_ document: ReceiptDocument) -> String {
        let date = receiptDateFormatter().string(
            from: Date(timeIntervalSince1970: TimeInterval(document.paidAtEpochSeconds))
        )
        let amount = singleLine(document.displayAmount)
        let token = singleLine(document.tokenSymbol)
        let network = singleLine(document.networkName)
        let total = [amount, token].filter { !$0.isEmpty }.joined(separator: " ")
        let paid = "Paid: \(total)" + (network.isEmpty ? "" : " (\(network))")

        var lines = [String]()
        lines.append(String(repeating: "=", count: receiptWidth))
        lines.append(contentsOf: wrapped(singleLine(document.merchantName), width: merchantNameWidth)
            .map(centered))
        if let abn = document.merchantABN.map(singleLine), !abn.isEmpty {
            lines.append(centered("ABN \(abn)"))
        }
        lines.append(String(repeating: "=", count: receiptWidth))
        lines.append("")
        lines.append(centered("PAYMENT RECEIPT"))
        lines.append("")
        lines.append("Date (UTC): \(date)")
        lines.append("Receipt:  #\(document.receiptNumber)")
        lines.append("")
        lines.append(String(repeating: "-", count: receiptWidth))
        lines.append(contentsOf: twoColumns(left: "TOTAL", right: total))
        lines.append(String(repeating: "-", count: receiptWidth))
        lines.append("")
        lines.append(contentsOf: fitted(paid))
        lines.append(contentsOf: fitted("Terminal: \(abbreviate(document.terminalAddress))"))
        lines.append(contentsOf: fitted("Tx Hash:  \(abbreviate(document.paymentTransactionHash))"))
        lines.append("")
        lines.append(String(repeating: "=", count: receiptWidth))
        lines.append(centered("Powered by OPK"))
        lines.append(String(repeating: "=", count: receiptWidth))
        lines.append("")
        lines.append(centered("Scan for transaction details"))
        lines.append(document.explorerURL.absoluteString)
        return lines.joined(separator: "\n") + "\n"
    }

    static func abbreviate(_ value: String) -> String {
        let normalized = singleLine(value)
        guard displayWidth(normalized) > 12 else { return normalized }
        return displayPrefix(normalized, maximumWidth: 7)
            + "..."
            + displaySuffix(normalized, maximumWidth: 5)
    }

    static func displayWidth(_ value: String) -> Int {
        value.reduce(into: 0) { $0 += characterDisplayWidth($1) }
    }

    static func wrapped(_ value: String, width: Int = receiptWidth) -> [String] {
        precondition(width > 0)
        guard !value.isEmpty else { return [""] }
        var result = [String]()
        var current = ""
        var currentWidth = 0

        func flush() {
            guard !current.isEmpty else { return }
            result.append(current)
            current = ""
            currentWidth = 0
        }

        for word in value.split(separator: " ").map(String.init) {
            let wordWidth = displayWidth(word)
            if wordWidth > width {
                flush()
                let chunks = splitByDisplayWidth(word, maximumWidth: width)
                result.append(contentsOf: chunks.dropLast())
                current = chunks.last ?? ""
                currentWidth = displayWidth(current)
                continue
            }
            let separatorWidth = current.isEmpty ? 0 : 1
            if currentWidth + separatorWidth + wordWidth > width { flush() }
            if !current.isEmpty {
                current.append(" ")
                currentWidth += 1
            }
            current.append(word)
            currentWidth += wordWidth
        }
        flush()
        return result.isEmpty ? [""] : result
    }

    private static func receiptDateFormatter() -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "dd MMM yyyy  HH:mm"
        return formatter
    }

    private static func singleLine(_ value: String) -> String {
        value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    private static func centered(_ value: String) -> String {
        let width = displayWidth(value)
        guard width < receiptWidth else { return value }
        return String(repeating: " ", count: (receiptWidth - width) / 2) + value
    }

    private static func fitted(_ value: String) -> [String] {
        displayWidth(value) <= receiptWidth ? [value] : wrapped(singleLine(value))
    }

    private static func twoColumns(left: String, right: String) -> [String] {
        let leftWidth = displayWidth(left)
        let rightWidth = displayWidth(right)
        if leftWidth + rightWidth + 1 <= receiptWidth {
            return [left + String(
                repeating: " ",
                count: receiptWidth - leftWidth - rightWidth
            ) + right]
        }
        return [left] + wrapped(right).map { value in
            String(repeating: " ", count: max(0, receiptWidth - displayWidth(value))) + value
        }
    }

    private static func displayPrefix(_ value: String, maximumWidth: Int) -> String {
        var result = ""
        var width = 0
        for character in value {
            let next = characterDisplayWidth(character)
            if !result.isEmpty, width + next > maximumWidth { break }
            result.append(character)
            width += next
        }
        return result
    }

    private static func displaySuffix(_ value: String, maximumWidth: Int) -> String {
        var selected = [Character]()
        var width = 0
        for character in value.reversed() {
            let next = characterDisplayWidth(character)
            if !selected.isEmpty, width + next > maximumWidth { break }
            selected.append(character)
            width += next
        }
        return String(selected.reversed())
    }

    private static func splitByDisplayWidth(_ value: String, maximumWidth: Int) -> [String] {
        var result = [String]()
        var current = ""
        var width = 0
        for character in value {
            let next = characterDisplayWidth(character)
            if !current.isEmpty, width + next > maximumWidth {
                result.append(current)
                current = ""
                width = 0
            }
            current.append(character)
            width += next
        }
        if !current.isEmpty { result.append(current) }
        return result.isEmpty ? [""] : result
    }

    /// Swift Character iteration keeps extended grapheme clusters intact. Treat East Asian and
    /// emoji clusters conservatively as two columns so wrapping remains safe on narrow paper.
    private static func characterDisplayWidth(_ character: Character) -> Int {
        let scalars = character.unicodeScalars
        let isWide = scalars.contains { scalar in
            let value = scalar.value
            return (0x1100...0x115F).contains(value)
                || value == 0x2329 || value == 0x232A
                || (0x2E80...0xA4CF).contains(value)
                || (0xAC00...0xD7A3).contains(value)
                || (0xF900...0xFAFF).contains(value)
                || (0xFE10...0xFE19).contains(value)
                || (0xFE30...0xFE6F).contains(value)
                || (0xFF00...0xFF60).contains(value)
                || (0xFFE0...0xFFE6).contains(value)
                || (0x1F000...0x1FAFF).contains(value)
                || (0x2600...0x27BF).contains(value)
                || (0x20000...0x3FFFD).contains(value)
        }
        return isWide ? 2 : 1
    }
}
