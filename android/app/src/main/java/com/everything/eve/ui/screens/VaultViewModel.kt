package com.everything.eve.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.data.RecordEntity
import com.everything.eve.sync.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NoteView(val id: String, val title: String, val body: String, val updatedAt: Long)

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    /** 已解锁时把本地密文记录解密为视图模型；锁定时为空。 */
    val notes = combine(ServiceLocator.auth.isUnlocked, ServiceLocator.repo.observeNotes()) { unlocked, records ->
        if (!unlocked) emptyList() else records.mapNotNull { it.toNoteView() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 最近一次成功同步时间（毫秒），顶栏展示“上次同步 HH:mm”。 */
    val lastSyncAt = ServiceLocator.repo.observeLastSuccessfulSync()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun createNote(title: String, body: String) {
        viewModelScope.launch {
            ServiceLocator.repo.createNote(title, body)
            SyncScheduler.requestCollectNow(getApplication())
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            try {
                ServiceLocator.repo.sync()
            } catch (_: Exception) {
                // 由周期任务兜底重试
            }
        }
    }

    private fun RecordEntity.toNoteView(): NoteView? =
        try {
            val (title, body) = ServiceLocator.repo.decryptNote(this)
            NoteView(id, title, body, updatedAt)
        } catch (_: Exception) {
            null
        }
}

/** 毫秒 → “HH:mm”；0/null 给空串由调用方兜底。 */
fun formatSyncTime(ms: Long?): String {
    if (ms == null || ms == 0L) return "未同步"
    return "上次同步 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
