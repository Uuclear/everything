package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultHomeScreen(
    onBack: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenIdentities: () -> Unit,
    vm: VaultHomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_home_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(onClick = onOpenArchive) {
                        Text(stringResource(R.string.nav_archive_wall))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCard(
                title = stringResource(R.string.vault_home_identity_expiry),
                body = if (state.identityExpiry.isEmpty()) {
                    stringResource(R.string.vault_home_empty_identity)
                } else {
                    state.identityExpiry.take(5).joinToString("\n") { row ->
                        "${row.entity.title} · ${row.days} 天"
                    }
                },
                action = onOpenIdentities,
                actionLabel = stringResource(R.string.nav_identities),
            )
            SummaryCard(
                title = stringResource(R.string.vault_home_counts),
                body = stringResource(
                    R.string.vault_home_counts_format,
                    state.itemCount,
                    state.financeCardCount,
                ),
            )
            SummaryCard(
                title = stringResource(R.string.vault_home_location_today),
                body = when (val n = state.todayLocationPoints) {
                    null -> stringResource(R.string.vault_home_location_unknown)
                    0 -> stringResource(R.string.vault_home_location_empty)
                    else -> stringResource(R.string.vault_home_location_points_format, n)
                },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    body: String,
    action: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, modifier = Modifier.padding(top = 6.dp))
            if (action != null && actionLabel != null) {
                TextButton(onClick = action) { Text(actionLabel) }
            }
        }
    }
}
