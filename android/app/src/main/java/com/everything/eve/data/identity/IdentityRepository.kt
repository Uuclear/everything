package com.everything.eve.data.identity

import com.everything.eve.api.RemoteRecord
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 证件仓库：identity 明文表 + records 密文双写（与 ItemsRepository 同款）。
 */
class IdentityRepository(
    private val identityDao: IdentityDao,
    private val recordsRepository: RecordsRepository,
) {
    fun observeAll(): Flow<List<IdentityEntity>> = identityDao.observeAll()

    suspend fun getById(id: String): IdentityEntity? = identityDao.getById(id)

    suspend fun upsert(identity: Identity): String {
        recordsRepository.upsertIdentityRecord(identity.id, identity.kind, identity.toJson())
        identityDao.upsert(identity.toEntity(dirty = true))
        return identity.id
    }

    suspend fun delete(id: String, kind: String) {
        recordsRepository.deleteIdentityRecord(id, kind)
        identityDao.markDeleted(id, System.currentTimeMillis())
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** 拉取 module=identity 下行并解密入 Room。 */
    suspend fun pullAndDecrypt(sinceMs: Long): Int {
        val api = com.everything.eve.ServiceLocator.api
        var cursor = if (sinceMs < 0) 0 else sinceMs
        var ingested = 0
        while (true) {
            val page = api.listRecords(cursor, 500)
            if (page.records.isEmpty()) break
            for (remote in page.records) {
                if (remote.module != MODULE_IDENTITY) continue
                ingestOneRemote(remote)
                ingested += 1
            }
            val identityRecords = page.records.filter { it.module == MODULE_IDENTITY }
            if (identityRecords.isNotEmpty()) {
                cursor = identityRecords.maxOf { it.updatedAt }
            }
            if (!page.hasMore) break
        }
        return ingested
    }

    private suspend fun ingestOneRemote(remote: RemoteRecord) {
        if (remote.deleted) {
            identityDao.markDeleted(remote.id, System.currentTimeMillis())
            return
        }
        val tmp = RecordEntity(
            id = remote.id,
            module = remote.module,
            type = remote.type,
            ciphertext = remote.ciphertext,
            version = remote.version.toLong(),
            createdAt = remote.createdAt,
            updatedAt = remote.updatedAt,
            deleted = false,
        )
        val plain = recordsRepository.decryptIdentityRecord(tmp)
        val identity = Identity.fromJson(
            remote.id,
            plain,
            remote.createdAt,
            remote.updatedAt,
        )
        identityDao.upsert(identity.toEntity(dirty = false))
    }

    companion object {
        const val MODULE_IDENTITY: String = "identity"
    }
}
