package com.btsi.swiftcabalpha

import android.Manifest
import android.content.pm.PackageManager
// import android.graphics.Color // For button visual feedback - Replaced with ContextCompat
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private enum class SelectionMode {
        PICKUP,
        DROPOFF
    }

    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var pickupLocationTextView: TextView
    private lateinit var dropoffLocationTextView: TextView
    private lateinit var confirmBookingButton: Button
    private lateinit var setPickupButton: Button
    private lateinit var setDropoffButton: Button

    private var pickupMarker: Marker? = null
    private var dropoffMarker: Marker? = null

    private var currentSelectionMode: SelectionMode = SelectionMode.PICKUP

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                getCurrentLocationAndSetupMap()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                getCurrentLocationAndSetupMap()
                Toast.makeText(this, "Precise location preferred for better experience.", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(this, "Location permission denied. Map functionality limited.", Toast.LENGTH_LONG).show()
                setupMapWithDefaultLocation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking)

        pickupLocationTextView = findViewById(R.id.pickupLocationTextView)
        dropoffLocationTextView = findViewById(R.id.dropoffLocationTextView)
        confirmBookingButton = findViewById(R.id.confirmBookingButton)
        setPickupButton = findViewById(R.id.setPickupButton)
        setDropoffButton = findViewById(R.id.setDropoffButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragmentContainer) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setPickupButton.setOnClickListener {
            currentSelectionMode = SelectionMode.PICKUP
            Toast.makeText(this, "Tap map to set Pickup Location", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }

        setDropoffButton.setOnClickListener {
            currentSelectionMode = SelectionMode.DROPOFF
            Toast.makeText(this, "Tap map to set Drop-off Location", Toast.LENGTH_SHORT).show()
            updateButtonStates()
        }

        confirmBookingButton.setOnClickListener {
            if (pickupMarker == null || dropoffMarker == null) {
                Toast.makeText(this, "Please select both pickup and drop-off locations.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Logic for confirming booking dito ilalagay
            val pickupLoc = pickupMarker!!.position
            val dropoffLoc = dropoffMarker!!.position
            Toast.makeText(this, "Booking: From ${pickupLoc.latitude},${pickupLoc.longitude} to ${dropoffLoc.latitude},${dropoffLoc.longitude}", Toast.LENGTH_LONG).show()
            // TODO: Implement actual booking logic
        }
        updateButtonStates()
    }

    private fun updateButtonStates() {
        setPickupButton.isEnabled = currentSelectionMode != SelectionMode.PICKUP
        setDropoffButton.isEnabled = currentSelectionMode != SelectionMode.DROPOFF

        if (currentSelectionMode == SelectionMode.PICKUP) {
            setPickupButton.setBackgroundColor(ContextCompat.getColor(this, R.color.my_button_active_color))
            setDropoffButton.setBackgroundColor(ContextCompat.getColor(this, R.color.my_button_inactive_color))
        } else {
            setDropoffButton.setBackgroundColor(ContextCompat.getColor(this, R.color.my_button_active_color))
            setPickupButton.setBackgroundColor(ContextCompat.getColor(this, R.color.my_button_inactive_color))
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        checkLocationPermissionAndSetupMap()

        mMap?.setOnMapClickListener { latLng ->
            when (currentSelectionMode) {
                SelectionMode.PICKUP -> {
                    pickupMarker?.remove() // Remove previous pickup marker
                    pickupMarker = mMap?.addMarker(MarkerOptions().position(latLng).title("Pickup Location"))
                    pickupLocationTextView.text = "Pickup: Lat: ${latLng.latitude.format(5)}, Lng: ${latLng.longitude.format(5)}"
                }
                SelectionMode.DROPOFF -> {
                    dropoffMarker?.remove() // Remove previous dropoff marker
                    dropoffMarker = mMap?.addMarker(MarkerOptions().position(latLng).title("Drop-off Location"))
                    dropoffLocationTextView.text = "Drop-off: Lat: ${latLng.latitude.format(5)}, Lng: ${latLng.longitude.format(5)}"
                }
            }
        }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    private fun checkLocationPermissionAndSetupMap() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                getCurrentLocationAndSetupMap()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Toast.makeText(this, "Location permission is needed to show current location.", Toast.LENGTH_LONG).show()
                locationPermissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
            else -> {
                locationPermissionRequest.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        }
    }

    private fun getCurrentLocationAndSetupMap() {
        try {
            mMap?.isMyLocationEnabled = true
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                    } else {
                        Toast.makeText(this, "Couldn\'t get current location. Showing default.", Toast.LENGTH_SHORT).show()
                        setupMapWithDefaultLocation()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error getting location. Showing default.", Toast.LENGTH_SHORT).show()
                    setupMapWithDefaultLocation()
                }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission error: ${e.message}", Toast.LENGTH_LONG).show()
            setupMapWithDefaultLocation()
        }
    }

    private fun setupMapWithDefaultLocation() {
        val defaultLocation = LatLng(37.4220, -122.0840) // Googleplex
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
    }
}
