package com.havok.arkremote

import android.app.Application

class ArkApplication : Application() {
    lateinit var store: MonitorStore
        private set
    lateinit var controller: ArkController
        private set

    override fun onCreate() {
        super.onCreate()
        store = MonitorStore(this)
        controller = ArkController(store)
    }
}
