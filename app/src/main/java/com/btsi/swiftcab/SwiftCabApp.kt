package com.btsi.swiftcab

import android.app.Application
import com.btsi.swiftcab.R
import com.google.android.libraries.places.api.Places

class SwiftCabApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize the Google Places SDK
        val apiKey = getString(R.string.google_maps_key)
        Places.initialize(applicationContext, apiKey)
    }
}
