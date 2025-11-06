package com.vaishnava.alarm

import android.app.Application
import com.vaishnava.alarm.data.AlarmRepository

class AlarmApplication : Application() {
    lateinit var repository: AlarmRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AlarmRepository(this)
        
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger initialization
        // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger.i call
    }
}
