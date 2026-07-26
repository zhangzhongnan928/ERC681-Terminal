package com.openpasskey.terminal.ui.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private enum class ColdLaunchDestination {
    CHOICE,
    DEMO,
    EXITING_DEMO,
}

/**
 * First production UI after a cold launch. It has no Context, persistence, ViewModel, model,
 * repository, wallet, RPC, or authentication dependency. Only the explicit live callback may
 * cross the live-runtime boundary.
 */
@Composable
internal fun ColdLaunchRoot(
    initiallyInDemo: Boolean = false,
    onEnterDemo: () -> Boolean,
    onExitDemo: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    var destination by remember {
        mutableStateOf(
            if (initiallyInDemo) {
                ColdLaunchDestination.DEMO
            } else {
                ColdLaunchDestination.CHOICE
            },
        )
    }

    when (destination) {
        ColdLaunchDestination.CHOICE -> ColdLaunchChoice(
            onOpenTerminal = onOpenTerminal,
            onOpenDemo = {
                if (onEnterDemo()) {
                    destination = ColdLaunchDestination.DEMO
                }
            },
        )
        ColdLaunchDestination.DEMO -> ReviewerDemoScreen(
            onClose = { destination = ColdLaunchDestination.EXITING_DEMO },
        )
        ColdLaunchDestination.EXITING_DEMO -> {
            LaunchedEffect(Unit) {
                onExitDemo()
                destination = ColdLaunchDestination.CHOICE
            }
        }
    }
}

@Composable
private fun ColdLaunchChoice(
    onOpenTerminal: () -> Unit,
    onOpenDemo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .testTag("cold_launch_choice"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.PointOfSale,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "OPK Terminal",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Merchant terminal setup",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Set up or open the live merchant terminal. This may use the device wallet, " +
                        "saved terminal configuration, and network connectivity.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onOpenTerminal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cold_launch_open_terminal"),
                ) {
                    Icon(Icons.Default.PointOfSale, contentDescription = null)
                    Text("  Set up / open terminal")
                }
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Explore OPK Terminal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Take a self-contained offline product tour before any wallet, storage, " +
                        "authentication, or network service is opened.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onOpenDemo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cold_launch_reviewer_demo"),
                ) {
                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null)
                    Text("  Explore offline product tour")
                }
                Text(
                    "This preview does not configure or authorize the terminal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.padding(top = 18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No live terminal component starts until you explicitly open the terminal.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
