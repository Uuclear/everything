// 物品详情页（stage5-items / T6）
package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import com.everything.eve.data.item.ItemEntity
import com.everything.eve.ui.components.ItemQrCard
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    vm: ItemsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    var entity by remember { mutableStateOf<ItemEntity?>(null) }
    var showDelete by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        entity = ServiceLocator.itemsRepo.getById(itemId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entity?.name ?: "…") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.event_back_btn)) }
                },
            )
        },
    ) { padding ->
        val e = entity
        if (e == null) {
            Text("加载中…", modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("分类: ${e.category}")
                    Text("品牌: ${e.brand ?: "-"}")
                    Text("型号: ${e.model ?: "-"}")
                    Text("序列号: ${e.serial_no ?: "-"}")
                    Text("保修: ${e.warranty_duration_days} 天")
                    Text("截止: ${e.warranty_until_ts}")
                    Text("标签: ${formatTags(e.tags_json)}")
                }
            }
            ItemQrCard(itemId = e.id, modifier = Modifier.padding(top = 16.dp))
            Button(
                onClick = onEdit,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("btn_edit"),
            ) {
                Text(stringResource(R.string.item_edit))
            }
            Button(
                onClick = { showDelete = true },
                modifier = Modifier.testTag("btn_delete"),
            ) {
                Text(stringResource(R.string.item_delete))
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.item_delete)) },
            text = { Text(stringResource(R.string.item_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteItem(itemId, onDeleted)
                        showDelete = false
                    },
                    modifier = Modifier.testTag("btn_delete_confirm"),
                ) { Text(stringResource(R.string.item_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

private fun formatTags(json: String): String =
    try {
        val arr = JSONArray(json)
        (0 until arr.length()).joinToString(", ") { arr.getString(it) }
    } catch (_: Exception) {
        ""
    }
