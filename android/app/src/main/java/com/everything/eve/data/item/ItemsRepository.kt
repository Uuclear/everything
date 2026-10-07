package com.everything.eve.data.item

import com.everything.eve.api.RemoteRecord
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 物品领域仓库（阶段 5 items Task 4 / TR-4.4）。
 *
 * 编排「明文 Item → Room item 表 + RecordsRepository.upsertItemRule」，
 * 与 [com.everything.eve.data.event.EventsRepository] 同款双写。
 */
class ItemsRepository(
    private val itemDao: ItemDao,
    private val recordsRepository: RecordsRepository,
) {

    /** 新建/编辑物品：records 密文 + 本地 item 明文，均标 dirty。 */
    suspend fun upsert(item: Item): String {
        val json = item.toJson()
        recordsRepository.upsertItemRule(item.id, json)
        itemDao.upsert(item.toEntity(dirty = true))
        return item.id
    }

    /** 删除物品：records tombstone + 本地 item 表清除。 */
    suspend fun delete(id: String) {
        recordsRepository.deleteItemRule(id)
        itemDao.deleteById(id)
    }

    fun observeAll(): Flow<List<ItemEntity>> = itemDao.observeAll()

    fun observeByCategory(category: String): Flow<List<ItemEntity>> =
        itemDao.observeByCategory(category)

    suspend fun dirtyList(): List<ItemEntity> = itemDao.dirtyList()

    suspend fun getById(id: String): ItemEntity? = itemDao.getById(id)

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 从服务端拉取 module=item/type=item，解密后 upsert 到本地 item 表。
     */
    suspend fun pullAndDecrypt(sinceMs: Long): Int {
        val api = com.everything.eve.ServiceLocator.api
        var cursor = if (sinceMs < 0) 0 else sinceMs
        var ingested = 0
        while (true) {
            val page = api.listRecords(cursor, 500)
            if (page.records.isEmpty()) break
            for (remote in page.records) {
                if (remote.module != "item") continue
                if (remote.type != "item") continue
                ingestOneRemote(remote)
                ingested += 1
            }
            val itemRecords = page.records.filter { it.module == "item" && it.type == "item" }
            if (itemRecords.isNotEmpty()) {
                cursor = itemRecords.maxOf { it.updatedAt }
            }
            if (!page.hasMore) break
        }
        return ingested
    }

    private suspend fun ingestOneRemote(remote: RemoteRecord) {
        if (remote.deleted) {
            itemDao.deleteById(remote.id)
            return
        }
        val tmpEntity = RecordEntity(
            id = remote.id,
            module = remote.module,
            type = remote.type,
            ciphertext = remote.ciphertext,
            version = remote.version.toLong(),
            createdAt = remote.createdAt,
            updatedAt = remote.updatedAt,
            deleted = false,
        )
        val plainJson = recordsRepository.decryptItemRule(tmpEntity)
        val item = Item.fromJson(plainJson)
        itemDao.upsert(item.toEntity(dirty = false))
    }
}

/** 物品明文形态（与 module-schemas.md 第 9 章 / Web Item 逐字段一致）。 */
data class Item(
    val id: String,
    val name: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val brand: String? = null,
    val model: String? = null,
    val serial_no: String? = null,
    val purchase_date: Long,
    val purchase_price_cents: Long,
    val currency: String = "CNY",
    val warranty_duration_days: Int,
    val warranty_until_ts: Long,
    val receipt_url: String? = null,
    val note: String? = null,
    val location_text: String? = null,
    val created_ts: Long,
    val updated_ts: Long,
) {
    fun toJson(): String =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("category", category)
            .put("tags", JSONArray(tags))
            .put("brand", brand)
            .put("model", model)
            .put("serial_no", serial_no)
            .put("purchase_date", purchase_date)
            .put("purchase_price_cents", purchase_price_cents)
            .put("currency", currency)
            .put("warranty_duration_days", warranty_duration_days)
            .put("warranty_until_ts", warranty_until_ts)
            .put("receipt_url", receipt_url)
            .put("note", note)
            .put("location_text", location_text)
            .put("created_ts", created_ts)
            .put("updated_ts", updated_ts)
            .toString()

    fun toEntity(dirty: Boolean): ItemEntity = ItemEntity(
        id = id,
        name = name,
        category = category,
        tags_json = JSONArray(tags).toString().ifEmpty { "[]" },
        brand = brand,
        model = model,
        serial_no = serial_no,
        purchase_date = purchase_date,
        purchase_price_cents = purchase_price_cents,
        currency = currency,
        warranty_duration_days = warranty_duration_days,
        warranty_until_ts = warranty_until_ts,
        receipt_url = receipt_url,
        note = note,
        location_text = location_text,
        dirty = dirty,
        created_ts = created_ts,
        updated_ts = updated_ts,
    )

    companion object {
        fun fromJson(json: String): Item {
            val o = JSONObject(json)
            val tagsArr = o.optJSONArray("tags")
            val tagsList = (0 until (tagsArr?.length() ?: 0))
                .map { tagsArr!!.getString(it) }
            return Item(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.getString("category"),
                tags = tagsList,
                brand = o.optString("brand").takeIf { it.isNotEmpty() },
                model = o.optString("model").takeIf { it.isNotEmpty() },
                serial_no = o.optString("serial_no").takeIf { it.isNotEmpty() },
                purchase_date = o.getLong("purchase_date"),
                purchase_price_cents = o.getLong("purchase_price_cents"),
                currency = o.optString("currency", "CNY"),
                warranty_duration_days = o.getInt("warranty_duration_days"),
                warranty_until_ts = o.getLong("warranty_until_ts"),
                receipt_url = o.optString("receipt_url").takeIf { it.isNotEmpty() },
                note = o.optString("note").takeIf { it.isNotEmpty() },
                location_text = o.optString("location_text").takeIf { it.isNotEmpty() },
                created_ts = o.getLong("created_ts"),
                updated_ts = o.getLong("updated_ts"),
            )
        }
    }
}
