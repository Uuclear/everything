// 物品编辑页（stage5-items / T6）
package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import com.everything.eve.data.item.Item
import com.everything.eve.items.isValidReceiptUrl
import com.everything.eve.items.normalizeTags
import com.everything.eve.items.warrantyUntilTs
import com.everything.eve.reminder.ReminderScheduler
import com.everything.eve.ui.components.ITEM_CATEGORIES
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(
    itemId: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("electronics") }
    var tagsRaw by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var purchaseDate by remember { mutableStateOf(System.currentTimeMillis().toString()) }
    var priceYuan by remember { mutableStateOf("0") }
    var warrantyDays by remember { mutableStateOf("365") }
    var receiptUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var createdTs by remember { mutableStateOf(0L) }
    var receiptError by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        if (itemId != null) {
            val e = ServiceLocator.itemsRepo.getById(itemId)
            if (e != null) {
                name = e.name
                category = e.category
                tagsRaw = e.tags_json
                brand = e.brand ?: ""
                model = e.model ?: ""
                serial = e.serial_no ?: ""
                purchaseDate = e.purchase_date.toString()
                priceYuan = (e.purchase_price_cents / 100.0).toString()
                warrantyDays = e.warranty_duration_days.toString()
                receiptUrl = e.receipt_url ?: ""
                note = e.note ?: ""
                location = e.location_text ?: ""
                createdTs = e.created_ts
            }
        }
    }

    val purchaseTs = purchaseDate.toLongOrNull() ?: 0L
    val duration = warrantyDays.toIntOrNull() ?: 0
    val warrantyUntil = warrantyUntilTs(purchaseTs, duration)
    val priceCents = ((priceYuan.toDoubleOrNull() ?: 0.0) * 100).toLong()
    val tags = normalizeTags(tagsRaw.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() })
    val receiptOk = receiptUrl.isBlank() || isValidReceiptUrl(receiptUrl)
    val canSave = name.isNotBlank() && receiptOk && tags.size <= 8

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (itemId == null) stringResource(R.string.items_new) else stringResource(R.string.item_edit)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.event_back_btn)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.item_editor_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_name"),
            )
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("dropdown_category"),
            ) {
                OutlinedTextField(
                    readOnly = true,
                    value = ITEM_CATEGORIES.firstOrNull { it.first == category }?.second ?: category,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.item_editor_category)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                    ITEM_CATEGORIES.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                category = value
                                categoryExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = tagsRaw,
                onValueChange = { tagsRaw = it },
                label = { Text("标签（逗号分隔，≤8）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("tags_input"),
            )
            OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("品牌") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("型号") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = serial, onValueChange = { serial = it }, label = { Text("序列号") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = purchaseDate,
                onValueChange = { purchaseDate = it },
                label = { Text("购买日（Unix ms）") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = priceYuan, onValueChange = { priceYuan = it }, label = { Text("价格（元）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = warrantyDays, onValueChange = { warrantyDays = it }, label = { Text("保修天数") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = warrantyUntil.toString(),
                onValueChange = {},
                readOnly = true,
                label = { Text("保修截止（自动）") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = receiptUrl,
                onValueChange = {
                    receiptUrl = it
                    receiptError = it.isNotBlank() && !isValidReceiptUrl(it)
                },
                label = { Text("发票链接 https://") },
                modifier = Modifier.fillMaxWidth(),
                isError = receiptError,
            )
            if (receiptError) {
                Text(stringResource(R.string.item_editor_receipt_invalid), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("位置") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    if (!canSave) return@Button
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val id = itemId ?: ServiceLocator.itemsRepo.newId()
                        val item = Item(
                            id = id,
                            name = name.trim(),
                            category = category,
                            tags = tags,
                            brand = brand.ifBlank { null },
                            model = model.ifBlank { null },
                            serial_no = serial.ifBlank { null },
                            purchase_date = purchaseTs,
                            purchase_price_cents = priceCents,
                            warranty_duration_days = duration,
                            warranty_until_ts = warrantyUntil,
                            receipt_url = receiptUrl.ifBlank { null },
                            note = note.ifBlank { null },
                            location_text = location.ifBlank { null },
                            created_ts = if (itemId == null) now else createdTs,
                            updated_ts = now,
                        )
                        ServiceLocator.itemsRepo.upsert(item)
                        ReminderScheduler.rebuildChain(ctx.applicationContext)
                        onDone()
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .testTag("btn_save"),
            ) {
                Text(stringResource(R.string.item_editor_save))
            }
        }
    }
}
