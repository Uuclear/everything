package com.everything.eve.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import com.everything.eve.vault.expiryDays
import com.everything.eve.vault.expiryLevel
import com.everything.eve.vault.ExpiryLevel
import androidx.compose.material3.MaterialTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitiesScreen(
    onBack: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    vm: IdentitiesViewModel = viewModel(),
) {
    val list by vm.identities.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identities_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenEditor(null) }) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            items(list, key = { it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onOpenEditor(item.id) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.title)
                        Text(item.kind)
                        if (!item.expiresOn.isNullOrBlank()) {
                            val days = expiryDays(item.expiresOn)
                            val color = when (expiryLevel(item.expiresOn)) {
                                ExpiryLevel.EXPIRED -> MaterialTheme.colorScheme.error
                                ExpiryLevel.SOON -> MaterialTheme.colorScheme.tertiary
                                ExpiryLevel.UPCOMING -> MaterialTheme.colorScheme.primary
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = if (days != null && days >= 0) {
                                    "${item.expiresOn} · ${days} 天"
                                } else {
                                    item.expiresOn
                                },
                                color = color,
                            )
                        }
                    }
                }
            }
        }
    }
}
