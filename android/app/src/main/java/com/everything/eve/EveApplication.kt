package com.everything.eve

import android.app.Application
import com.everything.eve.sync.SyncScheduler

class EveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        SyncScheduler.schedulePeriodic(this)
    }
}
