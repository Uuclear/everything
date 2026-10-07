// 物品列表 ViewModel（stage5-items / T6）
package com.everything.eve.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.data.item.ItemEntity
import com.everything.eve.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

class ItemsViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app.applicationContext
    private val selectedCategories = MutableStateFlow<Set<String>>(emptySet())
    private val tagQuery = MutableStateFlow("")

    val allItems: StateFlow<List<ItemEntity>> =
        ServiceLocator.itemsRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredItems: StateFlow<List<ItemEntity>> = combine(
        allItems,
        selectedCategories,
        tagQuery,
    ) { items, cats, query ->
        items.filter { entity ->
            val catOk = cats.isEmpty() || entity.category in cats
            val tagOk = query.isBlank() ||
                parseTags(entity.tags_json).any { it.contains(query, ignoreCase = true) }
            catOk && tagOk
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTagQuery(q: String) {
        tagQuery.value = q
    }

    fun selectedCategories(): Set<String> = selectedCategories.value

    /** 切换分类；传 __all__ 清空筛选。 */
    fun toggleCategory(value: String) {
        if (value == "__all__") {
            selectedCategories.value = emptySet()
            return
        }
        val cur = selectedCategories.value.toMutableSet()
        if (value in cur) cur.remove(value) else cur.add(value)
        selectedCategories.value = cur
    }

    fun deleteItem(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            ServiceLocator.itemsRepo.delete(id)
            ReminderScheduler.rebuildChain(appCtx)
            onDone()
        }
    }

    private fun parseTags(json: String): List<String> =
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
}
