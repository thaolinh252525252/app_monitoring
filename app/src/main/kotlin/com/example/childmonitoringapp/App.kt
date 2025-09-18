package com.example.childmonitoringapp

import android.app.Application
import android.content.Intent

class App : Application() {
    var resultCode: Int = android.app.Activity.RESULT_CANCELED
    var projectionData: Intent? = null

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}