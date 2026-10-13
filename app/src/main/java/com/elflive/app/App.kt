package com.elflive.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.elflive.app.core.Engine
import com.elflive.app.data.DataStoreStore
import com.elflive.app.data.HttpApi

class App : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(HttpApi(this), DataStoreStore(this))
        val channel = NotificationChannel(
            LiveService.CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

val android.content.Context.engine: Engine get() = (applicationContext as App).engine
