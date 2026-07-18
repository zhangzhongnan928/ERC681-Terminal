package com.openpasskey.erc681

/** Locally reproduces SessionFactory.computeReceiver without trusting an RPC response. */
class Create2ReceiverResolver(
    val factory: EvmAddress,
    val receiverImplementation: EvmAddress,
) {
    init {
        require(!factory.isZero) { "Factory address must not be zero" }
        require(!receiverImplementation.isZero) { "Receiver implementation address must not be zero" }
    }

    fun resolve(vault: EvmAddress, invoiceId: InvoiceId): EvmAddress {
        require(!vault.isZero) { "Vault address must not be zero" }

        val salt = Keccak256.digest(abiEncode(vault, invoiceId))
        val initCodeHash = Keccak256.digest(initCode(vault))
        val preimage = ByteArray(1 + 20 + 32 + 32)
        preimage[0] = 0xff.toByte()
        factory.toByteArray().copyInto(preimage, destinationOffset = 1)
        salt.copyInto(preimage, destinationOffset = 21)
        initCodeHash.copyInto(preimage, destinationOffset = 53)
        val hash = Keccak256.digest(preimage)
        return EvmAddress.fromBytes(hash.copyOfRange(12, 32))
    }

    fun initCode(vault: EvmAddress): ByteArray {
        require(!vault.isZero) { "Vault address must not be zero" }
        val code = ByteArray(INIT_CODE_SIZE)
        var offset = 0
        CREATION_PREFIX.copyInto(code, destinationOffset = offset)
        offset += CREATION_PREFIX.size
        RUNTIME_PREFIX.copyInto(code, destinationOffset = offset)
        offset += RUNTIME_PREFIX.size
        receiverImplementation.toByteArray().copyInto(code, destinationOffset = offset)
        offset += 20
        RUNTIME_SUFFIX.copyInto(code, destinationOffset = offset)
        offset += RUNTIME_SUFFIX.size
        vault.toByteArray().copyInto(code, destinationOffset = offset + 12)
        return code
    }

    private fun abiEncode(vault: EvmAddress, invoiceId: InvoiceId): ByteArray = ByteArray(64).also { encoded ->
        vault.toByteArray().copyInto(encoded, destinationOffset = 12)
        invoiceId.toByteArray().copyInto(encoded, destinationOffset = 32)
    }

    companion object {
        const val INIT_CODE_SIZE: Int = 88

        private val CREATION_PREFIX = Hex.decode("0x604d80600b6000396000f3")
        private val RUNTIME_PREFIX = Hex.decode("0x363d3d373d3d3d363d73")
        private val RUNTIME_SUFFIX = Hex.decode("0x5af43d82803e903d91602b57fd5bf3")
    }
}
