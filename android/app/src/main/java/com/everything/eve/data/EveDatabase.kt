package com.everything.eve.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecordEntity::class], version = 1, exportSchema = false)
abstract class EveDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao

    companion object {
        fun build(context: Context): EveDatabase =
            Room.databaseBuilder(context, EveDatabase::class.java, "eve.db")
                .fallbackToDestructiveMigration() // 阶段 1 骨架；正式版改为显式迁移
                .build()
    }
}
