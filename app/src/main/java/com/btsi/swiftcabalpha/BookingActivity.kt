package com.btsi.swiftcabalpha

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.semantics.setText
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import java.io.IOException
import java.util.Locale

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private companion object {
        private const val TAG = "BookingActivity"
        private const val DEFAULT_ZOOM = 15f
        private val PHILIPPINES_BOUNDS = RectangularBounds.newInstance(
            LatLng(4.0, 116.0), // SW corner
            LatLng(21.0, 127.0)  // NE corner
        )
    }

    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder

    private lateinit var pickupLocationEditText: EditText
    private lateinit var destinationEditText: EditText

    private lateinit var buttonConfirmBooking: Button
    private lateinit var buttonCurrentLocationPickup: ImageView
    private lateinit var buttonCurrentLocationDropoff: ImageView
    private lateinit var backButton: ImageView
    private lateinit var buttonSelectPickupMode: Button
    private lateinit var buttonSelectDropoffMode: Button


    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var pickupLocationLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null

    private var isPickupMode = true // true for pickup, false for dropoff

    private lateinit var progressBarWait: ProgressBar
    private lateinit var noDriversLayout: LinearLayout
    private lateinit var buttonShowOnMap: Button
    private lateinit var textViewNoDriversMessage: TextView
    private lateinit var mainBookingLayout: androidx.constraintlayout.widget.ConstraintLayout

    // Variables for the "Finding Driver" overlay
    private lateinit var findingDriverOverlayLayout: LinearLayout
    private lateinit var progressBarInFindingOverlay: ProgressBar
    private lateinit var textViewInFindingOverlay: TextView

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
                    permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                Log.d(TAG, "Location permission granted after request.")
                enableMyLocationOnMap()
            }
            else -> {
                Log.d(TAG, "Location permission denied after request.")
                Toast.makeText(this, "Location permission denied. Map functionality limited.", Toast.LENGTH_LONG).show()
                setupMapWithDefaultLocation()
            }
        }
    }

    private fun getBitmapDescriptorFromVector(context: Context, @DrawableRes vectorResId: Int): BitmapDescriptor? {
        return ContextCompat.getDrawable(context, vectorResId)?.let { vectorDrawable ->
            vectorDrawable.setBounds(0, 0, vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight)
            val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            vectorDrawable.draw(canvas)
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking)

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key), Locale.getDefault())
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragmentContainerView) as SupportMapFragment
        mapFragment.getMapAsync(this)

        findingDriverOverlayLayout = findViewById(R.id.findingDriverLayout)
        progressBarInFindingOverlay = findViewById(R.id.progressBarFindingDriver)
        textViewInFindingOverlay = findViewById(R.id.textViewFindingDriver)

        pickupLocationEditText = findViewById(R.id.pickupLocationEditText)
        destinationEditText = findViewById(R.id.destinationEditText)
        buttonConfirmBooking = findViewById(R.id.buttonConfirmBooking)
        buttonCurrentLocationPickup = findViewById(R.id.buttonCurrentLocationPickup)
        buttonCurrentLocationDropoff = findViewById(R.id.buttonCurrentLocationDropoff)
        backButton = findViewById(R.id.back_button_booking)

        buttonSelectPickupMode = findViewById(R.id.buttonSelectPickupMode)
        buttonSelectDropoffMode = findViewById(R.id.buttonSelectDropoffMode)

        progressBarWait = findViewById(R.id.progressBarWait)
        noDriversLayout = findViewById(R.id.noDriversLayout)
        buttonShowOnMap = findViewById(R.id.buttonShowOnMap)
        textViewNoDriversMessage = findViewById(R.id.textViewNoDriversMessage)
        mainBookingLayout = findViewById(R.id.mainBookingLayout)

        setupUI()
        checkLocationPermissionAndSetupMap()
    }

    @SuppressLint("PotentialBehaviorOverride")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        Log.d(TAG, "Map is ready.")

        mMap?.uiSettings?.isZoomControlsEnabled = true
        mMap?.uiSettings?.isMyLocationButtonEnabled = false

        mMap?.setOnMapClickListener { latLng ->
            val title = if (isPickupMode) "Tapped Pickup Location" else "Tapped Destination Location"
            updateMapLocation(latLng, title, isPickupMode)
        }
        mMap?.setOnMarkerClickListener { marker ->
            true
        }
        enableMyLocationOnMap()
    }

    private fun setupUI() {
        setupAutocompleteFragments()

        pickupLocationEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) isPickupMode = true
        }
        destinationEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) isPickupMode = false
        }

        buttonCurrentLocationPickup.setOnClickListener {
            getCurrentLocationAndSet(true)
        }
        buttonCurrentLocationDropoff.setOnClickListener {
            getCurrentLocationAndSet(false)
        }

        buttonSelectPickupMode.setOnClickListener {
            isPickupMode = true
            val pickupAutocompleteFragment =
                supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment
            pickupAutocompleteFragment.view?.findViewById<EditText>(com.google.android.libraries.places.R.id.places_autocomplete_search_input)?.requestFocus()
            Toast.makeText(this, "Tap on map to set PICKUP location or search above.", Toast.LENGTH_SHORT).show()
        }

        buttonSelectDropoffMode.setOnClickListener {
            isPickupMode = false
            val destinationAutocompleteFragment =
                supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
            destinationAutocompleteFragment.view?.findViewById<EditText>(com.google.android.libraries.places.R.id.places_autocomplete_search_input)?.requestFocus()
            Toast.makeText(this, "Tap on map to set DESTINATION location or search above.", Toast.LENGTH_SHORT).show()
        }

        buttonConfirmBooking.setOnClickListener {
            Log.d(TAG, "buttonConfirmBooking clicked")
            if (pickupLocationLatLng != null && destinationLatLng != null) {
                Log.d(TAG, "Confirm booking pressed. Pickup: $pickupLocationLatLng, Dest: $destinationLatLng")
                findingDriverOverlayLayout.visibility = View.VISIBLE
                Log.d(TAG, "findingDriverOverlayLayout visibility set to VISIBLE")
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Handler.postDelayed: START")
                    Log.d(TAG, "Simulated: Driver search complete. Found DriverXYZ.")
                    Log.d(TAG, "Simulated: Notifying DriverXYZ.")
                    Log.d(TAG, "Handler.postDelayed: Attempting to hide findingDriverOverlayLayout")
                    findingDriverOverlayLayout.visibility = View.GONE
                    Log.d(TAG, "Handler.postDelayed: findingDriverOverlayLayout visibility set to GONE")
                    Toast.makeText(this@BookingActivity, "Driver notified! Waiting for confirmation. (Simulation)", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Handler.postDelayed: Toast shown")
                    Log.d(TAG, "Handler.postDelayed: END")
                }, 4000)
            } else {
                Toast.makeText(this@BookingActivity, "Please select both pickup and destination locations.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Confirm booking attempt with missing location data. Pickup is null: ${pickupLocationLatLng == null}, Destination is null: ${destinationLatLng == null}")
            }
        }

        backButton.setOnClickListener {
            finish()
        }

        progressBarWait.visibility = View.GONE
        noDriversLayout.visibility = View.GONE
    }

    private fun setupAutocompleteFragments() {
        val pickupAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment
        val destinationAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment

        pickupAutocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
        pickupAutocompleteFragment.setHint("Enter Pickup Location")
        pickupAutocompleteFragment.setCountry("PH")
        pickupAutocompleteFragment.setLocationBias(PHILIPPINES_BOUNDS)
        pickupAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.d(TAG, "Pickup Place: ${place.name}, ${place.latLng}, Address: ${place.address}")
                place.latLng?.let {
                    updateMapLocation(it, place.name ?: "Selected Pickup", true)
                    pickupLocationEditText.setText(place.address ?: place.name)
                    isPickupMode = false
                    destinationEditText.requestFocus()
                    destinationAutocompleteFragment.setText("")
                    destinationMarker?.remove()
                    destinationMarker = null
                    destinationLatLng = null
                    destinationEditText.setText("")
                }
            }
            override fun onError(status: Status) {
                Log.e(TAG, "Pickup Autocomplete error: $status")
                Toast.makeText(this@BookingActivity, "Error finding pickup location: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
        })

        destinationAutocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
        destinationAutocompleteFragment.setHint("Enter Destination")
        destinationAutocompleteFragment.setCountry("PH")
        destinationAutocompleteFragment.setLocationBias(PHILIPPINES_BOUNDS)
        destinationAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.d(TAG, "Destination Place: ${place.name}, ${place.latLng}, Address: ${place.address}")
                place.latLng?.let {
                    updateMapLocation(it, place.name ?: "Selected Destination", false)
                    destinationEditText.setText(place.address ?: place.name)
                }
            }
            override fun onError(status: Status) {
                Log.e(TAG, "Destination Autocomplete error: $status")
                Toast.makeText(this@BookingActivity, "Error finding destination: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun checkLocationPermissionAndSetupMap() {
        Log.d(TAG, "Checking location permissions.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Location permission already granted.")
            enableMyLocationOnMap()
        } else {
            Log.d(TAG, "Location permission not granted. Requesting...")
            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationOnMap() {
        if (mMap == null) {
            Log.d(TAG, "Map not initialized yet. Cannot enable MyLocation layer.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Enabling MyLocation layer and attempting to move camera.")
            mMap?.isMyLocationEnabled = true
            fusedLocationProviderClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    val currentLatLng = LatLng(it.latitude, it.longitude)
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, DEFAULT_ZOOM))
                    Log.d(TAG, "Moved camera to current location: lat/lng: (${it.latitude},${it.longitude})")
                } ?: run {
                    Log.d(TAG, "Last known location is null. Using default map setup.")
                    setupMapWithDefaultLocation()
                }
            }.addOnFailureListener {
                Log.e(TAG, "Failed to get current location.", it)
                setupMapWithDefaultLocation()
            }
        } else {
            Log.d(TAG, "Location permission still not granted. Cannot enable MyLocation layer.")
            setupMapWithDefaultLocation() // Fallback if permissions somehow revoked or check failed
        }
    }

    private fun setupMapWithDefaultLocation() {
        Log.d(TAG, "Setting up map with default location (Manila).")
        val manila = LatLng(14.5995, 120.9842) // Default to Manila
        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(manila, DEFAULT_ZOOM))
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocationAndSet(isForPickup: Boolean) {
        if (mMap == null) {
            Toast.makeText(this, "Map not ready.", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationProviderClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                val currentLatLng = LatLng(it.latitude, it.longitude)
                val title = if (isForPickup) "Current Pickup Location" else "Current Destination Location"
                updateMapLocation(currentLatLng, title, isForPickup) // Address will be fetched here
                 if (isForPickup) {
                    isPickupMode = false
                    destinationEditText.requestFocus()
                    val destinationAutocompleteFragment =
                        supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
                    destinationAutocompleteFragment.setText("")
                    destinationMarker?.remove()
                    destinationMarker = null
                    destinationLatLng = null
                    destinationEditText.setText("")
                }
            } ?: Toast.makeText(this, "Could not get current location.", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get current location.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun updateMapLocation(latLng: LatLng, title: String, isPickup: Boolean) {
        val markerIconDrawableId = if (isPickup) R.drawable.ic_maps_marker_start else R.drawable.ic_maps_marker_end
        val customMarkerIcon = getBitmapDescriptorFromVector(this, markerIconDrawableId)

        if (customMarkerIcon == null) {
            Log.e(TAG, "Failed to create custom marker icon for $title. Using default marker.")
        }
        val markerOptions = MarkerOptions().position(latLng).title(title)
        customMarkerIcon?.let { markerOptions.icon(it) }

        val addressText = getAddressFromLatLng(latLng) ?: title

        if (isPickup) {
            pickupMarker?.remove()
            pickupMarker = mMap?.addMarker(markerOptions)
            pickupLocationLatLng = latLng
            pickupLocationEditText.setText(addressText)
            Log.d(TAG, "Updated pickup: $addressText ($latLng)")
        } else {
            destinationMarker?.remove()
            destinationMarker = mMap?.addMarker(markerOptions)
            destinationLatLng = latLng
            destinationEditText.setText(addressText)
            Log.d(TAG, "Updated destination: $addressText ($latLng)")
        }
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM))
    }

    private fun getAddressFromLatLng(latLng: LatLng): String? {
        return try {
            val addresses: List<Address>? = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                addresses[0].getAddressLine(0) ?: "Unknown address at this location"
            } else {
                "Address not found for this location"
            }
        } catch (e: IOException) {
            Log.e(TAG, "Geocoder IOException", e)
            "Could not fetch address (network error)"
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Geocoder IllegalArgumentException (invalid lat/lng)", e)
            "Could not fetch address (invalid coordinates)"
        }
    }
}