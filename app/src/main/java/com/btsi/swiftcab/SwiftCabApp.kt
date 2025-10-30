package com.btsi.swiftcab

import android.app.Application
import com.btsi.swiftcab.R
import com.google.android.libraries.places.api.Places
import com.google.android.material.color.DynamicColors

class SwiftCabApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Enable Material You dynamic color across activities
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Initialize the Google Places SDK
        // val apiKey = getString(R.string.google_maps_key)
        // Places.initialize(applicationContext, apiKey)
    }
}
