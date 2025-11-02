package com.echidna.app

import android.app.Application
import com.echidna.app.data.ControlStateRepository

class EchidnaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ControlStateRepository.initialize(this)
    }
}
