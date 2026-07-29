// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Victor Zhang

package com.openpasskey.erc681

/** ERC-20 metadata verified against the configured EVM network. */
data class PaymentTokenConfig(
    val address: EvmAddress,
    val symbol: String,
    val decimals: Int,
) {
    init {
        require(!address.isZero) { "Payment token address must not be zero" }
        require(symbol.isNotBlank()) { "Payment token symbol must not be blank" }
        require(decimals in 0..255) { "Payment token decimals must be between 0 and 255" }
    }
}

/**
 * A complete, immutable payment choice. One invoice uses exactly one profile; a catalog simply
 * lets a terminal choose between currencies, vaults, and EVM networks before creating it.
 */
data class PaymentProfile(
    val network: NetworkConfig,
    val token: PaymentTokenConfig,
) {
    val id: String = id(network.chainId, network.vault, token.address)

    companion object {
        @JvmStatic
        fun id(chainId: Long, vault: EvmAddress, token: EvmAddress): String =
            "eip155:$chainId:${vault.value.lowercase()}:${token.value.lowercase()}"
    }
}

/** Immutable profile catalog with explicit selection and deterministic upsert semantics. */
data class PaymentProfileCatalog(
    val profiles: List<PaymentProfile>,
    val selectedProfileId: String? = profiles.firstOrNull()?.id,
) {
    init {
        require(profiles.size <= MAX_PROFILES) {
            "Payment profile catalog cannot contain more than $MAX_PROFILES profiles"
        }
        require(profiles.map(PaymentProfile::id).distinct().size == profiles.size) {
            "Payment profile IDs must be unique"
        }
        require(profiles.isEmpty() || selectedProfileId != null) {
            "A non-empty payment profile catalog must select one profile"
        }
        require(selectedProfileId == null || profiles.any { it.id == selectedProfileId }) {
            "Selected payment profile is not in the catalog"
        }
        require(profiles.isNotEmpty() || selectedProfileId == null) {
            "An empty payment profile catalog cannot have a selection"
        }
    }

    val selected: PaymentProfile?
        get() = selectedProfileId?.let { id -> profiles.firstOrNull { it.id == id } }

    fun selecting(profileId: String): PaymentProfileCatalog = copy(selectedProfileId = profileId)

    /** Replaces an exact chain/vault/token profile or appends a new one, then selects it. */
    fun upserting(profile: PaymentProfile): PaymentProfileCatalog {
        val index = profiles.indexOfFirst { it.id == profile.id }
        val updated = if (index < 0) profiles + profile else profiles.toMutableList().also {
            it[index] = profile
        }
        return PaymentProfileCatalog(updated, profile.id)
    }

    /** Removes a future checkout route while leaving invoice snapshots to the host app. */
    fun removing(profileId: String): PaymentProfileCatalog {
        require(profiles.any { it.id == profileId }) {
            "Payment profile is not in the catalog"
        }
        val updated = profiles.filterNot { it.id == profileId }
        val nextSelection = selectedProfileId
            ?.takeIf { selected -> updated.any { it.id == selected } }
            ?: updated.firstOrNull()?.id
        return PaymentProfileCatalog(updated, nextSelection)
    }

    companion object {
        const val MAX_PROFILES = 32
    }
}
