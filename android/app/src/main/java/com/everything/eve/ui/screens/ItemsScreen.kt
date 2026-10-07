// 物品列表页（stage5-items / T6）
package com.everything.eve.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.R
import com.everything.eve.ui.components.CategoryChips

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenEditor: (String?) -> Unit,
    onOpenScan: () -> Unit,
    vm: ItemsViewModel = viewModel(),
) {
    val items by vm.filteredItems.collectAsState()
    var tagQuery by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(vm.selectedCategories()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.items_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.event_back_btn)) }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(
                    onClick = onOpenScan,
                    modifier = Modifier.testTag("fab_scan"),
                ) {
                    Text(stringResource(R.string.items_scan))
                }
                FloatingActionButton(
                    onClick = { onOpenEditor(null) },
                    modifier = Modifier.testTag("fab_new"),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.items_new))
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            CategoryChips(
                selected = selected.value,
                onToggle = {
                    vm.toggleCategory(it)
                    selected.value = vm.selectedCategories()
                },
            )
            OutlinedTextField(
                value = tagQuery,
                onValueChange = {
                    tagQuery = it
                    vm.setTagQuery(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_tags"),
                label = { Text(stringResource(R.string.items_search_tags)) },
                singleLine = true,
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.items_empty),
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("grid_items"),
                ) {
                    items(items, key = { it.id }) { entity ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenDetail(entity.id) }
                                .testTag("item_card_${entity.id}"),
                        ) {
                            Text(
                                text = entity.name,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
