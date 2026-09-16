/*
 * 阶段 4b — Task 6 / TR-6.2 辅助工具。
 *
 * 提供 8 色板（spec FR-1）→ Compose Color 的映射。
 *
 * 零知识纪律（spec NFR-1）：
 *   - 日志禁止打印 title/start_ts/end_ts 原文；
 *   - 本工具只导出颜色映射，与明文无关。
 */

package com.everything.eve.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 8 色板预设（spec FR-1）字段名 → Compose Color。
 *
 * 未识别字段名时回退主 primary（避免抛异常阻塞 UI）。
 *
 * 数值取 Material3 baseline 配色 + 中等饱和度，避免过亮或过暗。
 */
private val palette: Map<String, Color> = mapOf(
    "blue" to Color(0xFF1E88E5),
    "green" to Color(0xFF43A047),
    "red" to Color(0xFFE53935),
    "amber" to Color(0xFFFFB300),
    "violet" to Color(0xFF8E24AA),
    "pink" to Color(0xFFD81B60),
    "cyan" to Color(0xFF00ACC1),
    "slate" to Color(0xFF546E7A),
)

/**
 * 通过 color 字段取色（兜底主 primary）。
 *
 * @param name 8 色板字段名（blue/green/red/amber/violet/pink/cyan/slate）。
 */
@Composable
@ReadOnlyComposable
fun colorFor(name: String): Color =
    palette[name] ?: MaterialTheme.colorScheme.primary
