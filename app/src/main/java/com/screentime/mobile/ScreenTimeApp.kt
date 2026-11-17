package com.screentime.mobile

import android.app.Application

class ScreenTimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Scheduler.schedule(this)
    }
}
