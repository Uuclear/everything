package com.everything.eve.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单键值同步状态表（v2 迁移新增）。
 * 目前仅记录 [KEY_LAST_SUCCESSFUL_SYNC]：最近一次成功完成推送+拉取的毫秒时间戳，
 * 顶栏据此展示“上次同步 HH:mm”。
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
) {
    companion object {
        const val KEY_LAST_SUCCESSFUL_SYNC = "last_successful_sync"
    }
}
