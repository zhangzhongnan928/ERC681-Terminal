package com.openpasskey.terminal.provisioning

import com.openpasskey.erc681.EvmAddress

data class TerminalProvisioningPayload(
    val chainId: Long,
    val vault: EvmAddress,
    val token: EvmAddress,
    val operator: EvmAddress,
)

/** Exact raw-ASCII formats shared with the merchant portal. Nothing is trimmed or URL-decoded. */
object TerminalProvisioningPayloadCodec {
    private val ADDRESS = "(0x[0-9a-fA-F]{40})"
    private val OPERATOR_PATTERN = Regex(
        "^opk-terminal:operator\\?v=1&address=$ADDRESS$",
    )
    private val PROVISIONING_PATTERN = Regex(
        "^opk-terminal:provision\\?v=1&chainId=([1-9][0-9]*)&vault=$ADDRESS&token=$ADDRESS&operator=$ADDRESS$",
    )

    fun parse(rawPayload: String): TerminalProvisioningPayload {
        require(rawPayload.all { it.code in 0x20..0x7e }) { "Provisioning QR must be raw ASCII" }
        val match = requireNotNull(PROVISIONING_PATTERN.matchEntire(rawPayload)) {
            "Not a canonical OPK terminal provisioning QR"
        }
        val chainId = match.groupValues[1].toLongOrNull()
        require(chainId != null && chainId > 0) { "Provisioning chain ID is outside the supported range" }
        val vault = nonZeroAddress(match.groupValues[2], "vault")
        val token = nonZeroAddress(match.groupValues[3], "token")
        val operator = nonZeroAddress(match.groupValues[4], "operator")
        return TerminalProvisioningPayload(chainId, vault, token, operator)
    }

    fun encodeOperatorPairing(address: String): String {
        val operator = EvmAddress.parse(address)
        require(!operator.isZero) { "Operator address must not be zero" }
        return "opk-terminal:operator?v=1&address=${operator.value}"
    }

    fun parseOperatorPairing(rawPayload: String): EvmAddress {
        require(rawPayload.all { it.code in 0x20..0x7e }) { "Operator pairing QR must be raw ASCII" }
        val match = requireNotNull(OPERATOR_PATTERN.matchEntire(rawPayload)) {
            "Not a canonical OPK terminal operator QR"
        }
        return nonZeroAddress(match.groupValues[1], "operator")
    }

    fun encodeProvisioning(
        chainId: Long,
        vault: String,
        token: String,
        operator: String,
    ): String {
        require(chainId > 0) { "Provisioning chain ID must be positive" }
        val canonicalVault = nonZeroAddress(vault, "vault")
        val canonicalToken = nonZeroAddress(token, "token")
        val canonicalOperator = nonZeroAddress(operator, "operator")
        return "opk-terminal:provision?v=1&chainId=$chainId&vault=${canonicalVault.value}" +
            "&token=${canonicalToken.value}&operator=${canonicalOperator.value}"
    }

    private fun nonZeroAddress(value: String, label: String): EvmAddress =
        EvmAddress.parse(value).also { require(!it.isZero) { "Provisioning $label address must not be zero" } }
}
