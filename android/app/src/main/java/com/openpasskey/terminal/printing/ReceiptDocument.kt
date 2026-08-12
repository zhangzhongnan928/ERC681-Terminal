package com.openpasskey.terminal.printing

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ReceiptDocument(
    val merchantName: String,
    val merchantAbn: String? = null,
    val displayAmount: String,
    val tokenSymbol: String,
    val networkName: String,
    val terminalAddress: String,
    val paymentTxHash: String,
    val receiptNumber: Long,
    val paidAtEpochSeconds: Long,
    val explorerUrl: String,
)

internal data class ReceiptPrintContent(
    val merchantLines: List<String>,
    val merchantAbn: String?,
    val metadataLines: List<String>,
    val totalLines: List<String>,
    val paidLines: List<String>,
    val terminalLines: List<String>,
    val transactionLines: List<String>,
    val explorerUrl: String,
)

object ReceiptFormatter {
    private const val RECEIPT_WIDTH = 32
    // At 28 px, 32 ASCII columns can exceed the Swift 2's 384-dot printable width.
    private const val MERCHANT_NAME_WIDTH = 24
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val EMOJI_VARIATION_SELECTOR = 0xFE0F
    private val dateFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm", Locale.ENGLISH)

    /**
     * [zoneId] is retained for source compatibility with existing printer integrations. Receipt
     * timestamps are always rendered in UTC so a later timezone change cannot alter a reprint.
     */
    @Suppress("UNUSED_PARAMETER")
    fun format(document: ReceiptDocument, zoneId: ZoneId): String {
        val content = printContent(document)
        val lines = mutableListOf<String>()
        lines += content.merchantLines.map { it.centered() }
        content.merchantAbn?.let { lines += "ABN $it".centered() }
        lines += "PAYMENT RECEIPT".centered()
        lines += content.metadataLines
        lines += content.totalLines
        lines += content.paidLines
        lines += content.terminalLines
        lines += content.transactionLines
        lines += "Powered by OpenPasskey".centered()
        lines += "Scan for transaction details".centered()

        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    internal fun printContent(document: ReceiptDocument): ReceiptPrintContent {
        val date = dateFormatter.withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(document.paidAtEpochSeconds))
        val amount = document.displayAmount.toSingleLine()
        val token = document.tokenSymbol.toSingleLine()
        val network = document.networkName.toSingleLine()
        val total = listOf(amount, token).filter(String::isNotBlank).joinToString(" ")
        val paid = buildString {
            append("Paid: ")
            append(total)
            if (network.isNotBlank()) append(" ($network)")
        }
        return ReceiptPrintContent(
            merchantLines = document.merchantName.toSingleLine()
                .wrappedLines(MERCHANT_NAME_WIDTH),
            merchantAbn = document.merchantAbn
                ?.toSingleLine()
                ?.takeIf(String::isNotBlank),
            metadataLines = twoColumns("Date (UTC):", date) +
                twoColumns("Receipt:", "#${document.receiptNumber}"),
            totalLines = twoColumns("TOTAL", total),
            paidLines = paid.fittedLines(),
            terminalLines = "Terminal: ${abbreviate(document.terminalAddress)}".fittedLines(),
            transactionLines = "Tx Hash:  ${abbreviate(document.paymentTxHash)}".fittedLines(),
            explorerUrl = document.explorerUrl.toUrlLine(),
        )
    }

    private fun twoColumns(left: String, right: String): List<String> {
        val leftWidth = left.displayWidth()
        val rightWidth = right.displayWidth()
        if (leftWidth + rightWidth + 1 <= RECEIPT_WIDTH) {
            return listOf(left + " ".repeat(RECEIPT_WIDTH - leftWidth - rightWidth) + right)
        }
        return listOf(left) + right.wrappedLines().map { it.padStartDisplay(RECEIPT_WIDTH) }
    }

    private fun abbreviate(value: String): String {
        val normalized = value.toSingleLine()
        return if (normalized.displayWidth() > 12) {
            normalized.takeDisplayPrefix(7) + "..." + normalized.takeDisplaySuffix(5)
        } else {
            normalized
        }
    }

    private fun String.toSingleLine(): String =
        trim().replace(Regex("\\s+"), " ")

    private fun String.toUrlLine(): String =
        trim().replace(Regex("[\\r\\n\\t]+"), "")

    private fun String.centered(): String {
        val width = displayWidth()
        if (width >= RECEIPT_WIDTH) return this
        return " ".repeat((RECEIPT_WIDTH - width) / 2) + this
    }

    private fun String.fittedLines(): List<String> =
        if (displayWidth() <= RECEIPT_WIDTH) listOf(this) else toSingleLine().wrappedLines()

