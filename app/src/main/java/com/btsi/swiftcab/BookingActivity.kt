package com.btsi.swiftcab

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.btsi.swiftcab.databinding.ActivityBookingBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

private const val TAG = "BookingActivity"

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityBookingBinding
    private val viewModel: BookingViewModel by viewModels {
        BookingViewModelFactory(
            FirebaseDatabase.getInstance(),
            FirebaseAuth.getInstance(),
            FirebaseFunctions.getInstance(),
            FirebaseFirestore.getInstance()
        )
    }

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder

    private var pickupLocationLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null

    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var driverMarker: Marker? = null
    private var routePolyline: Polyline? = null

    private var previousStateClass: Class<out BookingUiState>? = null


    private enum class SelectionMode {
        PICKUP, DROPOFF
    }

    private var currentSelectionMode: SelectionMode = SelectionMode.PICKUP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this)
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }

        setupUI()
        observeUiState()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        enableMyLocation()
        mMap.setOnMapClickListener { latLng ->
            val title = if (currentSelectionMode == SelectionMode.PICKUP) "Pickup Location" else "Destination"
            addOrUpdateMarker(latLng, title, currentSelectionMode == SelectionMode.PICKUP)
            updateAddressFromLatLng(latLng, currentSelectionMode == SelectionMode.PICKUP)
        }

        // Center map on last known location
        fusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val currentLatLng = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
            }
        }
    }

    private fun addOrUpdateMarker(latLng: LatLng, title: String, isPickup: Boolean) {
        if (isPickup) {
            pickupLocationLatLng = latLng
            pickupMarker?.remove()
            pickupMarker = mMap.addMarker(MarkerOptions().position(latLng).title(title))
        } else {
            destinationLatLng = latLng
            destinationMarker?.remove()
            destinationMarker = mMap.addMarker(MarkerOptions().position(latLng).title(title))
        }
        zoomToFitMarkers()
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        // For simplicity, permissions are assumed to be granted.
        // In a real app, you must handle permission requests.
        mMap.isMyLocationEnabled = true
        mMap.uiSettings.isMyLocationButtonEnabled = true
    }

    private fun setupUI() {
        binding.buttonConfirmBooking.setOnClickListener {
            createBookingRequest()
        }

        val pickupAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as AutocompleteSupportFragment
        pickupAutocompleteFragment.setPlaceFields(listOf(com.google.android.libraries.places.api.model.Place.Field.ID, com.google.android.libraries.places.api.model.Place.Field.NAME, com.google.android.libraries.places.api.model.Place.Field.LAT_LNG))
        pickupAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: com.google.android.libraries.places.api.model.Place) {
                pickupLocationLatLng = place.latLng
                pickupMarker?.remove()
                pickupMarker = mMap.addMarker(MarkerOptions().position(place.latLng!!).title("Pickup: ${place.name}"))
                binding.pickupLocationEditText.setText(place.name)
                zoomToFitMarkers()
            }

            override fun onError(status: com.google.android.gms.common.api.Status) {
                Toast.makeText(this@BookingActivity, "An error occurred: $status", Toast.LENGTH_SHORT).show()
            }
        })

        val destinationAutocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as AutocompleteSupportFragment
        destinationAutocompleteFragment.setPlaceFields(listOf(com.google.android.libraries.places.api.model.Place.Field.ID, com.google.android.libraries.places.api.model.Place.Field.NAME, com.google.android.libraries.places.api.model.Place.Field.LAT_LNG))
        destinationAutocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: com.google.android.libraries.places.api.model.Place) {
                destinationLatLng = place.latLng
                destinationMarker?.remove()
                destinationMarker = mMap.addMarker(MarkerOptions().position(place.latLng!!).title("Destination: ${place.name}"))
                binding.destinationEditText.setText(place.name)
                zoomToFitMarkers()
            }

            override fun onError(status: com.google.android.gms.common.api.Status) {
                Toast.makeText(this@BookingActivity, "An error occurred: $status", Toast.LENGTH_SHORT).show()
            }
        })

        binding.buttonCancelRideRider.setOnClickListener { viewModel.cancelBooking() }
        binding.buttonCancelWhileFinding.setOnClickListener { viewModel.cancelBooking() }


        // Location selection buttons
        binding.buttonCurrentLocationPickup.setOnClickListener { getCurrentLocationAndSet(isPickup = true) }
        binding.buttonCurrentLocationDropoff.visibility = View.GONE


        binding.buttonSelectPickupMode.setOnClickListener {
            currentSelectionMode = SelectionMode.PICKUP
            Toast.makeText(this, "Tap on the map to set your pickup location", Toast.LENGTH_SHORT).show()
        }
        binding.buttonSelectDropoffMode.setOnClickListener {
            currentSelectionMode = SelectionMode.DROPOFF
            Toast.makeText(this, "Tap on the map to set your drop-off location", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocationAndSet(isPickup: Boolean) {
        if (!mMap.isMyLocationEnabled) {
            Toast.makeText(this, "Please enable location services.", Toast.LENGTH_SHORT).show()
            return
        }
        fusedLocationProviderClient.lastLocation.addOnSuccessListener(this) { location ->
            if (location != null) {
                val currentLatLng = LatLng(location.latitude, location.longitude)
                val title = if (isPickup) "Pickup Location" else "Destination"
                addOrUpdateMarker(currentLatLng, title, isPickup)
                updateAddressFromLatLng(currentLatLng, isPickup)
                zoomToFitMarkers()
            } else {
                Toast.makeText(this, "Could not get current location.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createBookingRequest() {
        if (pickupLocationLatLng == null || destinationLatLng == null) {
            Toast.makeText(this, "Please set pickup and destination.", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.createBooking(
            pickupLatLng = pickupLocationLatLng!!,
            destinationLatLng = destinationLatLng!!,
            pickupAddress = binding.pickupLocationEditText.text.toString(),
            destinationAddress = binding.destinationEditText.text.toString()
        )
    }
    private fun bitmapDescriptorFromVector(context: Context, vectorResId: Int): BitmapDescriptor? {
        return ContextCompat.getDrawable(context, vectorResId)?.run {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }

    private fun zoomToFitMarkers() {
        val markers = listOfNotNull(pickupMarker, destinationMarker, driverMarker)
        if (markers.isEmpty()) return
        if (markers.size == 1) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(markers.first().position, 15f))
            return
        }

        val builder = LatLngBounds.Builder()
        for (marker in markers) {
            builder.include(marker.position)
        }
        val bounds = builder.build()
        val padding = 150 // offset from edges of the map in pixels
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }

    private fun observeUiState() {
        viewModel.uiState.observe(this) { state ->
            renderState(state)
        }
    }

    private fun renderState(state: BookingUiState) {
        binding.infoCardView.visibility = View.GONE
        binding.bookingStatusCardView.visibility = View.GONE
        binding.findingDriverLayout.visibility = View.GONE
        setBookingInputEnabled(false)

        val isNewState = previousStateClass != state::class.java

        when (state) {
            is BookingUiState.Initial -> {
                resetToInitialState()
            }
            is BookingUiState.FindingDriver -> {
                showFindingDriverLayout(state.message)
            }
            is BookingUiState.DriverOnTheWay -> {
                if (::mMap.isInitialized) {
                    if (isNewState) {
                        mMap.clear()
                        driverMarker = null
                        pickupMarker = mMap.addMarker(MarkerOptions().position(state.pickupLocation).title("Pickup"))
                        destinationMarker = mMap.addMarker(MarkerOptions().position(state.dropOffLocation).title("Destination"))
                    }

                    if (state.driverLocation != null) {
                        updateDriverMarker(state.driverLocation)
                    }

                    if (isNewState) {
                        getDirectionsAndDrawRoute(state.pickupLocation, state.dropOffLocation)
                    }
                    showBookingStatusCard(
                        header = "Driver on the way",
                        driverName = state.driverName,
                        vehicleDetails = state.vehicleDetails,
                        message = state.message
                    )
                    zoomToFitMarkers()
                } else {
                    Log.e("renderState", "Map not ready for DriverOnTheWay")
                }
                previousStateClass = state::class.java
            }

            is BookingUiState.DriverArrived -> {
                showBookingStatusCard(
                    header = "Driver has arrived",
                    driverName = state.driverName,
                    vehicleDetails = state.vehicleDetails,
                    message = state.message
                )
            }
            is BookingUiState.TripInProgress -> {
                if (::mMap.isInitialized) {
                    if (isNewState) {
                        mMap.clear()
                        driverMarker = null
                        pickupMarker = mMap.addMarker(MarkerOptions().position(state.pickupLocation).title("Pickup"))
                        destinationMarker = mMap.addMarker(MarkerOptions().position(state.dropOffLocation).title("Destination"))
                    }

                    if (state.driverLocation != null) {
                        updateDriverMarker(state.driverLocation)
                    }
                    if (isNewState) {
                        getDirectionsAndDrawRoute(state.pickupLocation, state.dropOffLocation)
                    }
                    showBookingStatusCard(
                        header = "Trip in progress",
                        driverName = state.driverName,
                        vehicleDetails = state.vehicleDetails,
                        message = state.message
                    )
                    zoomToFitMarkers()
                } else {
                    Log.e("renderState", "Map not ready for TripInProgress")
                }
                previousStateClass = state::class.java
            }
            is BookingUiState.TripCompleted -> {
                // Show a confirmation message
                Toast.makeText(this, "Trip Completed! Thank you for riding with us.", Toast.LENGTH_LONG).show()

                // Navigate back to the home screen after a short delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this, HomeActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish()
                }, 3000) // 3-second delay

                // Hide unnecessary buttons
                binding.buttonCancelRideRider.visibility = View.GONE
            }
            is BookingUiState.Canceled -> {
                showBookingStatusCard(
                    header = "Booking Canceled",
                    driverName = "",
                    vehicleDetails = "",
                    message = state.message
                )
                binding.buttonCancelRideRider.visibility = View.GONE
            }
            is BookingUiState.Error -> {
                Toast.makeText(this, "Error: ${state.message}", Toast.LENGTH_LONG).show()
                resetToInitialState()
            }
        }
    }


    private fun updateDriverMarker(driverLocation: LatLng) {
        val carIcon = bitmapDescriptorFromVector(this, R.drawable.ic_car_marker)
        if (driverMarker == null) {
            driverMarker = mMap.addMarker(
                MarkerOptions().position(driverLocation).title("Driver").icon(carIcon).anchor(0.5f, 0.5f)
            )
        } else {
            driverMarker?.position = driverLocation
        }
    }

    private fun setBookingInputEnabled(enabled: Boolean) {
        binding.infoCardView.visibility = if (enabled) View.VISIBLE else View.GONE
    }


    private fun resetToInitialState() {
        mMap.clear()
        driverMarker = null
        pickupMarker = null
        destinationMarker = null
        routePolyline = null
        binding.pickupLocationEditText.text.clear()
        binding.destinationEditText.text.clear()
        setBookingInputEnabled(true)
        binding.bookingStatusCardView.visibility = View.GONE
        binding.findingDriverLayout.visibility = View.GONE
        binding.buttonCancelRideRider.visibility = View.GONE
        binding.buttonCancelWhileFinding.visibility = View.GONE
    }


    private fun showFindingDriverLayout(message: String) {
        binding.findingDriverLayout.visibility = View.VISIBLE
        binding.textViewFindingDriver.text = message
        binding.buttonCancelWhileFinding.visibility = View.VISIBLE
    }

    private fun showBookingStatusCard(header: String, driverName: String, vehicleDetails: String, message: String) {
        binding.bookingStatusCardView.visibility = View.VISIBLE
        binding.textViewBookingStatusHeader.text = header
        binding.textViewDriverNameStatus.text = "Driver: $driverName"
        binding.textViewVehicleDetailsStatus.text = "Vehicle: $vehicleDetails"
        binding.textViewTripStatusMessage.text = message

        val showCancelButton = header == "Driver on the way" || header == "Driver has arrived"
        binding.buttonCancelRideRider.visibility = if(showCancelButton) View.VISIBLE else View.GONE
    }

    private fun updateAddressFromLatLng(latLng: LatLng, isPickup: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val addressText = address.getAddressLine(0)
                    withContext(Dispatchers.Main) {
                        if (isPickup) {
                            binding.pickupLocationEditText.setText(addressText)
                        } else {
                            binding.destinationEditText.setText(addressText)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get address from LatLng", e)
            }
        }
    }


    private fun getDirectionsAndDrawRoute(start: LatLng, end: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            val apiKey = getString(R.string.google_maps_key)
            val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${start.latitude},${start.longitude}&destination=${end.latitude},${end.longitude}&key=$apiKey"
            try {
                val result = URL(url).readText()
                val json = JSONObject(result)
                val routes = json.getJSONArray("routes")
                if (routes.length() > 0) {
                    val route = routes.getJSONObject(0)
                    val overviewPolyline = route.getJSONObject("overview_polyline")
                    val points = overviewPolyline.getString("points")
                    val decodedPath = decodePolyline(points)

                    withContext(Dispatchers.Main) {
                        routePolyline?.remove()
                        routePolyline = mMap.addPolyline(PolylineOptions().addAll(decodedPath).color(Color.BLUE).width(12f))
                    }
                }
            } catch (e: Exception) {
                Log.e("getDirections", "Error fetching directions", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookingActivity, "Could not get directions.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }
}
