package com.openpasskey.terminal.viewmodel

import com.openpasskey.terminal.lifecycle.TerminalLifecycleGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSweepSettingsPolicyTest {
    @Test
    fun newSettingsStateDefaultsAutoSweepOff() {
        assertFalse(SettingsState().autoSweepEnabled)
        assertFalse(SettingsState().savingAutoSweepPreference)
    }

    @Test
    fun preferenceWriteRequiresTheCapturedAdminAuthorization() = runBlocking {
        val session = AdminSessionGate()
        val unlock = session.beginUnlock()
        assertTrue(session.completeUnlock(unlock))
        val authorization = requireNotNull(session.unlockedEpochOrNull())
        var writes = 0

        updateAutoSweepPreferenceExclusively(
            lifecycleGate = TerminalLifecycleGate(),
            commitWithAuthorization = { commit ->
                session.withAuthorization(authorization, commit)
            },
            update = {
                writes += 1
                true
            },
        )
        assertEquals(1, writes)

        session.lock()
        val rejected = runCatching {
            updateAutoSweepPreferenceExclusively(
                lifecycleGate = TerminalLifecycleGate(),
                commitWithAuthorization = { commit ->
                    session.withAuthorization(authorization, commit)
                },
                update = {
                    writes += 1
                    true
                },
            )
        }
        assertTrue(rejected.isFailure)
        assertEquals(1, writes)
    }
}
