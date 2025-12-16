
package com.btsi.swiftcab

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.View
import android.view.Gravity
import android.widget.Toast
import android.content.res.ColorStateList
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
import com.google.android.libraries.places.api.model.TypeFilter
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import android.widget.TextView
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

    private lateinit var pickupSearchLauncher: ActivityResultLauncher<Intent>
    private lateinit var dropoffSearchLauncher: ActivityResultLauncher<Intent>

    private var tripTimerHandler: android.os.Handler? = null
    private var tripTimerRunnable: Runnable? = null

    private var searchTimerHandler: android.os.Handler? = null
    private var searchTimerRunnable: Runnable? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    // Correct for server/client clock skew when computing elapsed trip time
    private var serverTimeOffsetMs: Long = 0L

    private enum class SelectionMode { NONE, PICKUP, DROPOFF }
    private var selectionMode: SelectionMode = SelectionMode.NONE

    // Discount selection state
    private var availableDiscountPercent: Int = 0
    private var applyDiscount: Boolean = false
    private var farePopupShown: Boolean = false
    // (Reverted) No multiple discount options list
    private var isPanelCollapsed: Boolean = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "BookingActivity"
    }

    // Estimated fare calculation
    private val BASE_FARE = 50.0
    private val PER_KM_RATE = 13.5

    /**
     * Computes great‑circle distance between two coordinates using the haversine formula.
     *
     * @param a origin coordinate
     * @param b destination coordinate
     * @return distance in kilometers
     */
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

    /**
     * Updates the estimated fare label when both pickup and destination are set.
     * Applies a discount when enabled.
     */
    private fun updateEstimatedFareIfReady() {
        val pickup = pickupLocationLatLng
        val dest = destinationLatLng
        if (pickup != null && dest != null) {
            val apiKey = getString(R.string.google_maps_key)
            val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${pickup.latitude},${pickup.longitude}&destination=${dest.latitude},${dest.longitude}&mode=driving&key=$apiKey"
            lifecycleScope.launch(Dispatchers.IO) {
                var estimatedFare = 0.0
                try {
                    val result = URL(url).readText()
                    val jsonObject = JSONObject(result)
                    val routes = jsonObject.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val legs = routes.getJSONObject(0).getJSONArray("legs")
                        if (legs.length() > 0) {
                            val meters = legs.getJSONObject(0).getJSONObject("distance").getInt("value")
                            val distanceKm = meters / 1000.0
                            estimatedFare = BASE_FARE + PER_KM_RATE * distanceKm
                        }
                    }
                } catch (_: Exception) {
                    val distanceKm = calculateDistanceKm(pickup, dest)
                    estimatedFare = BASE_FARE + PER_KM_RATE * distanceKm
                }
                if (applyDiscount && availableDiscountPercent > 0) {
                    val factor = 1.0 - (availableDiscountPercent / 100.0)
                    estimatedFare *= factor
                }
                withContext(Dispatchers.Main) {
                    binding.textViewEstimatedFare.text = String.format(java.util.Locale.getDefault(), "Estimated Fare: ₱%.2f", estimatedFare)
                }
            }
        }
    }

    private fun updateRoutePreviewIfReady() {
        val pickup = pickupLocationLatLng
        val dest = destinationLatLng
        if (pickup != null && dest != null) {
            getDirectionsAndDrawRoute(pickup, dest)
        }
    }

    /**
     * Sets up bindings, ViewModel, Google Play services, map, and UI listeners.
     * Restores any active booking and initializes discount/time offset.
     */
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
        setupPlaceSearchButtons()
        observeViewModel()
        // Attempt to resume any active booking for this rider
        viewModel.resumeActiveBookingIfAny()
    }

    /**
     * Wires up primary UI interactions: panel toggle, booking confirm, selection modes,
     * current‑location shortcuts, back/cancel actions, and initial card visibility.
     */
    private fun setupUI() {
        // Minimize/expand the booking panel to reveal more of the map
        binding.buttonTogglePanel.setOnClickListener {
            isPanelCollapsed = !isPanelCollapsed
            binding.bookingPanelContent.visibility = if (isPanelCollapsed) View.GONE else View.VISIBLE
            binding.buttonTogglePanel.setImageResource(
                if (isPanelCollapsed) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
            )
            binding.buttonTogglePanel.contentDescription = if (isPanelCollapsed) "Expand panel" else "Collapse panel"
        }

        binding.buttonConfirmBooking.setOnClickListener {
            createBookingRequest()
        }
        binding.buttonSelectPickupMode.setOnClickListener {
            selectionMode = SelectionMode.PICKUP
            showTopBanner("Tap on the map to set Pickup")
        }
        binding.buttonSelectDropoffMode.setOnClickListener {
            selectionMode = SelectionMode.DROPOFF
            showTopBanner("Tap on the map to set Drop-off")
        }
        binding.buttonCurrentLocationPickup.setOnClickListener {
            setCurrentLocationAs(true)
        }
        binding.buttonCurrentLocationDropoff.setOnClickListener {
            setCurrentLocationAs(false)
        }
        binding.backButtonBooking.setOnClickListener { finish() }

        // Cancel actions
        binding.buttonCancelWhileFinding.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cancel booking?")
                .setMessage("Are you sure you want to cancel this booking?")
                .setPositiveButton("Yes, cancel") { dialog, _ ->
                    it.isEnabled = false
                    viewModel.cancelBooking()
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.GONE
                    binding.infoCardView.visibility = View.VISIBLE
                    binding.noDriversLayout.visibility = View.VISIBLE
                    binding.textViewNoDriversMessage.text = "Booking canceled."
                    it.postDelayed({ it.isEnabled = true }, 800)
                    dialog.dismiss()
                }
                .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        binding.buttonCancelRideRider.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cancel ride?")
                .setMessage("Are you sure you want to cancel this ride?")
                .setPositiveButton("Yes, cancel") { dialog, _ ->
                    viewModel.cancelBooking()
                    hideBookingStatusCard()
                    binding.noDriversLayout.visibility = View.VISIBLE
                    binding.textViewNoDriversMessage.text = "Booking canceled."
                    dialog.dismiss()
                }
                .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    /**
     * Configures and launches Places Autocomplete for pickup and drop‑off search.
     * Updates markers and fare after selections.
     */
    private fun setupPlaceSearchButtons() {
        if (!playServicesAvailable) return

        val placeFields = listOf(
            Place.Field.ID,
            Place.Field.NAME,
            Place.Field.ADDRESS,
            Place.Field.LAT_LNG
        )

        pickupSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val place = Autocomplete.getPlaceFromIntent(result.data!!)
                place.latLng?.let { latLng ->
                    pickupLocationLatLng = latLng
                    binding.pickupLocationEditText.setText(place.address ?: place.name ?: "")
                    setPickupMarker(latLng)
                    selectionMode = SelectionMode.NONE
                    updateEstimatedFareIfReady()
                }
            } else if (result.data != null) {
                val status = Autocomplete.getStatusFromIntent(result.data!!)
                Toast.makeText(this, "Pickup selection error: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        dropoffSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val place = Autocomplete.getPlaceFromIntent(result.data!!)
                place.latLng?.let { latLng ->
                    destinationLatLng = latLng
                    binding.destinationEditText.setText(place.address ?: place.name ?: "")
                    setDestinationMarker(latLng)
                    selectionMode = SelectionMode.NONE
                    updateEstimatedFareIfReady()
                }
            } else if (result.data != null) {
                val status = Autocomplete.getStatusFromIntent(result.data!!)
                Toast.makeText(this, "Drop-off selection error: ${status.statusMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonPickupSearch.setOnClickListener {
            val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.FULLSCREEN, placeFields)
                .setTypeFilter(TypeFilter.ADDRESS)
                .build(this)
            pickupSearchLauncher.launch(intent)
        }

        binding.buttonDestinationSearch.setOnClickListener {
            val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.FULLSCREEN, placeFields)
                .setTypeFilter(TypeFilter.ADDRESS)
                .build(this)
            dropoffSearchLauncher.launch(intent)
        }
    }

    /**
     * Sets pickup or drop‑off to the device’s last known location if permitted.
     * Updates markers, address fields, and estimated fare.
     *
     * @param isPickup true to set pickup, false to set drop‑off
     */
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

    /**
     * Reverse‑geocodes a coordinate into a human‑readable address string.
     * Falls back to "lat, lng" when geocoder fails.
     *
     * @param latLng coordinate to resolve
     * @return address string or "lat, lng"
     */
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

    /**
     * Places or updates the pickup marker and centers the map on it.
     * Also refreshes the estimated fare.
     *
     * @param latLng pickup position
     */
    private fun setPickupMarker(latLng: LatLng) {
        pickupMarker?.remove()
        pickupMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Pickup")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        updateEstimatedFareIfReady()
        updateRoutePreviewIfReady()
    }

    /**
     * Places or updates the drop‑off marker and centers the map on it.
     * Also refreshes the estimated fare.
     *
     * @param latLng drop‑off position
     */
    private fun setDestinationMarker(latLng: LatLng) {
        destinationMarker?.remove()
        destinationMarker = mMap?.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Drop-off")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        updateEstimatedFareIfReady()
        updateRoutePreviewIfReady()
    }

    /**
     * Receives the GoogleMap instance, sets tap listener, and enables my‑location
     * when Google Play services and permissions are available.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap?.setOnMapClickListener { latLng -> onMapTapped(latLng) }
        if (playServicesAvailable) {
            enableMyLocationIfPermitted()
        }
    }

    /**
     * Enables the map’s my‑location layer if location permissions are granted;
     * otherwise requests permissions.
     */
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

    /**
     * Handles map taps based on the current selection mode to set pickup or drop‑off.
     * Updates markers, address fields, and resets selection mode.
     *
     * @param latLng tapped coordinate
     */
    private fun onMapTapped(latLng: LatLng) {
        when (selectionMode) {
            SelectionMode.PICKUP -> {
                pickupLocationLatLng = latLng
                setPickupMarker(latLng)
                val address = getAddressFromLatLng(latLng)
                binding.pickupLocationEditText.setText(address)
                showTopBanner("Pickup point selected")
                selectionMode = SelectionMode.NONE
            }
            SelectionMode.DROPOFF -> {
                destinationLatLng = latLng
                setDestinationMarker(latLng)
                val address = getAddressFromLatLng(latLng)
                binding.destinationEditText.setText(address)
                showTopBanner("Drop-off point selected")
                selectionMode = SelectionMode.NONE
            }
            else -> {
                // No-op
            }
        }
        updateEstimatedFareIfReady()
    }

    /**
     * Displays a transient top banner with the provided message and auto‑hides it.
     *
     * @param message text to show in the banner
     */
    private fun showTopBanner(message: String) {
        val banner = binding.topNotificationBanner
        val text = binding.textViewTopBanner
        text.text = message
        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.translationY = -banner.height.toFloat()
            banner.visibility = View.VISIBLE
        }
        banner.post {
            banner.translationY = -banner.height.toFloat()
            banner.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        // Auto-hide after short delay
        banner.removeCallbacks(hideBannerRunnable)
        banner.postDelayed(hideBannerRunnable, 2200)
    }

    private val hideBannerRunnable = Runnable { hideTopBanner() }

    /**
     * Animates and hides the top notification banner if visible.
     */
    private fun hideTopBanner() {
        val banner = binding.topNotificationBanner
        if (banner.visibility == View.VISIBLE) {
            banner.animate()
                .alpha(0f)
                .translationY(-banner.height.toFloat())
                .setDuration(200)
                .withEndAction { banner.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Observes booking UI state and updates cards, markers, routes, timers,
     * fare labels, and review prompts accordingly.
     */
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
                    val exp = state.expiresAt
                    if (exp != null) {
                        startSearchCountdown(exp)
                    } else {
                        stopSearchCountdown()
                    }
                }
                is BookingUiState.DriverOnTheWay -> {
                    stopSearchCountdown()
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Driver on the way"
                    binding.textViewTripStatusMessage.visibility = View.GONE
                    binding.buttonCancelRideRider.visibility = View.VISIBLE

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
                    stopSearchCountdown()
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Driver arrived"
                    binding.textViewTripStatusMessage.text = state.message
                    binding.textViewTripStatusMessage.visibility = View.VISIBLE
                    binding.buttonCancelRideRider.visibility = View.VISIBLE

                    pickupLocationLatLng = state.pickupLocation
                    destinationLatLng = state.dropOffLocation
                    setPickupMarker(state.pickupLocation)
                    setDestinationMarker(state.dropOffLocation)
                    updateEstimatedFareIfReady()
                }
                is BookingUiState.TripInProgress -> {
                    stopSearchCountdown()
                    binding.infoCardView.visibility = View.GONE
                    binding.bookingStatusCardView.visibility = View.VISIBLE
                    binding.findingDriverLayout.visibility = View.GONE
                    binding.noDriversLayout.visibility = View.GONE
                    binding.textViewBookingStatusHeader.text = "Trip in progress"
                    binding.textViewTripStatusMessage.visibility = View.GONE
                    binding.buttonCancelRideRider.visibility = View.GONE

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
                    stopSearchCountdown()
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

                    val beforeDiscountTotal = base + (km * perKm) + (minutes * perMin)
                    val discountAmount = (beforeDiscountTotal - total).coerceAtLeast(0.0)

                    binding.textViewFinalFare.text = String.format("Fare: ₱%.2f", total)
                    binding.textViewDuration.text = String.format("Duration: %d min", minutes)
                    binding.textViewFareBreakdown.text = String.format(
                        "Breakdown: Base ₱%.2f + %.2f km × ₱%.2f + %d min × ₱%.2f = ₱%.2f",
                        base, km, perKm, minutes, perMin, beforeDiscountTotal
                    )
                    if (discountAmount > 0.0) {
                        binding.textViewDiscountApplied.visibility = View.VISIBLE
                        binding.textViewDiscountApplied.text = String.format("Discount: - ₱%.2f", discountAmount)
                    } else {
                        binding.textViewDiscountApplied.visibility = View.GONE
                    }

                    clearDriverMarker()
                    clearRoute()
                    showRiderRatingDialog(state.bookingId, state.driverId)

                    // Centered fare popup once when trip is completed
                    if (!farePopupShown) {
                        val totalFare = state.finalFare ?: 0.0
                        val beforeDiscountTotal = base + (km * perKm) + (minutes * perMin)
                        val discountAmount = (beforeDiscountTotal - totalFare).coerceAtLeast(0.0)
                        showRiderFarePopup(totalFare, beforeDiscountTotal, discountAmount, minutes)
                        farePopupShown = true
                    }
                }
                is BookingUiState.AwaitingPayment -> {
                    stopSearchCountdown()
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

                    val beforeDiscountTotal = base + (km * perKm) + (minutes * perMin)
                    val discountAmount = (beforeDiscountTotal - total).coerceAtLeast(0.0)

                    binding.textViewFinalFare.text = String.format("Fare: ₱%.2f", total)
                    binding.textViewDuration.text = String.format("Duration: %d min", minutes)
                    binding.textViewFareBreakdown.text = String.format(
                        "Breakdown: Base ₱%.2f + %.2f km × ₱%.2f + %d min × ₱%.2f = ₱%.2f",
                        base, km, perKm, minutes, perMin, beforeDiscountTotal
                    )
                    if (discountAmount > 0.0) {
                        binding.textViewDiscountApplied.visibility = View.VISIBLE
                        binding.textViewDiscountApplied.text = String.format("Discount: - ₱%.2f", discountAmount)
                    } else {
                        binding.textViewDiscountApplied.visibility = View.GONE
                    }

                    clearDriverMarker()
                    clearRoute()
                    // Do NOT show review until payment is confirmed

                    // Centered fare popup once when awaiting payment
                    if (!farePopupShown) {
                        val totalFare = state.finalFare ?: 0.0
                        val beforeDiscountTotal2 = base + (km * perKm) + (minutes * perMin)
                        val discountAmount2 = (beforeDiscountTotal2 - totalFare).coerceAtLeast(0.0)
                        showRiderFarePopup(totalFare, beforeDiscountTotal2, discountAmount2, minutes)
                        farePopupShown = true
                    }
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

    /**
     * Shows a centered fare summary popup for the rider including discounts and duration.
     *
     * @param totalFare final fare after discounts
     * @param subtotal fare before discounts
     * @param discount discount amount applied
     * @param minutes trip duration in minutes
     */
    private fun showRiderFarePopup(totalFare: Double, subtotal: Double, discount: Double, minutes: Int) {
        try {
            val view = layoutInflater.inflate(R.layout.dialog_fare_summary_rider, null)
            val fareView = view.findViewById<TextView>(R.id.textFareAmount)
            val discountView = view.findViewById<TextView>(R.id.textDiscount)
            val durationView = view.findViewById<TextView>(R.id.textDuration)

            fareView.text = String.format(java.util.Locale.getDefault(), "Fare: ₱%.2f", totalFare)
            discountView.visibility = View.VISIBLE
            if (discount > 0.0) {
                discountView.text = String.format(java.util.Locale.getDefault(), "Discount: - ₱%.2f", discount)
            } else {
                discountView.text = String.format(java.util.Locale.getDefault(), "Discount: ₱%.2f", 0.0)
            }
            durationView.text = String.format(java.util.Locale.getDefault(), "Duration: %d min", minutes)

            val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .create()
            dlg.show()
        } catch (e: Exception) {
            android.util.Log.e("BookingActivity", "Failed to show rider fare popup", e)
        }
    }

    /**
     * Retrieves directions via Google Directions API, draws a polyline, and fits camera.
     *
     * @param origin starting coordinate
     * @param destination ending coordinate
     */
    private fun getDirectionsAndDrawRoute(origin: LatLng, destination: LatLng) {
        val apiKey = getString(R.string.google_maps_key)
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&mode=driving&key=$apiKey"

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

    /**
     * Decodes an encoded polyline string into a list of coordinates.
     *
     * @param encoded encoded polyline from Directions API
     * @return list of `LatLng` points
     */
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

    /**
     * Refreshes pickup and destination markers to match current state and fare.
     */
    private fun updateMarkers() {
        // Preserve existing markers and route; only update pickup/destination markers
        pickupLocationLatLng?.let { setPickupMarker(it) }
        destinationLatLng?.let { setDestinationMarker(it) }
        updateEstimatedFareIfReady()
    }

    /**
     * Creates or moves the driver marker to the provided location.
     *
     * @param latLng driver position
     */
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

    /**
     * Removes the driver marker from the map.
     */
    private fun clearDriverMarker() {
        driverMarker?.remove()
        driverMarker = null
    }

    /**
     * Converts a vector/drawable resource into a `BitmapDescriptor` for map markers.
     *
     * @param drawableId resource ID of the drawable
     * @return a bitmap descriptor for map usage
     */
    private fun bitmapDescriptorFromVector(drawableId: Int): BitmapDescriptor {
        val drawable = ContextCompat.getDrawable(this, drawableId) ?: return BitmapDescriptorFactory.defaultMarker()
        val targetSizePx = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(32)
        val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * Clears the current route polyline from the map.
     */
    private fun clearRoute() {
        currentPolyline?.remove()
        currentPolyline = null
    }

    /**
     * Hides the booking status card and shows the info card.
     */
    private fun hideBookingStatusCard() {
        binding.bookingStatusCardView.visibility = View.GONE
        binding.infoCardView.visibility = View.VISIBLE
    }

    /**
     * Starts a UI timer for per‑minute fees and updates fare breakdown labels.
     * Uses server time offset to correct client skew.
     *
     * @param startMillis server/start timestamp in millis
     * @param perMinuteRate rate charged per minute
     * @param initialFee base/initial fee shown upfront
     */
    private fun startTripTimer(startMillis: Long, perMinuteRate: Double, initialFee: Double) {
        Log.d("BookingActivity", "startTripTimer called with startMillis: $startMillis, perMinuteRate: $perMinuteRate, initialFee: $initialFee")

        // Ensure any previous timer is fully stopped before starting a new one
        stopTripTimer()

        val handler = android.os.Handler(mainLooper)
        tripTimerHandler = handler

        // Show initial fee immediately
        binding.textViewInitialFee.visibility = View.VISIBLE
        binding.textViewInitialFee.text = String.format("Initial fee: ₱%.2f", initialFee)

        // Compute and show km fee using road distance
        val pickupPos = pickupMarker?.position
        val destPos = destinationMarker?.position
        var kmFeeValue = 0.0
        binding.textViewKmFee.visibility = View.GONE
        if (pickupPos != null && destPos != null) {
            val apiKey = getString(R.string.google_maps_key)
            val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${pickupPos.latitude},${pickupPos.longitude}&destination=${destPos.latitude},${destPos.longitude}&mode=driving&key=$apiKey"
            lifecycleScope.launch(Dispatchers.IO) {
                var distanceKm = 0.0
                try {
                    val result = URL(url).readText()
                    val jsonObject = JSONObject(result)
                    val routes = jsonObject.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val legs = routes.getJSONObject(0).getJSONArray("legs")
                        if (legs.length() > 0) {
                            val meters = legs.getJSONObject(0).getJSONObject("distance").getInt("value")
                            distanceKm = meters / 1000.0
                        }
                    }
                } catch (_: Exception) {
                    distanceKm = calculateDistanceKm(pickupPos, destPos)
                }
                kmFeeValue = distanceKm * PER_KM_RATE
                withContext(Dispatchers.Main) {
                    binding.textViewKmFee.visibility = View.VISIBLE
                    binding.textViewKmFee.text = String.format("Km fee (₱%.1f/km): %.2f km | ₱%.2f", PER_KM_RATE, distanceKm, kmFeeValue)
                }
            }
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

                // Compute and show running total fare, applying discount if chosen
                val totalBeforeDiscount = initialFee + kmFeeValue + timeFee
                val discountPercent = if (applyDiscount && availableDiscountPercent > 0) availableDiscountPercent else 0
                val discountAmount = if (discountPercent > 0) totalBeforeDiscount * (discountPercent / 100.0) else 0.0
                val totalFare = totalBeforeDiscount - discountAmount

                if (discountPercent > 0) {
                    binding.textViewDiscountApplied.visibility = View.VISIBLE
                    binding.textViewDiscountApplied.text = String.format("Discount (%d%%): - ₱%.2f", discountPercent, discountAmount)
                } else {
                    binding.textViewDiscountApplied.visibility = View.GONE
                }

                binding.textViewFinalFare.visibility = View.VISIBLE
                binding.textViewFinalFare.text = String.format("Fare: ₱%.2f", totalFare)

                handler.postDelayed(this, 1000L)
            }
        }
        // Run first tick immediately so the timer appears to start instantly
        tripTimerRunnable!!.run()
    }

    /**
     * Stops and clears the running trip timer and hides timer‑related labels.
     */
    private fun stopTripTimer() {
        Log.d("BookingActivity", "stopTripTimer called")
        tripTimerRunnable?.let { tripTimerHandler?.removeCallbacks(it) }
        tripTimerRunnable = null
        binding.textViewTimeFee.visibility = View.GONE
        binding.textViewInitialFee.visibility = View.GONE
        binding.textViewKmFee.visibility = View.GONE
        // Keep final fare visible for AwaitingPayment/Completed states; hide here only if card switches
    }

    private fun startSearchCountdown(expiresAt: Long) {
        stopSearchCountdown()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        searchTimerHandler = handler
        searchTimerRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val remainingMs = (expiresAt - now).coerceAtLeast(0)
                val minutes = (remainingMs / 60000).toInt()
                val seconds = ((remainingMs % 60000) / 1000).toInt()
                binding.textViewSearchCountdown.visibility = View.VISIBLE
                binding.textViewSearchCountdown.text = String.format("Time remaining: %02d:%02d", minutes, seconds)
                if (remainingMs > 0) {
                    handler.postDelayed(this, 1000L)
                } else {
                    handler.removeCallbacks(this)
                }
            }
        }
        searchTimerRunnable!!.run()
    }

    private fun stopSearchCountdown() {
        searchTimerRunnable?.let { searchTimerHandler?.removeCallbacks(it) }
        searchTimerRunnable = null
        binding.textViewSearchCountdown.visibility = View.GONE
    }

    /**
     * Reads Firebase server time offset to correct client time for timers.
     */
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

    /**
     * Refreshes discount indicator when the activity resumes.
     */
    override fun onResume() {
        super.onResume()
        // Refresh discount indicator in case it changed (e.g., after redemption)
        updateDiscountIndicator()
    }

    /**
     * Loads and toggles the rider’s next‑booking discount and updates UI state.
     */
    private fun updateDiscountIndicator() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            binding.discountCard.visibility = View.GONE
            return
        }
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val percent = (doc.getLong("nextBookingDiscountPercent") ?: 0L).toInt()
                availableDiscountPercent = percent
                if (percent > 0) {
                    binding.discountCard.visibility = View.VISIBLE
                    binding.textViewDiscountPercent.text = "$percent% off your next ride"
            binding.buttonToggleDiscount.text = if (applyDiscount) "Discount Active" else "Discount Disabled"
                    val enabledColor = ContextCompat.getColor(this, R.color.discount_enabled_bg)
                    val disabledColor = ContextCompat.getColor(this, R.color.discount_disabled_bg)
                    binding.buttonToggleDiscount.backgroundTintList = ColorStateList.valueOf(if (applyDiscount) enabledColor else disabledColor)
            binding.buttonToggleDiscount.contentDescription = if (applyDiscount) "Discount active" else "Discount disabled"
                    binding.buttonToggleDiscount.setOnClickListener {
                        applyDiscount = !applyDiscount
        binding.buttonToggleDiscount.text = if (applyDiscount) "Discount Active" else "Discount Disabled"
                        binding.buttonToggleDiscount.backgroundTintList = ColorStateList.valueOf(if (applyDiscount) enabledColor else disabledColor)
        binding.buttonToggleDiscount.contentDescription = if (applyDiscount) "Discount active" else "Discount disabled"
                        updateEstimatedFareIfReady()
                    }
                } else {
                    applyDiscount = false
                    binding.discountCard.visibility = View.GONE
                }
            }
            .addOnFailureListener {
                // On failure, hide to avoid stale/incorrect info
                binding.discountCard.visibility = View.GONE
            }
    }

    /**
     * Handles location permission result and enables my‑location if granted.
     */
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
    /**
     * Validates inputs and requests a booking via the ViewModel.
     * Applies discount selection and addresses.
     */
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
            destinationAddress = destinationAddress,
            applyDiscount = applyDiscount,
            availableDiscountPercent = availableDiscountPercent
        )
    }

    /**
     * Displays a rating dialog for the rider with submit and report options.
     *
     * @param bookingId the completed booking ID
     * @param driverId the driver to rate or report
     */
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

    /**
     * Persists a rider’s rating to Firestore and marks the trip as rated.
     *
     * @param bookingId rated booking ID
     * @param driverId driver being rated
     * @param rating rating value (0.0–5.0)
     * @param comments optional comments
     */
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
                val riderId = uid
                firestore.collection("bookinghistory").document(bookingId)
                    .set(mapOf("riderRated" to true, "riderId" to riderId), com.google.firebase.firestore.SetOptions.merge())
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

    /**
     * Shows an issue report dialog for the rider to submit a problem.
     *
     * @param bookingId the booking related to the issue
     * @param driverId the driver involved
     */
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

    /**
     * Submits a rider issue report to Firestore and navigates to Home on success.
     *
     * @param bookingId the booking being reported
     * @param driverId driver associated with the booking
     * @param category optional issue category
     * @param message description of the issue
     */
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
/**
 * Loads driver rating summary and wires navigation to full reviews.
 *
 * @param driverId target driver user ID
 */
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

    firestore.collection("AVGrating").document("driver_rating_summaries_" + driverId)
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

/**
 * Loads the driver’s profile image from user or driver collection and binds it.
 *
 * @param driverId target driver user ID
 */
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
