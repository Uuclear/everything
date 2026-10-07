package com.everything.eve.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.collector.location.db.LocationDao
import com.everything.eve.vault.HOME_HORIZON_DAYS
import com.everything.eve.vault.IdentityExpiryRow
import com.everything.eve.vault.identitiesExpiringWithinDays
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.TimeZone

data class VaultHomeUiState(
    val identityExpiry: List<IdentityExpiryRow> = emptyList(),
    val itemCount: Int = 0,
    val financeCardCount: Int = 0,
    val todayLocationPoints: Int? = null,
)

/** 「今日与我」摘要 VM（只读本地投影）。 */
class VaultHomeViewModel(app: Application) : AndroidViewModel(app) {
    private val identityRepo = ServiceLocator.identityRepo
    private val itemsRepo = ServiceLocator.itemsRepo
    private val financeRepo = ServiceLocator.financeRepo
    private val locationDao: LocationDao = ServiceLocator.db.locationDao()

    private val dayStartTs = todayStartMs()

    val state: StateFlow<VaultHomeUiState> = combine(
        identityRepo.observeAll(),
        itemsRepo.observeAll(),
        financeRepo.observeCards(),
        locationDao.observeCountSince(dayStartTs),
    ) { identities, items, cards, locCount ->
        val expiry = identitiesExpiringWithinDays(
            identities.filter { !it.deleted },
            HOME_HORIZON_DAYS,
        )
        VaultHomeUiState(
            identityExpiry = expiry,
            itemCount = items.size,
            financeCardCount = cards.count { !it.deleted && !it.archived },
            todayLocationPoints = locCount,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultHomeUiState())

    private fun todayStartMs(): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
