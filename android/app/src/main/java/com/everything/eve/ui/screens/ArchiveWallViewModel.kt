package com.everything.eve.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.everything.eve.ServiceLocator
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.identity.IdentityEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class ArchiveWallTile(
    val id: String,
    val module: String,
    val title: String,
    val subtitle: String?,
    val expiresOn: String?,
    val attachmentId: String?,
    val kindLabel: String,
    val last4: String?,
)

/** 证件墙：证件 + 财务卡片只读网格。 */
class ArchiveWallViewModel(app: Application) : AndroidViewModel(app) {
    private val identityRepo = ServiceLocator.identityRepo
    private val financeRepo = ServiceLocator.financeRepo
    private val attachmentRepo = ServiceLocator.attachmentRepo

    val tiles: Flow<List<ArchiveWallTile>> = combine(
        identityRepo.observeAll(),
        financeRepo.observeCards(),
    ) { identities, cards ->
        val idTiles = identities.filter { !it.deleted }.map { it.toTile() }
        val cardTiles = cards.filter { !it.deleted && !it.archived }.map { it.toTile() }
        idTiles + cardTiles
    }

    suspend fun loadPreview(attachmentId: String): Result<ByteArray> =
        attachmentRepo.download(attachmentId)

    private fun IdentityEntity.toTile() = ArchiveWallTile(
        id = id,
        module = "identity",
        title = title,
        subtitle = name,
        expiresOn = expiresOn,
        attachmentId = frontAttachmentId,
        kindLabel = kind,
        last4 = null,
    )

    private fun FinanceCardEntity.toTile() = ArchiveWallTile(
        id = id,
        module = "finance",
        title = name,
        subtitle = issuer,
        expiresOn = null,
        attachmentId = cardFaceAttachmentId,
        kindLabel = "card",
        last4 = last4,
    )
}
