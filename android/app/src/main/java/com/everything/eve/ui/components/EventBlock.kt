/*
 * 阶段 4b — Task 6 / TR-6.2：EventBlock 组件。
 *
 * 设计要点：
 *   1. 单文件组件，无 ViewModel 依赖；纯展示 + onClick。
 *   2. 8 色板预设（spec FR-1）：按 color 字段取主题不依赖 Color 类的硬编码值；
 *      由 ColorTokens 映射。绝不渲染 start_ts/end_ts 原始数字给日志（spec NFR-1）。
 *   3. all_day=true 时用 chip 标"全天"+ 不显示具体时刻。
 *   4. 月视图事件块最多展示 3 个，超出走"more"链接（MoreIndicator 由调用方布局）。
 *
 * 零知识：onClick 回调只回传 entity.id，不回传 title 原文明文供日志打印。
 */

package com.everything.eve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.data.event.EventEntity
import com.everything.eve.ui.util.colorFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 月/周视图通用的事件块（TR-6.2）。
 *
 * 渲染策略：
 *   - 全天事件（all_day=true）：仅显示"全天"chip + title；不显示 start_ts/end_ts 时间。
 *   - 非全天：显示 "HH:mm–HH:mm" + title（单行，省略号截断）。
 *
 * @param entity 待展示的事件。
 * @param onClick 点击回调，回传 entity.id（不携带 title 原文明文，NFR-1 零知识）。
 * @param modifier 父布局修饰。
 */
@Composable
fun EventBlock(
    entity: EventEntity,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = colorFor(entity.color).copy(alpha = 0.18f)
    val stripe = colorFor(entity.color)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(entity.id) }
            .semantics { contentDescription = eventBlockSemantic(entity) },
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧 4dp 色条（spec FR-1 "按 color 着色"）。
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxWidth()
                    .background(stripe),
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (entity.all_day) {
                    Text(
                        text = stringResourceId(R.string.event_block_all_day_chip),
                        style = MaterialTheme.typography.labelSmall,
                        color = stripe,
                    )
                } else {
                    Text(
                        text = formatTimeRange(entity.start_ts, entity.end_ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = stripe,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * "还有 N 项"行（超出 3 项时由 MonthGrid 调用）。
 */
@Composable
fun MoreIndicator(
    remaining: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResourceFormat(R.string.event_block_more, remaining),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * 把 HH:mm–HH:mm 渲染出来（仅时间，不含日期）。复用 SimpleDateFormat 维持 Locale 一致。
 *
 * 注：此为展示用格式化；事件编辑保存对 Editable 时间仍由 UI 层 DatePicker/TimePicker 接管。
 */
private fun formatTimeRange(startTs: Long, endTs: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${fmt.format(Date(startTs))}–${fmt.format(Date(endTs))}"
}

/**
 * EventBlock 的无障碍 contentDescription：仅渲染 id（不打印 title 明文进日志）。
 *
 * 测试友好：测试用例通过 onContentDescription 找不到 title 字面值以校验零知识。
 */
private fun eventBlockSemantic(entity: EventEntity): String =
    "event_block_${entity.id}_${if (entity.all_day) "allday" else "timed"}"

// -- 内部小工具（避免文件间循环 import，包装 android R.string 用） --

@Composable
private fun stringResourceId(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

@Composable
private fun stringResourceFormat(resId: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(resId, *args)

// 占位：未使用的导入抑制（unit test 用）
@Suppress("unused") private val _palette_lookup_check: Color = Color(0xFF000000)
