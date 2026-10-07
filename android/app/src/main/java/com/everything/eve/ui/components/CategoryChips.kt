// 物品一级分类筛选 Chips（stage5-items / T6）
package com.everything.eve.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 六种一级分类 + 展示名（与 module-schemas / Web 对齐）。 */
val ITEM_CATEGORIES: List<Pair<String, String>> = listOf(
    "electronics" to "电子设备",
    "furniture" to "家具",
    "apparel" to "服饰",
    "tools" to "工具",
    "books" to "图书",
    "other" to "其他",
)

/**
 * 多选分类筛选；空集合表示「全部」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChips(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = { onToggle("__all__") },
            label = { Text("全部") },
            modifier = Modifier.testTag("category_chip_all"),
        )
        for ((value, label) in ITEM_CATEGORIES) {
            FilterChip(
                selected = value in selected,
                onClick = { onToggle(value) },
                label = { Text(label) },
                modifier = Modifier.testTag("category_chip_$value"),
            )
        }
    }
}
