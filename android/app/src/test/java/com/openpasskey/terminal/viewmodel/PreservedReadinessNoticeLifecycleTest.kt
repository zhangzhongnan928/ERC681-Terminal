package com.openpasskey.terminal.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreservedReadinessNoticeLifecycleTest {
    @Test
    fun `unrelated settings updates are not lifecycle events`() {
        val notice = PreservedReadinessNotice()
        notice.beginPreservation(NOTICE)

        // Admin lock/unlock and receipt saves rebuild generic state through load() without
        // invoking any lifecycle transition, so the notice must survive verbatim across any
        // number of such rebuilds. (RPC-endpoint and profile mutations intentionally
        // invalidate readiness instead; SettingsViewModelReadinessLifecycleTest drives the
        // production paths.)
        repeat(3) {
            assertEquals(NOTICE, notice.current)
        }
    }

    @Test
    fun `only fresh proof demotion or invalidation end the preserved window`() {
        val afterFreshProof = PreservedReadinessNotice()
        afterFreshProof.beginPreservation(NOTICE)
        afterFreshProof.endAfterFreshProof()
        assertNull(afterFreshProof.current)

        val afterDemotion = PreservedReadinessNotice()
        afterDemotion.beginPreservation(NOTICE)
        afterDemotion.endAfterDemotion()
        assertNull(afterDemotion.current)

        val afterInvalidation = PreservedReadinessNotice()
        afterInvalidation.beginPreservation(NOTICE)
        afterInvalidation.endAfterInvalidation()
        assertNull(afterInvalidation.current)
    }

    @Test
    fun `generic message updates cannot rewrite or hide checkout's banner`() {
        // The lockAdmin-shaped rebuild replaces only the generic message; the dedicated
        // notice field is untouched, so Checkout can never present an unrelated status
        // line as an RPC-staleness banner.
        val preserved = SettingsState(
            preservedReadinessNotice = NOTICE,
            message = NOTICE,
        )
        val locked = preserved.copy(
            message = "Admin/setup controls locked.",
            isError = false,
        )
        assertEquals(NOTICE, locked.preservedReadinessNotice)

        // A rebuilt state without preservation shows no banner even while a generic
        // message is present: the banner is driven only by the dedicated field.
        val rebuilt = SettingsState(message = "Receipt profile saved.")
        assertNull(rebuilt.preservedReadinessNotice)
    }

    private companion object {
        const val NOTICE = "Terminal is ready to create payments. The latest status " +
            "re-check could not reach the RPC provider; showing the last validated result."
    }
}
