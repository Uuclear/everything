// 阶段 2b：附件 L1 元数据 JSON 与 Web / W0 spec 对齐（snake_case 为准）
package com.everything.eve.vault

import org.json.JSONObject

/**
 * 构造跨端附件元数据明文 JSON。
 *
 * 写入：`parent_ref_id`、`parent_module`（W0）；保留 `recordId` 供 Web finance 路径读取。
 */
fun attachmentMetadataJson(
    attachmentId: String,
    parentRefId: String,
    parentModule: String,
    mime: String,
    size: Long,
    sha256Hex: String,
    name: String,
    createdAtMs: Long? = null,
): String {
    val o = JSONObject()
        .put("id", attachmentId)
        .put("name", name.take(120))
        .put("mime", mime)
        .put("size", size)
        .put("sha256", sha256Hex)
        .put("parent_ref_id", parentRefId)
        .put("parent_module", parentModule)
        .put("recordId", parentRefId)
    if (createdAtMs != null) {
        o.put("created_at", createdAtMs)
    }
    return o.toString()
}

/** 从元数据 JSON 解析父记录 id（读多写一）。 */
fun parseAttachmentParentRefId(obj: Map<String, Any?>): String {
    return obj.stringOrNull("parent_ref_id")
        ?: obj.stringOrNull("parentRefId")
        ?: obj.stringOrNull("recordId")
        ?: ""
}

/** 从元数据 JSON 解析父模块（读多写一）。 */
fun parseAttachmentParentModule(obj: Map<String, Any?>, recordModuleFallback: String): String {
    return obj.stringOrNull("parent_module")
        ?: obj.stringOrNull("parentModule")
        ?: recordModuleFallback
}

internal fun Map<String, Any?>.stringOrNull(key: String): String? {
    val v = this[key] ?: return null
    return v as? String
}