    private fun String.wrappedLines(maxWidth: Int = RECEIPT_WIDTH): List<String> {
        require(maxWidth > 0) { "Receipt line width must be positive" }
        if (isEmpty()) return listOf("")

        val result = mutableListOf<String>()
        var current = StringBuilder()
        var currentWidth = 0

        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current = StringBuilder()
                currentWidth = 0
            }
        }

        split(' ').filter(String::isNotEmpty).forEach { word ->
            val wordWidth = word.displayWidth()
            if (wordWidth > maxWidth) {
                flush()
                val chunks = word.splitByDisplayWidth(maxWidth)
                chunks.dropLast(1).forEach { result += it }
                current.append(chunks.last())
                currentWidth = chunks.last().displayWidth()
            } else {
                val separatorWidth = if (current.isEmpty()) 0 else 1
                if (currentWidth + separatorWidth + wordWidth > maxWidth) flush()
                if (current.isNotEmpty()) {
                    current.append(' ')
                    currentWidth += 1
                }
                current.append(word)
                currentWidth += wordWidth
            }
        }
        flush()

        return result.ifEmpty { listOf("") }
    }

    internal fun displayWidth(value: String): Int = value.displayWidth()

    private fun String.displayWidth(): Int = displayUnits().sumOf(DisplayUnit::width)

    private fun String.padStartDisplay(targetWidth: Int): String {
        val padding = targetWidth - displayWidth()
        return if (padding > 0) " ".repeat(padding) + this else this
    }

    private fun String.takeDisplayPrefix(maxWidth: Int): String {
        val result = StringBuilder()
        var width = 0
        for (unit in displayUnits()) {
            if (result.isNotEmpty() && width + unit.width > maxWidth) break
            result.append(unit.text)
            width += unit.width
        }
        return result.toString()
    }

    private fun String.takeDisplaySuffix(maxWidth: Int): String {
        val selected = mutableListOf<DisplayUnit>()
        var width = 0
        for (unit in displayUnits().asReversed()) {
            if (selected.isNotEmpty() && width + unit.width > maxWidth) break
            selected += unit
            width += unit.width
        }
        return selected.asReversed().joinToString(separator = "", transform = DisplayUnit::text)
    }

    private fun String.splitByDisplayWidth(maxWidth: Int): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var currentWidth = 0

        displayUnits().forEach { unit ->
            if (current.isNotEmpty() && currentWidth + unit.width > maxWidth) {
                chunks += current.toString()
                current = StringBuilder()
                currentWidth = 0
            }
            current.append(unit.text)
            currentWidth += unit.width
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.ifEmpty { listOf("") }
    }

    /** A display unit keeps a code point and its common combining or joined emoji suffixes intact. */
    private data class DisplayUnit(val text: String, val width: Int)

    private fun String.displayUnits(): List<DisplayUnit> {
        val result = mutableListOf<DisplayUnit>()
        var index = 0
        while (index < length) {
            val start = index
            val first = Character.codePointAt(this, index)
            index += Character.charCount(first)

            // Two regional indicators form one flag glyph.
            if (isRegionalIndicator(first) && index < length) {
                val next = Character.codePointAt(this, index)
                if (isRegionalIndicator(next)) index += Character.charCount(next)
            }
            index = consumeDisplayExtenders(index)

            // Keep common emoji ZWJ sequences in one unit so wrapping cannot corrupt the glyph.
            while (index < length && Character.codePointAt(this, index) == ZERO_WIDTH_JOINER) {
                index += Character.charCount(ZERO_WIDTH_JOINER)
                if (index >= length) break
                val joined = Character.codePointAt(this, index)
                index += Character.charCount(joined)
                index = consumeDisplayExtenders(index)
            }

            val text = substring(start, index)
            var width = 0
            var hasEmojiPresentation = false
            var unitIndex = 0
            while (unitIndex < text.length) {
                val codePoint = Character.codePointAt(text, unitIndex)
                width += codePointDisplayWidth(codePoint)
                hasEmojiPresentation = hasEmojiPresentation ||
                    codePoint == EMOJI_VARIATION_SELECTOR || isEmojiCodePoint(codePoint)
                unitIndex += Character.charCount(codePoint)
            }
            result += DisplayUnit(
                text = text,
                width = if (hasEmojiPresentation) maxOf(2, width) else width,
            )
        }
        return result
    }

    private fun String.consumeDisplayExtenders(startIndex: Int): Int {
        var index = startIndex
        while (index < length) {
            val codePoint = Character.codePointAt(this, index)
            if (!isDisplayExtender(codePoint)) break
            index += Character.charCount(codePoint)
        }
        return index
    }

    private fun codePointDisplayWidth(codePoint: Int): Int = when {
        isDisplayExtender(codePoint) || codePoint == ZERO_WIDTH_JOINER -> 0
        isWideCodePoint(codePoint) || isEmojiCodePoint(codePoint) -> 2
        else -> 1
    }

    private fun isDisplayExtender(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean =
        codePoint in 0x1F1E6..0x1F1FF

    private fun isEmojiCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x2600..0x27BF

    /** Conservative subset of Unicode East Asian Wide and Fullwidth ranges. */
    private fun isWideCodePoint(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint == 0x2329 || codePoint == 0x232A ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x20000..0x3FFFD
}
