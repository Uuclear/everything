package com.everything.eve.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.R
import com.everything.eve.vault.ExpiryLevel
import com.everything.eve.vault.expiryLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveWallScreen(
    onBack: () -> Unit,
    onOpenIdentity: (String) -> Unit,
    onOpenFinance: () -> Unit,
    vm: ArchiveWallViewModel = viewModel(),
) {
    val tiles by vm.tiles.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archive_wall_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
        ) {
            items(tiles, key = { "${it.module}-${it.id}" }) { tile ->
                ArchiveTile(
                    tile = tile,
                    loadPreview = vm::loadPreview,
                    onClick = {
                        if (tile.module == "identity") onOpenIdentity(tile.id)
                        else onOpenFinance()
                    },
                )
            }
        }
    }
}

@Composable
private fun ArchiveTile(
    tile: ArchiveWallTile,
    loadPreview: suspend (String) -> Result<ByteArray>,
    onClick: () -> Unit,
) {
    var preview by remember(tile.attachmentId) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(tile.attachmentId) {
        preview = null
        val id = tile.attachmentId
        if (id != null) {
            preview = loadPreview(id).getOrNull()
        }
    }
    val barColor = when (expiryLevel(tile.expiresOn)) {
        ExpiryLevel.EXPIRED -> MaterialTheme.colorScheme.error
        ExpiryLevel.SOON -> MaterialTheme.colorScheme.tertiary
        ExpiryLevel.UPCOMING -> MaterialTheme.colorScheme.primary
        null -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .padding(6.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(barColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
            ) {
                val bytes = preview
                if (bytes != null) {
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                } else {
                    Text(
                        tile.kindLabel,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(tile.title, style = MaterialTheme.typography.titleSmall)
                tile.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                tile.last4?.let {
                    Text("•••• $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
