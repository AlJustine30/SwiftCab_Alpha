package com.btsi.swiftcab

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.btsi.swiftcab.databinding.ActivityBookingBinding
import com.btsi.swiftcab.BookingViewModel
import com.btsi.swiftcab.BookingViewModelFactory
import com.btsi.swiftcab.BookingUiState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import android.widget.Button
import android.widget.EditText
import android.widget.RatingBar
import android.content.Intent
import com.bumptech.glide.Glide
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.LatLngBounds
import android.graphics.Color
import org.json.JSONObject
import java.net.URL
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

class BookingActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityBookingBinding
    private lateinit var viewModel: BookingViewModel
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var mMap: GoogleMap? = null
    private var playServicesAvailable: Boolean = false
    private var pickupLocationLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null
    private var pickupMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var currentPolyline: Polyline? = null
    private var driverMarker: Marker? = null

    private var tripTimerHandler: android.os.Handler? = null
    private var tripTimerRunnable: Runnable? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    // Correct for server/client clock skew when computing elapsed trip time
    private var serverTimeOffsetMs: Long = 0L

    private enum class SelectionMode { NONE, PICKUP, DROPOFF }
    private var selectionMode: SelectionMode = SelectionMode.NONE

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "BookingActivity"
    }

    // Estimated fare calculation
    private val BASE_FARE = 50.0
    private val PER_KM_RATE = 13.5

    private fun calculateDistanceKm(a: LatLng, b: LatLng): Double {
        val R = 6371.0 // km
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val aa = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa))
        return R * c
    }

    private fun updateEstimatedFareIfReady() {
        val pickup = pickupLocationLatLng
        val dest = destinationLatLng
        if (pickup != null && dest != null) {
            val distanceKm = calculateDistanceKm(pickup, dest)
            val estimatedFare = BASE_FARE + PER_KM_RATE * distanceKm
            binding.textViewEstimatedFare.text = String.format(java.util.Locale.getDefault(), "Estimated Fare: ₱%.2f", estimatedFare)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.textViewEstimatedFare.text = ""

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Show discount indicator if rider has a pending next-booking discount
        updateDiscountIndicator()

        // Fetch server time offset once to avoid timer delay at start
        initServerTimeOffset()

        playServicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS

        val factory = BookingViewModelFactory(
            FirebaseDatabase.getInstance(),
            FirebaseAuth.getInstance(),
            FirebaseFunctions.getInstance(),
            FirebaseFirestore.getInstance()
        )
        viewModel = ViewModelProvider(this, factory)[BookingViewModel::class.java]

        if (playServicesAvailable) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        } else {
            // Remove Places autocomplete fragments that rely on Google Play services
            supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment)?.let {
                supportFragmentManager.beginTransaction().remove(it).commit()
            }
            supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment)?.let {
                supportFragmentManager.beginTransaction().remove(it).commit()
            }
            // Hide current location shortcuts when Play services are unavailable
            binding.buttonCurrentLocationPickup.visibility = View.GONE
            binding.buttonCurrentLocationDropoff.visibility = View.GONE
        }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        if (playServicesAvailable) {
            mapFragment?.getMapAsync(this)
        } else {
            Toast.makeText(this, "Google Play services not available; map disabled.", Toast.LENGTH_SHORT).show()
        }

        setupUI()
        // Wire up the ‘OK (Show Map)’ button to dismiss the canceled overlay
        binding.buttonShowOnMap.setOnClickListener {
            binding.noDriversLayout.visibility = View.GONE
            viewModel.clearBookingState()
        }
        setupPlaceAutocomplete()
        observeViewModel()
        // Attempt to resume any active booking for this rider
        viewModel.resumeActiveBookingIfAny()
    }

    private fun setupUI() {
        binding.buttonConfirmBooking.setOnClickListener {
            createBookingRequest()
        }
        binding.buttonSelectPickupMode.setOnClickListener {
            selectionMode = SelectionMode.PICKUP
            Toast.makeText(this, "Tap on the map to set Pickup", Toast.LENGTH_SHORT).show()
        }
        binding.buttonSelectDropoffMode.setOnClickListener {
            selectionMode = SelectionMode.DROPOFF
            Toast.makeText(this, "Tap on the map to set Drop-off", Toast.LENGTH_SHORT).show()
        }
        binding.buttonCurrentLocationPickup.setOnClickListener {
            setCurrentLocationAs(true)
        }
        binding.buttonCurrentLocationDropoff.setOnClickListener {
            setCurrentLocationAs(false)
        }
        binding.backButtonBooking.setOnClickListener { finish() }
    }

    private fun setupPlaceAutocomplete() {
        if (!playServicesAvailable) return
        val pickupFragment = supportFragmentManager.findFragmentById(R.id.pickup_autocomplete_fragment) as? AutocompleteSupportFragment
        val destinationFragment = supportFragmentManager.findFragmentById(R.id.destination_autocomplete_fragment) as? AutocompleteSupportFragment

        val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS, Place.Field.LAT_LNG)
        pickupFragment?.setPlaceFields(placeFields)
        destinationFragment?.setPlaceFields(placeFields)

        pickupFragment?.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                place.latLng?.let { latLng ->
                    pickupLocationLatLng = latLng
                    binding.pickupLocationEditText.setText(place.address ?: place.name ?: "")
                    setPickupMarker(latLng)
                    selectionMode = SelectionMode.NONE
                    updateEstimatedFareIfReady()
                }
            }
            override fun onError(status: Status) {
                Toast.makeText(this@BookingActivity, "Pickup selection error: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
        })

        destinationFragment?.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                place.latLng?.let { latLng ->
                    destinationLatLng = latLng
                    binding.destinationEditText.setText(place.address ?: place.name ?: "")
                    setDestinationMarker(latLng)
                    selectionMode = SelectionMode.NONE
                    updateEstimatedFareIfReady()
                }
            }
            override fun onError(status: Status) {
                Toast.makeText(this@BookingActivity, "Drop-off selection error: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun setCurrentLocationAs(isPickup: Boolean) {
        if (!playServicesAvailable) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                val address = getAddressFromLatLng(latLng)
                if (isPickup) {
                    pickupLocationLatLng = latLng
                    binding.pickupLocationEditText.setText(address)
                    setPickupMarker(latLng)
                } else {
                    destinationLatLng = latLng
                    binding.destinationEditText.setText(address)
                    setDestinationMarker(latLng)
                }
                updateEstimatedFareIfReady()
            }
        }
    }

    private fun getAddressFromLatLng(latLng: LatLng): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val results = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
            if (!results.isNullOrEmpty()) {
                results[0].getAddressLine(0) ?: "${latLng.latitude}, ${latLng.longitude}"
            } else {
                "${latLng.latitude}, ${latLng.longitude}"
            }
        } catch (e: Exception) {
            "${latLng.latitude}, ${latLng.longitude}"
        }
    }

    private fun setPickupMarker(latLng: LatLng) {
        pickupMarker?.remove()
        pickupMarker = mMap?.addMarker(MarkerOptions().position(latLng).title("Pickup"))
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        updateEstimatedFareIfReady()
    }

    private fun setDestinationMarker(latLng: LatLng) {
        destinationMarker?.remove()
        destinationMarker = mMap?.addMarker(MarkerOptions().position(latLng).title("Drop-off"))
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        updateEstimatedFareIfReady()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap?.setOnMapClickListener { latLng -> onMapTapped(latLng) }
        if (playServicesAvailable) {
            enableMyLocationIfPermitted()
        }
    }

    private fun enableMyLocationIfPermitted() {
        if (!playServicesAvailable) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap?.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun onMapTapped(latLng: LatLng) {
        when (selectionMode) {
            SelectionMode.PICKUP -> {
                pickupLocationLatLng = latLng
                setPickupMarker(latLng)
                val address = getAddressFromLatLng(latLng)
                binding.pickupLocationEditText.setText(address)
                selectionMode = SelectionMode.NONE
            }
            SelectionMode.DROPOFF -> {
                destinationLatLng = latLng
                setDestinationMarker(latLng)
                val address = getAddressFromLatLng(latLng)
                binding.destinationEditText.setText(address)
                selectionMode = SelectionMode.NONE
            }
            else -> {
                // No-op
            }
        }
        updateEstimatedFareIfReady()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is BookingUiState.Initial -> {
                    binding.infoCardView.visibility = View.VISIBLE
                    binding.bookingStatusCardView.visibility = View.GONE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    clearDriverMarker()
                    clearRoute()
                }
                is BookingUiState.FindingDriver -> {
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.VISIBLE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Finding driver"
                    binding.textViewTripStatusMessage.text = state.message
                    binding.textViewDriverNameStatus.text = "Loading..."
                    binding.textViewVehicleDetailsStatus.text = ""
                    binding.textViewDriverPhoneStatus.text = ""
                }
                is BookingUiState.DriverOnTheWay -> {
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Driver on the way"
                    binding.textViewTripStatusMessage.text = state.message

                    pickupLocationLatLng = state.pickupLocation
                    destinationLatLng = state.dropOffLocation
                    setPickupMarker(state.pickupLocation)
                    setDestinationMarker(state.dropOffLocation)
                    updateEstimatedFareIfReady()

                    binding.textViewDriverNameStatus.text = state.driverName
                    binding.textViewVehicleDetailsStatus.text = state.vehicleDetails
                    binding.textViewDriverPhoneStatus.text = state.driverPhone ?: "Unavailable"
                    updateDriverRatingSummary(state.driverId)

                    state.driverLocation?.let { loc ->
                        updateDriverMarker(loc)
                        getDirectionsAndDrawRoute(loc, state.pickupLocation)
                    }
                }
                is BookingUiState.DriverArrived -> {
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Driver arrived"
                    binding.textViewTripStatusMessage.text = state.message

                    pickupLocationLatLng = state.pickupLocation
                    destinationLatLng = state.dropOffLocation
                    setPickupMarker(state.pickupLocation)
                    setDestinationMarker(state.dropOffLocation)
                    updateEstimatedFareIfReady()
                }
                is BookingUiState.TripInProgress -> {
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Trip in progress"
                    binding.textViewTripStatusMessage.text = state.message

                    pickupLocationLatLng = state.pickupLocation
                    destinationLatLng = state.dropOffLocation
                    setPickupMarker(state.pickupLocation)
                    setDestinationMarker(state.dropOffLocation)
                    updateEstimatedFareIfReady()

                    // Start or update timer for per-minute fee
                    val startMs = state.tripStartedAt ?: System.currentTimeMillis()
                    val perMin = state.perMinuteRate ?: 2.0
                    val base = state.fareBase ?: 0.0
                    
                    // Debug logging to check timer values
                    Log.d("BookingActivity", "TripInProgress - tripStartedAt: ${state.tripStartedAt}, perMinuteRate: ${state.perMinuteRate}, fareBase: ${state.fareBase}")
                    Log.d("BookingActivity", "Starting timer with startMs: $startMs, perMin: $perMin, base: $base")
                    
                    startTripTimer(startMs, perMin, base)

                    // Ensure total fare label is visible during trip
                    binding.textViewFinalFare.visibility = View.VISIBLE

                    binding.textViewDriverNameStatus.text = state.driverName
                    binding.textViewVehicleDetailsStatus.text = state.vehicleDetails
                    binding.textViewDriverPhoneStatus.text = state.driverPhone ?: "Unavailable"
                    updateDriverRatingSummary(state.driverId)

                    state.driverLocation?.let { loc ->
                        updateDriverMarker(loc)
                        getDirectionsAndDrawRoute(loc, state.dropOffLocation)
                    }
                }
                is BookingUiState.TripCompleted -> {
                    stopTripTimer()
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = getString(R.string.payment_confirmed_message)
                    binding.textViewTripStatusMessage.text = getString(R.string.payment_confirmed_message)

                    // Show final fare and duration for transparency
                    val total = state.finalFare ?: 0.0
                    val minutes = state.durationMinutes ?: 0
                    val base = state.fareBase ?: 0.0
                    val perKm = state.perKmRate ?: 0.0
                    val perMin = state.perMinuteRate ?: 0.0
                    val km = state.distanceKm ?: 0.0

                    binding.textViewFinalFare.visibility = View.VISIBLE
                    binding.textViewDuration.visibility = View.VISIBLE
                    binding.textViewFareBreakdown.visibility = View.VISIBLE

                    binding.textViewFinalFare.text = String.format("Fare: ₱%.2f", total)
                    binding.textViewDuration.text = String.format("Duration: %d min", minutes)
                    binding.textViewFareBreakdown.text = String.format(
                        "Breakdown: Base ₱%.2f + %.2f km × ₱%.2f + %d min × ₱%.2f = ₱%.2f",
                        base, km, perKm, minutes, perMin, total
                    )

                    clearDriverMarker()
                    clearRoute()
                    showRiderRatingDialog(state.bookingId, state.driverId)
                }
                is BookingUiState.AwaitingPayment -> {
                    stopTripTimer()
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = getString(R.string.awaiting_payment_header)
                    binding.textViewTripStatusMessage.text = getString(R.string.awaiting_payment_message)

                    val total = state.finalFare ?: 0.0
                    val minutes = state.durationMinutes ?: 0
                    val base = state.fareBase ?: 0.0
                    val perKm = state.perKmRate ?: 0.0
                    val perMin = state.perMinuteRate ?: 0.0
                    val km = state.distanceKm ?: 0.0

                    binding.textViewFinalFare.visibility = View.VISIBLE
                    binding.textViewDuration.visibility = View.VISIBLE
                    binding.textViewFareBreakdown.visibility = View.VISIBLE

                    binding.textViewFinalFare.text = String.format("Fare: ₱%.2f", total)
                    binding.textViewDuration.text = String.format("Duration: %d min", minutes)
                    binding.textViewFareBreakdown.text = String.format(
                        "Breakdown: Base ₱%.2f + %.2f km × ₱%.2f + %d min × ₱%.2f = ₱%.2f",
                        base, km, perKm, minutes, perMin, total
                    )

                    clearDriverMarker()
                    clearRoute()
                    // Do NOT show review until payment is confirmed
                }
                is BookingUiState.Canceled -> {
                    binding.infoCardView.visibility = View.VISIBLE
                    binding.bookingStatusCardView.visibility = View.GONE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.VISIBLE
                    binding.textViewNoDriversMessage.text = state.message
                    clearDriverMarker()
                    clearRoute()
                }
                is BookingUiState.Error -> {
                    Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getDirectionsAndDrawRoute(origin: LatLng, destination: LatLng) {
        val apiKey = getString(R.string.google_maps_key)
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&key=$apiKey"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = URL(url).readText()
                val jsonObject = JSONObject(result)
                val routes = jsonObject.getJSONArray("routes")
                if (routes.length() > 0) {
                    val points = routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points")
                    val polylineOptions = PolylineOptions()
                        .addAll(decodePoly(points))
                        .color(Color.BLUE)
                        .width(12f)

                    withContext(Dispatchers.Main) {
                        currentPolyline?.remove()
                        currentPolyline = mMap?.addPolyline(polylineOptions)

                        // Adjust camera to fit the route
                        val boundsBuilder = LatLngBounds.Builder()
                        boundsBuilder.include(origin)
                        boundsBuilder.include(destination)
                        mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 150))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching directions", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BookingActivity, "Could not get directions.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodePoly(encoded: String): List<LatLng> {
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

    private fun updateMarkers() {
        // Preserve existing markers and route; only update pickup/destination markers
        pickupLocationLatLng?.let { setPickupMarker(it) }
        destinationLatLng?.let { setDestinationMarker(it) }
        updateEstimatedFareIfReady()
    }

    private fun updateDriverMarker(latLng: LatLng) {
        val icon = bitmapDescriptorFromVector(R.drawable.ic_car_marker)
        if (driverMarker == null) {
            driverMarker = mMap?.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Driver")
                    .icon(icon)
            )
        } else {
            driverMarker?.position = latLng
        }
    }

    private fun clearDriverMarker() {
        driverMarker?.remove()
        driverMarker = null
    }

    private fun bitmapDescriptorFromVector(drawableId: Int): BitmapDescriptor {
        val drawable = ContextCompat.getDrawable(this, drawableId) ?: return BitmapDescriptorFactory.defaultMarker()
        val targetSizePx = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun clearRoute() {
        currentPolyline?.remove()
        currentPolyline = null
    }

    private fun hideBookingStatusCard() {
        binding.bookingStatusCardView.visibility = View.GONE
        binding.infoCardView.visibility = View.VISIBLE
    }

    private fun startTripTimer(startMillis: Long, perMinuteRate: Double, initialFee: Double) {
        Log.d("BookingActivity", "startTripTimer called with startMillis: $startMillis, perMinuteRate: $perMinuteRate, initialFee: $initialFee")

        // Ensure any previous timer is fully stopped before starting a new one
        stopTripTimer()

        val handler = android.os.Handler(mainLooper)
        tripTimerHandler = handler

        // Show initial fee immediately
        binding.textViewInitialFee.visibility = View.VISIBLE
        binding.textViewInitialFee.text = String.format("Initial fee: ₱%.2f", initialFee)

        // Compute and show km fee once
        val pickupPos = pickupMarker?.position
        val destPos = destinationMarker?.position
        val kmFee: Double? = if (pickupPos != null && destPos != null) {
            val distanceKm = calculateDistanceKm(pickupPos, destPos)
            binding.textViewKmFee.visibility = View.VISIBLE
            binding.textViewKmFee.text = String.format("Km fee (₱%.1f/km): %.2f km | ₱%.2f", PER_KM_RATE, distanceKm, distanceKm * PER_KM_RATE)
            distanceKm * PER_KM_RATE
        } else {
            binding.textViewKmFee.visibility = View.GONE
            null
        }

        // Update time fee every second and total fare each minute increment
        tripTimerRunnable = object : Runnable {
            override fun run() {
                // Use server-corrected time to avoid initial 1-minute stall
                val serverNowMs = System.currentTimeMillis() + serverTimeOffsetMs
                val rawElapsedMs = serverNowMs - startMillis
                val elapsedMs = if (rawElapsedMs < 0) 0L else rawElapsedMs
                val totalSeconds = (elapsedMs / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val timeFee = minutes * perMinuteRate

                Log.d("BookingActivity", "Timer tick - elapsedMs: $elapsedMs, minutes: $minutes, seconds: $seconds, timeFee: $timeFee")

                binding.textViewTimeFee.visibility = View.VISIBLE
                binding.textViewTimeFee.text = String.format("Time fee (₱%.0f/min): %02d:%02d | ₱%.2f", perMinuteRate, minutes, seconds, timeFee)

                // Compute and show running total fare
                val totalFare = initialFee + (kmFee ?: 0.0) + timeFee
                binding.textViewFinalFare.visibility = View.VISIBLE
                binding.textViewFinalFare.text = String.format("Fare: ₱%.2f", totalFare)

                handler.postDelayed(this, 1000L)
            }
        }
        // Run first tick immediately so the timer appears to start instantly
        tripTimerRunnable!!.run()
    }

    private fun stopTripTimer() {
        Log.d("BookingActivity", "stopTripTimer called")
        tripTimerRunnable?.let { tripTimerHandler?.removeCallbacks(it) }
        tripTimerRunnable = null
        binding.textViewTimeFee.visibility = View.GONE
        binding.textViewInitialFee.visibility = View.GONE
        binding.textViewKmFee.visibility = View.GONE
        // Keep final fare visible for AwaitingPayment/Completed states; hide here only if card switches
    }

    private fun initServerTimeOffset() {
        try {
            val db = FirebaseDatabase.getInstance()
            db.getReference(".info/serverTimeOffset").addListenerForSingleValueEvent(
                object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        val offset = snapshot.getValue(Long::class.java)
                        serverTimeOffsetMs = offset ?: 0L
                        Log.d(TAG, "Server time offset: $serverTimeOffsetMs ms")
                    }

                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                        Log.w(TAG, "Server time offset load cancelled: ${error.message}")
                        serverTimeOffsetMs = 0L
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init server time offset", e)
            serverTimeOffsetMs = 0L
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh discount indicator in case it changed (e.g., after redemption)
        updateDiscountIndicator()
    }

    private fun updateDiscountIndicator() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            binding.textViewDiscountApplied.visibility = View.GONE
            return
        }
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val percent = (doc.getLong("nextBookingDiscountPercent") ?: 0L).toInt()
                if (percent > 0) {
                    binding.textViewDiscountApplied.visibility = View.VISIBLE
                    binding.textViewDiscountApplied.text = "Discount applied: ${percent}%"
                } else {
                    binding.textViewDiscountApplied.visibility = View.GONE
                }
            }
            .addOnFailureListener {
                // On failure, hide to avoid stale/incorrect info
                binding.textViewDiscountApplied.visibility = View.GONE
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocationIfPermitted()
            } else {
                Toast.makeText(this, "Location permission is required.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun createBookingRequest() {
        val pickup = pickupLocationLatLng
        val dropoff = destinationLatLng
        if (pickup == null || dropoff == null) {
            Toast.makeText(this, "Please set pickup and destination.", Toast.LENGTH_SHORT).show()
            return
        }
        val pickupAddress = binding.pickupLocationEditText.text?.toString() ?: ""
        val destinationAddress = binding.destinationEditText.text?.toString() ?: ""
        viewModel.createBooking(
            pickupLatLng = pickup,
            destinationLatLng = dropoff,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress
        )
    }

    private fun showRiderRatingDialog(bookingId: String, driverId: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rating, null)
        val ratingBar = dialogView.findViewById<RatingBar>(R.id.ratingBar)
        val etComments = dialogView.findViewById<EditText>(R.id.editTextComments)
        val btnSubmit = dialogView.findViewById<Button>(R.id.buttonSubmitRating)
        val btnReport = dialogView.findViewById<Button>(R.id.buttonReportIssue)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rate Your Driver")
            .setView(dialogView)
            .create()

        btnSubmit.setOnClickListener {
            val rating = ratingBar.rating
            val comments = etComments.text.toString()
            if (rating > 0f) {
                submitRating(bookingId, driverId, rating, comments)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please provide a rating.", Toast.LENGTH_SHORT).show()
            }
        }

        btnReport.setOnClickListener {
            dialog.dismiss()
            showIssueReportDialog(bookingId, driverId)
        }

        dialog.show()
    }

    private fun submitRating(bookingId: String, driverId: String, rating: Float, comments: String) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val ratingData = com.btsi.swiftcab.models.Rating(
            bookingId = bookingId,
            raterId = uid,
            ratedId = driverId,
            rating = rating,
            comments = comments,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("ratings").add(ratingData)
            .addOnSuccessListener {
                firestore.collection("bookinghistory").document(bookingId)
                    .update("riderRated", true)
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to mark trip as rated: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                Toast.makeText(this, "Rating submitted successfully!", Toast.LENGTH_SHORT).show()
                finish() // Exit booking screen and map after rating submission
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to submit rating: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showIssueReportDialog(bookingId: String, driverId: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_issue_report, null)
        val etCategory = dialogView.findViewById<EditText>(R.id.editTextIssueCategory)
        val etMessage = dialogView.findViewById<EditText>(R.id.editTextIssueMessage)
        val btnSubmitIssue = dialogView.findViewById<Button>(R.id.buttonSubmitIssue)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Report an Issue")
            .setView(dialogView)
            .create()

        btnSubmitIssue.setOnClickListener {
            val category = etCategory.text.toString().trim()
            val message = etMessage.text.toString().trim()
            if (message.isBlank()) {
                Toast.makeText(this, "Please describe the issue.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitIssueReport(bookingId, driverId, category, message)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun submitIssueReport(bookingId: String, driverId: String, category: String, message: String) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }
        val currentRiderId = uid
        val report = com.btsi.swiftcab.models.Report(
            bookingId = bookingId,
            reporterId = uid,
            riderId = currentRiderId,
            driverId = driverId,
            message = message,
            category = category,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("reports").add(report)
            .addOnSuccessListener {
                Toast.makeText(this, "Report submitted. Returning to Home…", Toast.LENGTH_LONG).show()
                val intent = Intent(this, HomeActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to submit report: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
private fun updateDriverRatingSummary(driverId: String?) {
    // Wire reviews button for passengers
    binding.buttonViewAllDriverReviews.isEnabled = !driverId.isNullOrBlank()
    binding.buttonViewAllDriverReviews.setOnClickListener {
        if (!driverId.isNullOrBlank()) {
            val intent = Intent(this, DriverRatingsActivity::class.java)
            intent.putExtra("TARGET_USER_ID", driverId)
            startActivity(intent)
        }
    }

    // Load driver profile image
    loadDriverProfileImage(driverId)

    if (driverId.isNullOrBlank()) {
        binding.driverRatingSummary.visibility = View.GONE
        return
    }
    binding.driverRatingSummary.visibility = View.VISIBLE

    firestore.collection("public").document("driver_rating_summaries_" + driverId)
        .get()
        .addOnSuccessListener { doc ->
            if (!doc.exists()) {
                binding.driverAverageRatingBar.rating = 0f
                binding.textViewDriverRatingCount.text = "0.0 stars (0 total reviews)"
                return@addOnSuccessListener
            }
            val avg = doc.getDouble("average") ?: 0.0
            val count = doc.getLong("count")?.toInt() ?: 0
            binding.driverAverageRatingBar.rating = avg.toFloat()
            val summaryText = String.format(java.util.Locale.getDefault(), "%.1f stars (%d total reviews)", avg, count)
            binding.textViewDriverRatingCount.text = summaryText
        }
        .addOnFailureListener { e ->
            binding.driverRatingSummary.visibility = View.GONE
            Log.e(TAG, "Failed to load driver rating summary (public)", e)
        }
}

private fun loadDriverProfileImage(driverId: String?) {
    if (driverId.isNullOrBlank()) {
        return
    }
    firestore.collection("users").document(driverId).get()
        .addOnSuccessListener { doc ->
            val url = doc.getString("profileImageUrl")
            if (!url.isNullOrBlank()) {
                Glide.with(this)
                    .load(url)
                    .placeholder(R.drawable.ic_driver_profile)
                    .circleCrop()
                    .into(binding.imageViewDriverProfile)
            } else {
                firestore.collection("drivers").document(driverId).get()
                    .addOnSuccessListener { d2 ->
                        val url2 = d2.getString("profileImageUrl")
                        if (!url2.isNullOrBlank()) {
                            Glide.with(this)
                                .load(url2)
                                .placeholder(R.drawable.ic_driver_profile)
                                .circleCrop()
                                .into(binding.imageViewDriverProfile)
                        }
                    }
            }
        }
        .addOnFailureListener {
            // ignore; keep placeholder
        }
}
}
