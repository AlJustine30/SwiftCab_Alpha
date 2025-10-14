package com.btsi.swiftcab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.*

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityBookingBinding
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var viewModel: BookingViewModel
    private lateinit var geocoder: Geocoder

    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var driverMarker: Marker? = null

    private var pickupLocationLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null

    private var routePolyline: Polyline? = null


    private enum class SelectionMode {
        PICKUP, DROPOFF
    }
    private var currentSelectionMode: SelectionMode? = null
    companion object {
        private const val TAG = "BookingActivity"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = FirebaseDatabase.getInstance()
        val auth = FirebaseAuth.getInstance()
        val functions = FirebaseFunctions.getInstance()
        val viewModelFactory = BookingViewModelFactory(database, auth, functions)
        viewModel = ViewModelProvider(this, viewModelFactory)[BookingViewModel::class.java]

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
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
            when (currentSelectionMode) {
                SelectionMode.PICKUP -> {
                    pickupLocationLatLng = latLng
                    addOrUpdateMarker(latLng, "Pickup Location", true)
                    updateAddressFromLatLng(latLng, true)
                }
                SelectionMode.DROPOFF -> {
                    destinationLatLng = latLng
                    addOrUpdateMarker(latLng, "Destination", false)
                    updateAddressFromLatLng(latLng, false)
                }
                null -> {
                    // Do nothing if no mode is selected
                }
            }
            zoomToFitMarkers()
            currentSelectionMode = null // Reset mode after a point is selected
        }
    }
    private fun addOrUpdateMarker(latLng: LatLng, title: String, isPickup: Boolean) {
        if (isPickup) {
            pickupMarker?.remove()
            pickupMarker = mMap.addMarker(MarkerOptions().position(latLng).title(title))
            pickupLocationLatLng = latLng
        } else {
            destinationMarker?.remove()
            destinationMarker = mMap.addMarker(MarkerOptions().position(latLng).title(title))
            destinationLatLng = latLng
        }
    }
    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        // This function assumes location permissions are already granted.
        // In a real app, you must handle the permission request flow.
        mMap.isMyLocationEnabled = true
        mMap.uiSettings.isMyLocationButtonEnabled = true
    }

    private fun setupUI() {
        binding.buttonConfirmBooking.setOnClickListener { createBookingRequest() }
        binding.backButtonBooking.setOnClickListener { finish() }

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
        binding.buttonCurrentLocationDropoff.setOnClickListener { getCurrentLocationAndSet(isPickup = false) }

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

        when (state) {
            is BookingUiState.Initial -> {
                resetToInitialState()
            }
            is BookingUiState.FindingDriver -> {
                showFindingDriverLayout(state.message)
            }
            is BookingUiState.DriverOnTheWay -> {
                if (::mMap.isInitialized) {
                    mMap.clear()
                    pickupMarker = mMap.addMarker(MarkerOptions().position(state.pickupLocation).title("Pickup"))
                    destinationMarker = mMap.addMarker(MarkerOptions().position(state.dropOffLocation).title("Destination"))

                    state.driverLocation?.let {
                        updateDriverMarker(it)
                        getDirectionsAndDrawRoute(it, state.pickupLocation)
                    } ?: run {
                        zoomToFitMarkers()
                    }
                }

                showBookingStatusCard(
                    header = "Driver is on the way!",
                    driverName = "Driver: ${state.driverName}",
                    vehicleDetails = "Vehicle: ${state.vehicleDetails}",
                    message = state.message,
                    isTripOngoing = true
                )
            }
            is BookingUiState.DriverArrived -> {
                routePolyline?.remove()
                showBookingStatusCard(
                    header = "Your driver has arrived!",
                    driverName = "Driver: ${state.driverName}",
                    vehicleDetails = "Vehicle: ${state.vehicleDetails}",
                    message = "Please meet your driver at the pickup location.",
                    isTripOngoing = true
                )
            }
            is BookingUiState.TripInProgress -> {
                 if (::mMap.isInitialized) {
                     mMap.clear()
                     pickupMarker = mMap.addMarker(MarkerOptions().position(state.pickupLocation).title("Pickup"))
                     destinationMarker = mMap.addMarker(MarkerOptions().position(state.dropOffLocation).title("Destination"))

                     state.driverLocation?.let {
                         updateDriverMarker(it)
                         getDirectionsAndDrawRoute(it, state.dropOffLocation)
                     } ?: run {
                         zoomToFitMarkers()
                     }
                 }

                showBookingStatusCard(
                    header = "Trip in Progress",
                    driverName = "Driver: ${state.driverName}",
                    vehicleDetails = "Vehicle: ${state.vehicleDetails}",
                    message = "Enjoy your ride!",
                    isTripOngoing = false
                )
            }
            is BookingUiState.TripCompleted -> {
                Toast.makeText(this, "Trip Completed!", Toast.LENGTH_LONG).show()
                resetToInitialState()
            }
            is BookingUiState.Canceled -> {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                resetToInitialState()
            }
            is BookingUiState.Error -> {
                Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                resetToInitialState()
            }
        }
    }

    private fun updateDriverMarker(location: LatLng) {
        val carIcon = bitmapDescriptorFromVector(this, R.drawable.ic_car_marker)
        if (driverMarker == null) {
            driverMarker = mMap.addMarker(MarkerOptions().position(location).title("Driver").icon(carIcon).anchor(0.5f, 0.5f).flat(true))
        } else {
            driverMarker?.position = location
        }
    }

    private fun getDirectionsAndDrawRoute(origin: LatLng, destination: LatLng) {
        val apiKey = getString(R.string.google_maps_key)
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&key=$apiKey"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = URL(url).readText()
                val json = JSONObject(result)
                val routes = json.getJSONArray("routes")
                if (routes.length() > 0) {
                    val points = routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points")
                    val decodedPath = decodePoly(points)

                    withContext(Dispatchers.Main) {
                        routePolyline?.remove()
                        routePolyline = mMap.addPolyline(PolylineOptions().addAll(decodedPath).color(Color.BLUE).width(10f))
                        zoomToFitMarkers()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching directions", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookingActivity, "Error fetching directions", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun decodePoly(encoded: String): List<LatLng> {
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


    private fun showFindingDriverLayout(message: String) {
        binding.findingDriverLayout.visibility = View.VISIBLE
        binding.textViewFindingDriver.text = message
        binding.buttonCancelWhileFinding.visibility = View.VISIBLE
    }

    private fun showBookingStatusCard(header: String, driverName: String, vehicleDetails: String, message: String, isTripOngoing: Boolean) {
        binding.bookingStatusCardView.visibility = View.VISIBLE
        binding.textViewBookingStatusHeader.text = header
        binding.textViewDriverNameStatus.text = driverName
        binding.textViewVehicleDetailsStatus.text = vehicleDetails
        binding.textViewTripStatusMessage.text = message
        binding.buttonCancelRideRider.visibility = if (isTripOngoing) View.VISIBLE else View.GONE
    }

    private fun setBookingInputEnabled(enabled: Boolean) {
        binding.pickupLocationEditText.isEnabled = enabled
        binding.destinationEditText.isEnabled = enabled
        binding.buttonCurrentLocationPickup.isEnabled = enabled
        binding.buttonCurrentLocationDropoff.isEnabled = enabled
        binding.buttonSelectPickupMode.isEnabled = enabled
        binding.buttonSelectDropoffMode.isEnabled = enabled
        binding.buttonConfirmBooking.isEnabled = enabled

        val pickupFragment = supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment)
        pickupFragment?.view?.isEnabled = enabled

        val destinationFragment = supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment)
        destinationFragment?.view?.isEnabled = enabled
    }

    private fun resetToInitialState() {
        binding.infoCardView.visibility = View.VISIBLE
        setBookingInputEnabled(true)
        mMap.clear()
        pickupMarker = null
        destinationMarker = null
        driverMarker = null
        pickupLocationLatLng = null
        destinationLatLng = null
        routePolyline = null
        viewModel.clearBookingState()
        binding.pickupLocationEditText.text.clear()
        binding.destinationEditText.text.clear()
    }

    private fun updateAddressFromLatLng(latLng: LatLng, isPickup: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val addressText = address.getAddressLine(0) // Full address
                    withContext(Dispatchers.Main) {
                        if (isPickup) {
                            binding.pickupLocationEditText.setText(addressText)
                        } else {
                            binding.destinationEditText.setText(addressText)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not get address from coordinates", e)
            }
        }
    }

}
