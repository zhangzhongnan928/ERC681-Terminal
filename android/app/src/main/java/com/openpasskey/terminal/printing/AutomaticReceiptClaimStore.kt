package com.openpasskey.terminal.printing

enum class AutomaticReceiptClaimResult {
    CLAIMED,
    ALREADY_CLAIMED,
    PERSISTENCE_FAILED,
}

/** Durable uncertainty marker written before an automatic physical print can be submitted. */
interface AutomaticReceiptClaimStore {
    fun claims(): Set<String>
    fun claim(fingerprint: String): AutomaticReceiptClaimResult
    fun release(fingerprint: String): Boolean
    fun retainOnly(liveFingerprints: Set<String>): Boolean
}
