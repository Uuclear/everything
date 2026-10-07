package com.everything.eve.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.everything.eve.ServiceLocator
import com.everything.eve.data.identity.IdentityEntity
import kotlinx.coroutines.flow.Flow

/** 证件列表 VM（只读观察 Room）。 */
class IdentitiesViewModel(app: Application) : AndroidViewModel(app) {
    val identities: Flow<List<IdentityEntity>> = ServiceLocator.identityRepo.observeAll()
}
