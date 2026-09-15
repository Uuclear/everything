package com.everything.eve.data

import com.everything.eve.api.BatchRequest
import com.everything.eve.api.RemoteRecord
import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

/**
 * 离线优先资料库：本地 Room 为唯一读来源；写入先落本地（dirty），
 * 再由 SyncWorker 推送并增量拉取。
 */
class RecordsRepository(
    private val dao: RecordDao,
    private val auth: AuthManager,
) {
    private val api get() = com.everything.eve.ServiceLocator.api

    fun observeNotes(): Flow<List<RecordEntity>> = dao.observeByModule(MODULE_NOTE)

    /** 新建一条加密安全笔记。返回记录 id。 */
    suspend fun createNote(title: String, body: String): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val plaintext = JSONObject()
            .put("title", title)
            .put("body", body)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(mk, plaintext, id, MODULE_NOTE, 1)
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = id,
                    module = MODULE_NOTE,
                    type = "secure_note",
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    dirty = true,
                ),
            ),
        )
        return id
    }

    /** 本地解密（MK 仅内存）。 */
    fun decryptNote(entity: RecordEntity): Pair<String, String> {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() } ?: error("资料库未解锁")
        val plain = CryptoEnvelope.openRecord(
            mk,
            CryptoEnvelope.unb64(entity.ciphertext),
            entity.id,
            entity.module,
            entity.version,
        )
        val obj = JSONObject(String(plain, Charsets.UTF_8))
        return obj.optString("title") to obj.optString("body")
    }

    /** WorkManager 调用：推脏数据 + 增量拉取。 */
    suspend fun sync() {
        if (!auth.isLoggedIn) return

        val dirty = dao.dirtyRecords()
        if (dirty.isNotEmpty()) {
            val remote = dirty.map {
                RemoteRecord(
                    id = it.id,
                    module = it.module,
                    type = it.type,
                    ciphertext = it.ciphertext,
                    version = it.version,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    deleted = it.deleted,
                )
            }
            // 单批 ≤1000 与服务端约束保持一致
            remote.chunked(1000).forEach { chunk ->
                val result = api.pushRecords(BatchRequest(chunk))
                if (result.applied + result.skipped == chunk.size) {
                    dao.markClean(chunk.map { it.id })
                }
            }
        }

        var since = dao.maxUpdatedAt()
        // 首次全量拉取；之后按 updated_at 游标翻页。
        while (true) {
            val resp = api.listRecords(since, 500)
            if (resp.records.isEmpty()) break
            dao.upsertAll(
                resp.records.map { r ->
                    RecordEntity(
                        id = r.id,
                        module = r.module,
                        type = r.type,
                        ciphertext = r.ciphertext,
                        version = r.version,
                        createdAt = r.createdAt,
                        updatedAt = r.updatedAt,
                        deleted = r.deleted,
                        dirty = false,
                    )
                },
            )
            since = resp.records.maxOf { it.updatedAt }
            if (!resp.hasMore) break
        }
    }

    companion object {
        const val MODULE_NOTE = "note"
    }
}
