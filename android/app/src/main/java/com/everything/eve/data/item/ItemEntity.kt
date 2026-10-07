package com.everything.eve.data.item

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 物品明文表（阶段 5 items，v11 迁移新增）。
 *
 * 字段表与 [docs/module-schemas.md] 第 9 章 / Web `Item` 严格一致；
 * JSON 列表以 TEXT 序列化入列（`tags_json`）。
 *
 * 零知识红线：本表仅存 Vault DB 容器内明文；不进 SharedPreferences / 日志 /
 * 通知文案（通知仅渲染抽象短语，不渲染 name/价格/序列号）。
 */
@Entity(
    tableName = "item",
    indices = [
        Index(value = ["category"]),
        Index(value = ["updated_ts"]),
        Index(value = ["dirty"]),
    ],
)
data class ItemEntity(
    /** 物品 id（UUID v4）；兼作 records 主键与二维码 payload。 */
    @PrimaryKey val id: String,

    /** 物品名称（≤120 字符；表单校验非空）。 */
    val name: String,

    /** 一级分类：electronics/furniture/apparel/tools/books/other。 */
    val category: String,

    /** 二级标签数组 JSON；非 null；空列表 `"[]"`。 */
    @ColumnInfo(defaultValue = "[]") val tags_json: String,

    val brand: String?,
    val model: String?,
    val serial_no: String?,

    /** 购买日锚点，Unix 毫秒（本地日历日语义）。 */
    @ColumnInfo(defaultValue = "0") val purchase_date: Long,

    /** 购买价格，以分为单位的整数。 */
    @ColumnInfo(defaultValue = "0") val purchase_price_cents: Long,

    @ColumnInfo(defaultValue = "CNY") val currency: String,

    @ColumnInfo(defaultValue = "0") val warranty_duration_days: Int,

    /** 保修截止时刻，Unix 毫秒（由 purchase_date + 天数推导，编辑器只读）。 */
    @ColumnInfo(defaultValue = "0") val warranty_until_ts: Long,

    val receipt_url: String?,
    val note: String?,
    val location_text: String?,

    /** 本地脏标记（与 records 表 dirty 联动）。 */
    val dirty: Boolean,

    val created_ts: Long,
    val updated_ts: Long,
)
