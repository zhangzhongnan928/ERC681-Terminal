package com.openpasskey.terminal

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.openpasskey.terminal.ui.demo.ColdLaunchRoot
import com.openpasskey.terminal.ui.navigation.AppNavigation
import com.openpasskey.terminal.ui.theme.OPKTerminalTheme
import com.openpasskey.terminal.viewmodel.InvoiceViewModel
import com.openpasskey.terminal.viewmodel.SettingsViewModel
import com.openpasskey.terminal.viewmodel.SettlementViewModel

internal enum class ProcessLaunchMode {
    UNDECIDED,
    DEMO,
    LIVE,
}

internal data class LiveTerminalStack(
    val invoiceViewModel: InvoiceViewModel,
    val settingsViewModel: SettingsViewModel,
    val settlementViewModel: SettlementViewModel,
)

internal fun interface LiveTerminalStackFactory {
    fun create(activity: FragmentActivity, app: OPKTerminalApp): LiveTerminalStack
}

private object DefaultLiveTerminalStackFactory : LiveTerminalStackFactory {
    override fun create(
        activity: FragmentActivity,
        app: OPKTerminalApp,
    ): LiveTerminalStack {
        val invoiceViewModel = ViewModelProvider(
            activity,
            InvoiceViewModel.Factory(
                app.invoiceRepository,
                app.chainConfig,
                app.receiptCoordinator,
            ),
        )[InvoiceViewModel::class.java]
        val settingsViewModel = ViewModelProvider(
            activity,
            SettingsViewModel.Factory(
                app.chainConfig,
                app.operatorWalletStore,
                app.adminPinStore,
                app.terminalProvisioner,
                app.terminalResetCoordinator,
                app.terminalLifecycleGate,
                app.rpcWorkCoordinator,
                app.rpcEndpointStore,
                app.rpcEndpointVerifier,
            ),
        )[SettingsViewModel::class.java]
        val settlementViewModel = ViewModelProvider(
            activity,
            SettlementViewModel.Factory(app.settlementRepository, app.chainConfig),
        )[SettlementViewModel::class.java]
        return LiveTerminalStack(
            invoiceViewModel = invoiceViewModel,
            settingsViewModel = settingsViewModel,
            settlementViewModel = settlementViewModel,
        )
    }
}

private object RetainedReviewerDemoSession

/**
 * The launcher presents a dependency-free choice before constructing the live terminal graph.
 *
 * Demo state is process-session-only and the process can move from demo to live, but never from
 * live back to demo. This prevents a reviewer demo from coexisting with Room, preferences,
 * keystore, RPC recovery, authentication, signing, or settlement-broadcast services.
 */
class MainActivity : FragmentActivity() {
    private var liveStack by mutableStateOf<LiveTerminalStack?>(null)
    private var ownsDemoSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ownsDemoSession =
            lastCustomNonConfigurationInstance === RetainedReviewerDemoSession &&
            processLaunchMode == ProcessLaunchMode.DEMO
        val app = application as OPKTerminalApp
        if (processLaunchMode == ProcessLaunchMode.LIVE) {
            activateLiveTerminal(app)
        }

        setContent {
            OPKTerminalTheme {
                val stack = liveStack
                if (stack == null) {
                    ColdLaunchRoot(
                        initiallyInDemo = ownsDemoSession,
                        onEnterDemo = ::enterDemoSession,
                        onExitDemo = ::exitDemoSession,
                        onOpenTerminal = { activateLiveTerminal(app) },
                    )
                } else {
                    AppNavigation(
                        invoiceViewModel = stack.invoiceViewModel,
                        settingsViewModel = stack.settingsViewModel,
                        settlementViewModel = stack.settlementViewModel,
                    )
                }
            }
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? =
        if (ownsDemoSession) {
            RetainedReviewerDemoSession
        } else {
            super.onRetainCustomNonConfigurationInstance()
        }

    override fun onStop() {
        liveStack?.settingsViewModel?.lockAdmin()
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            exitDemoSession()
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        liveStack?.settingsViewModel?.refreshOperatorStatusAutomatically()
    }

    private fun activateLiveTerminal(app: OPKTerminalApp) {
        if (liveStack != null || !commitLiveMode()) {
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val factory = liveStackFactoryOverride ?: DefaultLiveTerminalStackFactory
        val stack = factory.create(this, app)
        liveStack = stack
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            stack.settingsViewModel.refreshOperatorStatusAutomatically()
        }
    }

    private fun enterDemoSession(): Boolean {
        if (ownsDemoSession) {
            return true
        }
        val entered = commitDemoMode()
        if (entered) {
            ownsDemoSession = true
        }
        return entered
    }

    private fun exitDemoSession() {
        if (!ownsDemoSession) {
            return
        }
        ownsDemoSession = false
        releaseDemoMode()
    }

    internal companion object {
        private val processModeLock = Any()
        private var activeDemoSessions = 0

        @Volatile
        internal var processLaunchMode: ProcessLaunchMode = ProcessLaunchMode.UNDECIDED
            private set

        @Volatile
        private var liveStackFactoryOverride: LiveTerminalStackFactory? = null

        private fun commitDemoMode(): Boolean = synchronized(processModeLock) {
            if (processLaunchMode == ProcessLaunchMode.LIVE) {
                false
            } else {
                activeDemoSessions += 1
                processLaunchMode = ProcessLaunchMode.DEMO
                true
            }
        }

        private fun commitLiveMode(): Boolean = synchronized(processModeLock) {
            if (processLaunchMode == ProcessLaunchMode.DEMO || activeDemoSessions > 0) {
                false
            } else {
                processLaunchMode = ProcessLaunchMode.LIVE
                true
            }
        }

        private fun releaseDemoMode() = synchronized(processModeLock) {
            check(activeDemoSessions > 0) {
                "Cannot release a reviewer demo session that is not active."
            }
            activeDemoSessions -= 1
            if (activeDemoSessions == 0 && processLaunchMode == ProcessLaunchMode.DEMO) {
                processLaunchMode = ProcessLaunchMode.UNDECIDED
            }
        }

        internal fun setLiveStackFactoryForTesting(factory: LiveTerminalStackFactory?) {
            liveStackFactoryOverride = factory
        }

    }
}
