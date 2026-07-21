package com.openpasskey.erc681

data class PaymentInvoice(
    val invoiceId: InvoiceId,
    val vault: EvmAddress,
    val request: Erc681PaymentRequest,
) {
    init {
        require(!vault.isZero) { "Vault address must not be zero" }
    }

    val erc681Uri: String get() = Erc681Codec.encode(request)
}

object PaymentInvoiceFactory {
    @JvmStatic
    @JvmOverloads
    fun create(
        profile: PaymentProfile,
        amount: TokenAmount,
        terminalIdentifier: EvmAddress,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000,
        random: java.security.SecureRandom = java.security.SecureRandom(),
    ): PaymentInvoice {
        require(amount.decimals == profile.token.decimals) {
            "Amount decimals must match the selected payment profile token"
        }
        return create(
            profile.network,
            profile.token.address,
            amount,
            terminalIdentifier,
            timestampSeconds,
            random,
        )
    }

    @JvmStatic
    fun create(
        profile: PaymentProfile,
        amount: TokenAmount,
        invoiceId: InvoiceId,
    ): PaymentInvoice {
        require(amount.decimals == profile.token.decimals) {
            "Amount decimals must match the selected payment profile token"
        }
        return create(profile.network, profile.token.address, amount, invoiceId)
    }

    @JvmStatic
    @JvmOverloads
    fun create(
        network: NetworkConfig,
        token: EvmAddress,
        amount: TokenAmount,
        terminalIdentifier: EvmAddress,
        timestampSeconds: Long = System.currentTimeMillis() / 1_000,
        random: java.security.SecureRandom = java.security.SecureRandom(),
    ): PaymentInvoice {
        val invoiceId = InvoiceIdGenerator.generate(terminalIdentifier, timestampSeconds, random)
        return create(network, token, amount, invoiceId)
    }

    @JvmStatic
    fun create(
        network: NetworkConfig,
        token: EvmAddress,
        amount: TokenAmount,
        invoiceId: InvoiceId,
    ): PaymentInvoice {
        val receiver = network.receiverResolver.resolve(network.vault, invoiceId)
        return PaymentInvoice(
            invoiceId = invoiceId,
            vault = network.vault,
            request = Erc681PaymentRequest(token, network.chainId, receiver, amount),
        )
    }
}
