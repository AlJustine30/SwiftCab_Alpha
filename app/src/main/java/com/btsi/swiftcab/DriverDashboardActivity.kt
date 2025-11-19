package com.btsi.swiftcab

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RatingBar
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.btsi.swiftcab.databinding.ActivityDriverDashboardBinding
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import android.widget.ImageView
import com.bumptech.glide.Glide

class DriverDashboardActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityDriverDashboardBinding
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var mMap: GoogleMap? = null
    private var currentBooking: BookingRequest? = null
    private var currentPolyline: Polyline? = null
    private var farePopupShown: Boolean = false
    private var serverTimeOffsetMs: Long = 0L
    private var driverTripTimerHandler: android.os.Handler? = null
    private var driverTripTimerRunnable: Runnable? = null
    private val PER_KM_RATE = 13.5


    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance()
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private var driverRef: DatabaseReference? = null
    private var offersListenerReference: DatabaseReference? = null
    private var offersValueListener: ValueEventListener? = null
    // NEW: Offers list and adapter
    private var offersAdapter: DriverOffersAdapter? = null
    private val offersList: MutableList<BookingRequest> = mutableListOf()
    private var currentDriverLatLng: LatLng? = null
    private var activeBookingListener: ValueEventListener? = null
    private var activeBookingRef: DatabaseReference? = null
    private var playServicesAvailable: Boolean = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val TAG = "DriverDashboard"
    }

    /**
     * Initializes bindings, Play Services, map, server time offset,
     * and sets up toolbar, drawer, and UI listeners.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize server time offset to align client clock with Firebase ServerValue.TIMESTAMP
        initServerTimeOffset()

        playServicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.driver_map) as? SupportMapFragment
        if (playServicesAvailable) {
            mapFragment?.getMapAsync(this)
        } else {
            Toast.makeText(this, "Google Play services not available; map disabled.", Toast.LENGTH_SHORT).show()
        }

        setupToolbarAndDrawer()
        setupUI()
    }

    /**
     * Receives the map, enables my‑location when permitted and services available,
     * and shows a notice otherwise.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        // Enable my location button on the map only if Play Services available
        if (playServicesAvailable && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
        } else if (!playServicesAvailable) {
            Toast.makeText(this, "Google Play services not available; map location disabled.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Configures the toolbar and navigation drawer for the driver dashboard.
     */
    private fun setupToolbarAndDrawer() {
        setSupportActionBar(binding.toolbarDriverDashboard)
        toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayoutDriverDashboard,
            binding.toolbarDriverDashboard,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayoutDriverDashboard.addDrawerListener(toggle)
        toggle.syncState()
        toggle.isDrawerIndicatorEnabled = true
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    /**
     * Wires UI interactions: online/offline switch, nav items, offers list,
     * trip action button, and initial status.
     */
    private fun setupUI() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            finish()
            return
        }

        binding.textViewDriverWelcome.text = "Welcome, ${currentUser.displayName ?: "Driver"}"
        // Load actual driver name and profile image
        loadCurrentDriverProfile()
        // Default to offline color until switch toggled
        updateStatusHeaderColor(false)
        driverRef = db.getReference("drivers").child(currentUser.uid)

        binding.switchDriverStatus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) goOnline() else goOffline()
        }

        binding.navViewDriver.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayoutDriverDashboard.closeDrawers() // Close drawer on item click

            when (menuItem.itemId) {
                R.id.nav_driver_dashboard_home -> {
                    Toast.makeText(this, "Dashboard", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_driver_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                }
                R.id.nav_driver_ride_history -> {
                    startActivity(Intent(this, DriverBookingHistoryActivity::class.java))
                }
                R.id.nav_driver_ratings -> {
                    startActivity(Intent(this, DriverRatingsActivity::class.java))
                }
                R.id.nav_driver_earnings -> {
                    startActivity(Intent(this, DriverEarningsActivity::class.java))
                }
                R.id.nav_driver_settings -> {
                    Toast.makeText(this, "Settings clicked", Toast.LENGTH_SHORT).show()
                }
                R.id.nav_driver_logout -> {
                    goOffline()
                    auth.signOut()
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
            }
            true
        }


        binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }

        // NEW: Setup offers RecyclerView
        binding.recyclerViewDriverOffers.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@DriverDashboardActivity)
        }
        offersAdapter = DriverOffersAdapter(
            offersList,
            currentDriverLatLng,
            onAccept = { offer ->
                acceptBooking(offer.bookingId)
                // Remove from local list to reflect decision
                offer.bookingId?.let { id ->
                    val idx = offersList.indexOfFirst { it.bookingId == id }
                    if (idx >= 0) {
                        offersList.removeAt(idx)
                        offersAdapter?.notifyItemRemoved(idx)
                    }
                }
            },
            onDecline = { offer ->
                val driverId = auth.currentUser?.uid
                val bookingId = offer.bookingId
                if (driverId != null && bookingId != null) {
                    db.getReference("driverOffers").child(driverId).child(bookingId).removeValue()
                }
                // Update local list
                val idx = offersList.indexOfFirst { it.bookingId == bookingId }
                if (idx >= 0) {
                    offersList.removeAt(idx)
                    offersAdapter?.notifyItemRemoved(idx)
                }
            }
        )
        binding.recyclerViewDriverOffers.adapter = offersAdapter
    }

    /**
     * Starts periodic location updates to update driver presence, offers distance,
     * and active booking driverLocation.
     */
    private fun startLocationUpdates() {
        if (!playServicesAvailable) return
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val driverData = mapOf(
                        "location/latitude" to location.latitude,
                        "location/longitude" to location.longitude,
                        "isOnline" to true
                    )
                    // Preserve metadata like displayName and vehicleDetails
                    driverRef?.updateChildren(driverData)

                    // Update local driver location for offer distance
                    currentDriverLatLng = LatLng(location.latitude, location.longitude)
                    offersAdapter?.updateDriverLocation(currentDriverLatLng)

                    // If there is an active booking, update its driverLocation
                    currentBooking?.bookingId?.let { bookingId ->
                        if (currentBooking?.status == "EN_ROUTE_TO_PICKUP" || currentBooking?.status == "ARRIVED_AT_PICKUP" || currentBooking?.status == "EN_ROUTE_TO_DROPOFF") {
                            db.getReference("bookingRequests").child(bookingId).child("driverLocation")
                                .setValue(mapOf("latitude" to location.latitude, "longitude" to location.longitude))
                        }
                    }
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    /**
     * Sets the driver offline, stops location and listeners, clears realtime presence,
     * and resets UI to the default view.
     */
    private fun goOffline() {
        binding.switchDriverStatus.text = getString(R.string.status_offline)
        updateStatusHeaderColor(false)
        stopLocationUpdates()
        driverRef?.onDisconnect()?.cancel()
        driverRef?.removeValue()
        detachDriverOffersListener()
        detachActiveBookingListener()
        showDefaultView()
        Toast.makeText(this, "You are now offline", Toast.LENGTH_SHORT).show()
    }

    /**
     * Copies driver profile info (name, phone, vehicle) from Firestore into Realtime DB
     * to ensure rider UIs show details consistently.
     */
    private fun backfillDriverProfileToRealtime() {
        val uid = auth.currentUser?.uid ?: return
        val defaultName = auth.currentUser?.displayName

        firestore.collection("drivers").document(uid).get()
            .addOnSuccessListener { driverDoc ->
                val adminName = driverDoc.getString("name")
                val adminPhone = driverDoc.getString("phone")
                val vehicleMap = driverDoc.get("vehicle") as? Map<*, *>
                val make = vehicleMap?.get("make") as? String
                val model = vehicleMap?.get("model") as? String
                val color = vehicleMap?.get("color") as? String
                val plate = vehicleMap?.get("licensePlate") as? String
                val year = vehicleMap?.get("year")?.toString()

                val vehicleDetails = listOfNotNull(
                    listOfNotNull(make, model).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
                    color?.takeIf { it.isNotBlank() },
                    plate?.let { "Plate $it" },
                    year?.let { "Year $it" }
                ).joinToString(", ")

                val resolvedName = (adminName?.takeIf { it.isNotBlank() } ?: defaultName ?: "Your Driver")
                val updates = mutableMapOf<String, Any>(
                    "displayName" to resolvedName,
                )
                if (!vehicleDetails.isNullOrBlank()) updates["vehicleDetails"] = vehicleDetails else updates["vehicleDetails"] = "Vehicle"
                if (!adminPhone.isNullOrBlank()) updates["phone"] = adminPhone

                driverRef?.updateChildren(updates)
            }
            .addOnFailureListener {
                val resolvedName = (defaultName ?: "Your Driver")
                val updates = mutableMapOf<String, Any>(
                    "displayName" to resolvedName,
                    "vehicleDetails" to "Vehicle"
                )
                driverRef?.updateChildren(updates)
            }
    }

    /**
     * Stops location updates if previously started.
     */
    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    /**
     * Subscribes to driverOffers for this driver and updates the offers list UI.
     */
    private fun attachDriverOffersListener() {
        val driverId = auth.currentUser?.uid ?: return
        offersListenerReference = db.getReference("driverOffers").child(driverId)

        offersValueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newOffers = mutableListOf<BookingRequest>()
                snapshot.children.forEach { child ->
                    val offer = child.getValue(BookingRequest::class.java)
                    if (offer != null) newOffers.add(offer)
                }
                if (newOffers.isNotEmpty()) {
                    binding.bookingRequestLayout.visibility = View.GONE
                    binding.recyclerViewDriverOffers.visibility = View.VISIBLE
                    offersAdapter?.setOffers(newOffers)
                } else {
                    binding.recyclerViewDriverOffers.visibility = View.GONE
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Driver offers listener cancelled.", error.toException())
            }
        }
        offersListenerReference?.addValueEventListener(offersValueListener!!)
    }

    /**
     * Unsubscribes the driverOffers listener if present.
     */
    private fun detachDriverOffersListener() {
        offersValueListener?.let { listener ->
            offersListenerReference?.removeEventListener(listener)
        }
    }

    /**
     * Displays a booking offer card with passenger and route details, allowing accept/decline.
     */
    private fun showBookingOfferDialog(bookingRequest: BookingRequest) {
        binding.bookingRequestLayout.visibility = View.VISIBLE
        binding.textViewPickupLocationInfo.text = "Pickup: ${bookingRequest.pickupAddress}"
        binding.textViewDestinationLocationInfo.text = "Destination: ${bookingRequest.destinationAddress}"
        binding.textViewPassengerNameInfo.text = "Passenger: ${bookingRequest.riderName ?: "Unknown"}"
        binding.textViewPassengerPhoneInfo.text = "Phone: ${bookingRequest.riderPhone ?: "Unknown"}"

        // Load passenger image for booking offer
        loadPassengerProfileImage(bookingRequest.riderId, binding.imageViewPassengerProfileRequest)

        binding.buttonAcceptBooking.setOnClickListener {
            acceptBooking(bookingRequest.bookingId)
        }
        binding.buttonDeclineBooking.setOnClickListener {
            binding.bookingRequestLayout.visibility = View.GONE
            attachDriverOffersListener()
        }
    }

    /**
     * Requests to accept a booking via Cloud Function and starts listening to it.
     */
    private fun acceptBooking(bookingId: String?) {
        if (bookingId == null) return

        functions.getHttpsCallable("acceptBooking")
            .call(hashMapOf("bookingId" to bookingId))
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Booking Accepted!", Toast.LENGTH_SHORT).show()
                    binding.bookingRequestLayout.visibility = View.GONE
                    farePopupShown = false
                    listenForActiveBooking(bookingId)
                } else {
                    Log.w(TAG, "acceptBooking:onComplete:failure", task.exception)
                    Toast.makeText(this, "Failed to accept booking.", Toast.LENGTH_SHORT).show()
                    attachDriverOffersListener()
                }
            }
    }

    /**
     * Listens for updates to the active booking and updates UI accordingly.
     */
    private fun listenForActiveBooking(bookingId: String) {
        detachActiveBookingListener()
        activeBookingRef = db.getReference("bookingRequests").child(bookingId)
        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val booking = snapshot.getValue(BookingRequest::class.java)
                val previousId = currentBooking?.bookingId
                currentBooking = booking
                if (booking != null && booking.bookingId != previousId) {
                    farePopupShown = false
                }
                if (booking != null && booking.driverId == auth.currentUser?.uid) {
                    // Backfill driver details if missing to ensure rider sees names
                    val uid = auth.currentUser?.uid ?: return
                    val updates = mutableMapOf<String, Any>()
                    if (booking.driverId == null) updates["driverId"] = uid

                    firestore.collection("users").document(uid).get().addOnSuccessListener { doc ->
                        val phone = doc.getString("phone")
                        val firestoreName = doc.getString("name")
                        if (booking.driverPhone.isNullOrEmpty() && phone != null) {
                            updates["driverPhone"] = phone
                        }
                        if (booking.driverName.isNullOrEmpty()) {
                            val nameToUse = auth.currentUser?.displayName?.takeIf { !it.isNullOrBlank() } ?: firestoreName ?: "Driver"
                            updates["driverName"] = nameToUse
                        }
                        if (updates.isNotEmpty()) {
                            activeBookingRef?.updateChildren(updates)
                        }
                    }

                    updateUiForActiveTrip(booking)
                } else {
                    showDefaultView()
                    currentBooking = null
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Active booking listener failed.", error.toException())
                showDefaultView()
            }
        }
        activeBookingRef?.addValueEventListener(activeBookingListener!!)
    }

    /**
     * Unsubscribes the active booking listener if present.
     */
    private fun detachActiveBookingListener() {
        activeBookingListener?.let { listener ->
            activeBookingRef?.removeEventListener(listener)
        }
    }

    /**
     * Updates the active trip card, markers, and route based on booking status.
     */
    private fun updateUiForActiveTrip(booking: BookingRequest) {
        showActiveTripView()
        binding.passengerNameText.text = "Passenger: ${booking.riderName}"
        binding.passengerPhoneText.text = "Phone: ${booking.riderPhone ?: "Unknown"}"
        binding.pickupAddressText.text = "Pickup: ${booking.pickupAddress}"
        binding.dropoffAddressText.text = "Dropoff: ${booking.destinationAddress}"

        // Load passenger image for active trip card
        loadPassengerProfileImage(booking.riderId, binding.imageViewPassengerProfileTrip)

        val pickupLatLng = LatLng(booking.pickupLatitude ?: 0.0, booking.pickupLongitude ?: 0.0)
        val dropoffLatLng = LatLng(booking.destinationLatitude ?: 0.0, booking.destinationLongitude ?: 0.0)

        mMap?.clear()
        currentPolyline?.remove()
        mMap?.addMarker(
            MarkerOptions()
                .position(pickupLatLng)
                .title("Pickup")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
        mMap?.addMarker(
            MarkerOptions()
                .position(dropoffLatLng)
                .title("Dropoff")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        // Draw route if trip is active
        val shouldDrawRoute = booking.status == "EN_ROUTE_TO_PICKUP" || booking.status == "EN_ROUTE_TO_DROPOFF"
        if (shouldDrawRoute) {
            if (playServicesAvailable && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { driverLocation ->
                    if(driverLocation != null) {
                        val origin = LatLng(driverLocation.latitude, driverLocation.longitude)
                        val destination = if (booking.status == "EN_ROUTE_TO_PICKUP") pickupLatLng else dropoffLatLng
                        getDirectionsAndDrawRoute(origin, destination)
                    }
                }
            }
        } else {
            currentPolyline?.remove()
        }

        // Update action button based on status
        when (booking.status) {
            "ACCEPTED" -> {
                stopDriverTripTimer()
                binding.tripActionButton.text = "Start Trip"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
                binding.textViewDriverFinalFare.visibility = View.GONE
                binding.buttonPaymentConfirmed.visibility = View.GONE
            }
            "EN_ROUTE_TO_PICKUP" -> {
                stopDriverTripTimer()
                binding.tripActionButton.text = "Arrived at Pickup"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
                binding.textViewDriverFinalFare.visibility = View.GONE
                binding.buttonPaymentConfirmed.visibility = View.GONE
            }
            "ARRIVED_AT_PICKUP" -> {
                stopDriverTripTimer()
                binding.tripActionButton.text = "Start Dropoff"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
                binding.textViewDriverFinalFare.visibility = View.GONE
                binding.buttonPaymentConfirmed.visibility = View.GONE
            }
            "EN_ROUTE_TO_DROPOFF" -> {
                val startMs = booking.tripStartedAt ?: System.currentTimeMillis()
                val perMin = booking.perMinuteRate ?: 2.0
                val base = booking.fareBase ?: 0.0
                val pickupLatLng = LatLng(booking.pickupLatitude ?: 0.0, booking.pickupLongitude ?: 0.0)
                val dropoffLatLng = LatLng(booking.destinationLatitude ?: 0.0, booking.destinationLongitude ?: 0.0)
                val distanceKm = calculateDistanceKm(pickupLatLng, dropoffLatLng)
                startDriverTripTimer(startMs, perMin, base, PER_KM_RATE, distanceKm)

                binding.tripActionButton.text = "Complete Trip"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
                binding.textViewDriverFinalFare.visibility = View.VISIBLE
                binding.buttonPaymentConfirmed.visibility = View.GONE
            }
            "AWAITING_PAYMENT" -> {
                stopDriverTripTimer()
                binding.tripActionButton.visibility = View.GONE
                val fare = booking.finalFare ?: booking.estimatedFare ?: 0.0
                binding.textViewDriverFinalFare.visibility = View.VISIBLE
                binding.textViewDriverFinalFare.text = String.format(java.util.Locale.getDefault(), "Fare: ₱%.2f", fare)

                binding.buttonPaymentConfirmed.visibility = View.VISIBLE
                binding.buttonPaymentConfirmed.isEnabled = true
                binding.buttonPaymentConfirmed.text = getString(R.string.payment_confirmed)
                binding.buttonPaymentConfirmed.setOnClickListener {
                    val bId = booking.bookingId
                    if (!bId.isNullOrBlank()) {
                        binding.buttonPaymentConfirmed.isEnabled = false
                        // Call server to complete the trip; server will set paymentConfirmed
                        functions.getHttpsCallable("updateTripStatus")
                            .call(mapOf("bookingId" to bId, "newStatus" to "COMPLETED", "driverId" to auth.currentUser?.uid))
                            .addOnSuccessListener {
                                binding.buttonPaymentConfirmed.visibility = View.GONE
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to set COMPLETED status", e)
                                // Fallback: directly set status to COMPLETED in Realtime DB
                                val bookingRef = db.getReference("bookingRequests").child(bId)
                                bookingRef.child("status").setValue("COMPLETED")
                                    .addOnSuccessListener {
                                        binding.buttonPaymentConfirmed.visibility = View.GONE
                                    }
                                    .addOnFailureListener { err ->
                                        Log.e(TAG, "Fallback COMPLETED update failed", err)
                                        binding.buttonPaymentConfirmed.isEnabled = true
                                    }
                            }
                    }
                }

                if (!farePopupShown) {
                    showDriverFarePopup(fare, booking)
                    farePopupShown = true
                }
            }
            "COMPLETED" -> {
                stopDriverTripTimer()
                binding.tripActionButton.visibility = View.GONE
                val fare = booking.finalFare ?: booking.estimatedFare ?: 0.0
                binding.textViewDriverFinalFare.visibility = View.VISIBLE
                binding.textViewDriverFinalFare.text = String.format(java.util.Locale.getDefault(), "Fare: ₱%.2f", fare)
                binding.buttonPaymentConfirmed.visibility = View.GONE
            }
            "CANCELED" -> {
                stopDriverTripTimer()
                binding.tripActionButton.visibility = View.GONE
                detachActiveBookingListener()
                currentBooking = null
                showDefaultView()
            }
            else -> {
                stopDriverTripTimer()
                binding.tripActionButton.visibility = View.GONE
                binding.tripActionButton.isEnabled = false
                binding.textViewDriverFinalFare.visibility = View.GONE
                binding.buttonPaymentConfirmed.visibility = View.GONE
            }



        }
    }

    /**
     * Advances the trip to the next status and requests server updates.
     */
    private fun onTripActionButtonClicked() {
        val booking = currentBooking ?: return
        val currentStatus = booking.status

        val nextStatus = when (currentStatus) {
            "ACCEPTED" -> "EN_ROUTE_TO_PICKUP"
            "EN_ROUTE_TO_PICKUP" -> "ARRIVED_AT_PICKUP"
            "ARRIVED_AT_PICKUP" -> "EN_ROUTE_TO_DROPOFF"
            "EN_ROUTE_TO_DROPOFF" -> "AWAITING_PAYMENT"
            else -> null
        }

        if (nextStatus != null) {
            updateTripStatus(booking.bookingId!!, nextStatus)
        }
    }

    /**
     * Calls Cloud Function to update trip status and performs client‑side fallbacks.
     */
    private fun updateTripStatus(bookingId: String, newStatus: String) {
        binding.tripActionButton.isEnabled = false
        functions.getHttpsCallable("updateTripStatus")
            .call(mapOf("bookingId" to bookingId, "newStatus" to newStatus, "driverId" to auth.currentUser?.uid))
            .addOnSuccessListener {
                Log.d(TAG, "Trip status updated to $newStatus")

                if (newStatus == "EN_ROUTE_TO_DROPOFF") {
                    db.getReference("bookingRequests").child(bookingId).child("tripStartedAt")
                        .setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                    db.getReference("bookingRequests").child(bookingId).child("perMinuteRate").setValue(2.0)
                }

                if (newStatus == "AWAITING_PAYMENT") {
                    val b = currentBooking
                    if (b != null) {
                        val startMs = b.tripStartedAt ?: System.currentTimeMillis()
                        val serverNowMs = System.currentTimeMillis() + serverTimeOffsetMs
                        val rawElapsedMs = serverNowMs - startMs
                        val safeElapsedMs = if (rawElapsedMs < 0) 0L else rawElapsedMs
                        val durationMinutes = (safeElapsedMs / 60000).toInt()
                        val perMin = b.perMinuteRate ?: 2.0
                        val base = b.fareBase ?: 50.0
                        val pickup = LatLng(b.pickupLatitude ?: 0.0, b.pickupLongitude ?: 0.0)
                        val drop = LatLng(b.destinationLatitude ?: 0.0, b.destinationLongitude ?: 0.0)
                        val distanceKm = calculateDistanceKm(pickup, drop)
                        val kmFee = distanceKm * PER_KM_RATE
                        val timeFee = durationMinutes * perMin
                        val finalBeforeDiscount = base + kmFee + timeFee
                        val discountPercent = b.appliedDiscountPercent ?: 0
                        val finalFare = if (discountPercent > 0) finalBeforeDiscount * (1.0 - discountPercent / 100.0) else finalBeforeDiscount
                        val updates: Map<String, Any> = mapOf(
                            "durationMinutes" to durationMinutes,
                            "finalFare" to finalFare
                        )
                        db.getReference("bookingRequests").child(bookingId).updateChildren(updates)
                    }
                }

                // The listener will handle UI changes. Now, draw route if needed.
                if (newStatus == "EN_ROUTE_TO_PICKUP" || newStatus == "EN_ROUTE_TO_DROPOFF") {
                    if (!playServicesAvailable || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@addOnSuccessListener
                    fusedLocationClient.lastLocation.addOnSuccessListener { driverLocation ->
                        if (driverLocation != null) {
                            val origin = LatLng(driverLocation.latitude, driverLocation.longitude)
                            val destination = if (newStatus == "EN_ROUTE_TO_PICKUP") {
                                LatLng(currentBooking?.pickupLatitude ?: 0.0, currentBooking?.pickupLongitude ?: 0.0)
                            } else {
                                LatLng(currentBooking?.destinationLatitude ?: 0.0, currentBooking?.destinationLongitude ?: 0.0)
                            }
                            getDirectionsAndDrawRoute(origin, destination)
                        }
                    }
                }
                binding.tripActionButton.isEnabled = true
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update trip status", e)
                // Fallback: try to update status directly in Realtime DB
                val bookingRef = db.getReference("bookingRequests").child(bookingId)
                bookingRef.child("status").setValue(newStatus)
                    .addOnSuccessListener {
                        Log.d(TAG, "Status set directly to $newStatus")
                        if (newStatus == "EN_ROUTE_TO_DROPOFF") {
                            bookingRef.child("tripStartedAt").setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                            bookingRef.child("perMinuteRate").setValue(2.0)
                        }
                        if (newStatus == "AWAITING_PAYMENT") {
                            val b = currentBooking
                            if (b != null) {
                                val startMs = b.tripStartedAt ?: System.currentTimeMillis()
                                val serverNowMs = System.currentTimeMillis() + serverTimeOffsetMs
                                val rawElapsedMs = serverNowMs - startMs
                                val safeElapsedMs = if (rawElapsedMs < 0) 0L else rawElapsedMs
                                val durationMinutes = (safeElapsedMs / 60000).toInt()
                                val perMin = b.perMinuteRate ?: 2.0
                                val base = b.fareBase ?: 50.0
                                val pickup = LatLng(b.pickupLatitude ?: 0.0, b.pickupLongitude ?: 0.0)
                                val drop = LatLng(b.destinationLatitude ?: 0.0, b.destinationLongitude ?: 0.0)
                                val distanceKm = calculateDistanceKm(pickup, drop)
                                val kmFee = distanceKm * PER_KM_RATE
                                val timeFee = durationMinutes * perMin
                                val finalBeforeDiscount = base + kmFee + timeFee
                                val discountPercent = b.appliedDiscountPercent ?: 0
                                val finalFare = if (discountPercent > 0) finalBeforeDiscount * (1.0 - discountPercent / 100.0) else finalBeforeDiscount
                                val updates: Map<String, Any> = mapOf(
                                    "durationMinutes" to durationMinutes,
                                    "finalFare" to finalFare
                                )
                                bookingRef.updateChildren(updates)
                            }
                        }
                        binding.tripActionButton.isEnabled = true
                    }
                    .addOnFailureListener { err ->
                        Log.e(TAG, "Fallback status update failed", err)
                        binding.tripActionButton.isEnabled = true
                    }
            }
    }

    /**
     * Shows the default dashboard view and clears map overlays.
     */
    private fun showDefaultView() {
        binding.driverStatusLayout.visibility = View.VISIBLE
        binding.tripDetailsMapContainer.visibility = View.GONE
        binding.tripDetailsCard.visibility = View.GONE
        binding.bookingRequestLayout.visibility = View.GONE // Hide booking request as well
        mMap?.clear()
        currentPolyline?.remove()
        farePopupShown = false
    }

    /**
     * Shows the active trip card and map container while hiding other views.
     */
    private fun showActiveTripView() {
        binding.driverStatusLayout.visibility = View.GONE
        binding.bookingRequestLayout.visibility = View.GONE
        binding.tripDetailsMapContainer.visibility = View.VISIBLE
        binding.tripDetailsCard.visibility = View.VISIBLE
    }

    /**
     * Handles location permission result, enabling online mode and my‑location when granted.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (binding.switchDriverStatus.isChecked) goOnline()
                if (playServicesAvailable) mMap?.isMyLocationEnabled = true
            } else {
                Toast.makeText(this, "Location permission is required to go online.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Checks for an active booking on resume and provides a quick return action.
     */
    override fun onResume() {
        super.onResume()
        auth.currentUser?.uid?.let {
            db.getReference("bookingRequests").orderByChild("driverId").equalTo(it).get().addOnSuccessListener { snap ->
                var activeBookingId: String? = null
                if(snap.exists()){
                    for (child in snap.children) {
                        val booking = child.getValue(BookingRequest::class.java)
                        if (booking != null && booking.status != "COMPLETED" && booking.status != "CANCELED" && booking.status != "NO_DRIVERS" && booking.status != "ERROR") {
                            activeBookingId = booking.bookingId
                            // Keep listening to the active booking
                            listenForActiveBooking(booking.bookingId!!)
                            break
                        }
                    }
                }
                // Toggle the return button visibility
                if (activeBookingId != null) {
                    binding.buttonReturnToActiveTrip.visibility = View.VISIBLE
                    binding.buttonReturnToActiveTrip.setOnClickListener {
                        // Ask for confirmation before resuming
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Resume active trip")
                            .setMessage("You have an active trip. Resume now?")
                            .setPositiveButton("Resume") { dialog, _ ->
                                activeBookingId?.let { id -> listenForActiveBooking(id) }
                                dialog.dismiss()
                            }
                            .setNegativeButton("Stay here") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                } else {
                    binding.buttonReturnToActiveTrip.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Ensures driver goes offline and listeners are detached to prevent leaks.
     */
    override fun onDestroy() {
        super.onDestroy()
        // To prevent memory leaks, we should ensure we go offline and detach listeners
        if (auth.currentUser != null) {
            goOffline()
        }
    }


    /**
     * Fetches directions via the Google Directions API, draws the polyline,
     * and fits camera to the route.
     */
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
                    Toast.makeText(this@DriverDashboardActivity, "Could not get directions.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Decodes an encoded polyline string into coordinate points.
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


    // Helper: Load passenger profile image by userId from Firestore
    /**
     * Loads a passenger’s profile image into the provided ImageView if available.
     */
    private fun loadPassengerProfileImage(userId: String?, target: ImageView?) {
        if (userId.isNullOrBlank() || target == null) {
            return
        }
        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { doc ->
                val url = doc.getString("profileImageUrl")
                if (!url.isNullOrBlank()) {
                    Glide.with(this).load(url).into(target)
                }
            }
            .addOnFailureListener {
                // Ignore failures; rules may restrict cross-read. Placeholder remains.
            }
    }

    /**
     * Puts the driver online: checks permission, enables my‑location,
     * sets presence, starts updates, and attaches offers listener.
     */
    private fun goOnline() {
        // Ensure location permission before going online
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            binding.switchDriverStatus.isChecked = false
            return
        }

        binding.switchDriverStatus.text = getString(R.string.status_online)
        updateStatusHeaderColor(true)

        // Enable My Location layer on map if available
        if (playServicesAvailable) {
            try {
                mMap?.isMyLocationEnabled = true
            } catch (_: SecurityException) {
                // Ignored: permission checked above
            }
        }

        // Mark driver online and set presence cleanup
        driverRef?.child("isOnline")?.setValue(true)
        driverRef?.onDisconnect()?.removeValue()

        // Backfill displayName/vehicle/phone in Realtime if missing
        backfillDriverProfileToRealtime()

        // Start location updates and listen for new offers
        startLocationUpdates()
        attachDriverOffersListener()

        // Show status card by default until offers arrive
        showDefaultView()
        Toast.makeText(this, "You are now online", Toast.LENGTH_SHORT).show()
    }

    // Update header background with rounded online/offline cards
    /**
     * Updates the dashboard header background to reflect online/offline state.
     */
    private fun updateStatusHeaderColor(isOnline: Boolean) {
        val drawableRes = if (isOnline) R.drawable.bg_status_online else R.drawable.bg_status_offline
        binding.driverStatusLayout.setBackgroundResource(drawableRes)
    }

    // Load the current driver's name and profile image from Firestore, with fallbacks
    /**
     * Loads current driver name and avatar from Firestore with fallbacks.
     */
    private fun loadCurrentDriverProfile() {
        val uid = auth.currentUser?.uid ?: return
        // First try users/{uid}
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                var displayName: String? = doc.getString("displayName")
                val imageUrl: String? = doc.getString("profileImageUrl")
                if (!imageUrl.isNullOrBlank()) {
                    Glide.with(this)
                        .load(imageUrl)
                        .placeholder(R.drawable.default_profile)
                        .circleCrop()
                        .into(binding.imageViewDriverAvatar)
                }
                if (displayName.isNullOrBlank()) {
                    // Fallback to drivers/{uid} name
                    firestore.collection("drivers").document(uid).get()
                        .addOnSuccessListener { d2 ->
                            val name = d2.getString("name")
                            val img2 = d2.getString("profileImageUrl")
                            if (!img2.isNullOrBlank()) {
                                Glide.with(this)
                                    .load(img2)
                                    .placeholder(R.drawable.default_profile)
                                    .circleCrop()
                                    .into(binding.imageViewDriverAvatar)
                            }
                            val resolved = name ?: auth.currentUser?.displayName ?: "Driver"
                            binding.textViewDriverWelcome.text = "Welcome, $resolved"
                        }
                        .addOnFailureListener {
                            val resolved = auth.currentUser?.displayName ?: "Driver"
                            binding.textViewDriverWelcome.text = "Welcome, $resolved"
                        }
                } else {
                    binding.textViewDriverWelcome.text = "Welcome, $displayName"
                }
            }
            .addOnFailureListener {
                // If users doc fails, fallback to drivers/{uid}
                firestore.collection("drivers").document(uid).get()
                    .addOnSuccessListener { d2 ->
                        val name = d2.getString("name")
                        val imageUrl = d2.getString("profileImageUrl")
                        if (!imageUrl.isNullOrBlank()) {
                            Glide.with(this)
                                .load(imageUrl)
                                .placeholder(R.drawable.default_profile)
                                .circleCrop()
                                .into(binding.imageViewDriverAvatar)
                        }
                        val resolved = name ?: auth.currentUser?.displayName ?: "Driver"
                        binding.textViewDriverWelcome.text = "Welcome, $resolved"
                    }
                    .addOnFailureListener {
                        val resolved = auth.currentUser?.displayName ?: "Driver"
                        binding.textViewDriverWelcome.text = "Welcome, $resolved"
                    }
            }
    }

    /**
     * Computes distance between two coordinates using `Location.distanceBetween`.
     */
    private fun calculateDistanceKm(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            a.latitude, a.longitude,
            b.latitude, b.longitude,
            results
        )
        return (results[0] / 1000.0)
    }

    /**
     * Reads Firebase server time offset to align timer calculations.
     */
    private fun initServerTimeOffset() {
        try {
            val db = FirebaseDatabase.getInstance()
            db.getReference(".info/serverTimeOffset").addListenerForSingleValueEvent(
                object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        val offset = snapshot.getValue(Long::class.java)
                        serverTimeOffsetMs = offset ?: 0L
                        Log.d(TAG, "Server time offset (driver): $serverTimeOffsetMs ms")
                    }

                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                        Log.w(TAG, "Server time offset load cancelled (driver): ${error.message}")
                        serverTimeOffsetMs = 0L
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init server time offset (driver)", e)
            serverTimeOffsetMs = 0L
        }
    }

    /**
     * Shows a driver fare summary popup with breakdown and payment confirmation.
     */
    private fun showDriverFarePopup(totalFare: Double, booking: BookingRequest?) {
        try {
            val view = layoutInflater.inflate(R.layout.dialog_fare_summary_driver, null)
            val fareView = view.findViewById<TextView>(R.id.textFareAmount)
            val discountView = view.findViewById<TextView>(R.id.textDiscount)
            val durationView = view.findViewById<TextView>(R.id.textDuration)

            // Compute breakdown from booking data
            val base = booking?.fareBase ?: 50.0
            val perMin = booking?.perMinuteRate ?: 2.0
            val minutes = booking?.durationMinutes ?: 0
            val km = booking?.distanceKm ?: run {
                val pickup = LatLng(booking?.pickupLatitude ?: 0.0, booking?.pickupLongitude ?: 0.0)
                val drop = LatLng(booking?.destinationLatitude ?: 0.0, booking?.destinationLongitude ?: 0.0)
                calculateDistanceKm(pickup, drop)
            }
            val subtotal = base + (km * PER_KM_RATE) + (minutes * perMin)
            val discount = (subtotal - totalFare).coerceAtLeast(0.0)

            fareView.text = String.format(java.util.Locale.getDefault(), "Fare: ₱%.2f", totalFare)
            discountView.visibility = View.VISIBLE
            if (discount > 0.0) {
                discountView.text = String.format(java.util.Locale.getDefault(), "Discount: - ₱%.2f", discount)
            } else {
                discountView.text = String.format(java.util.Locale.getDefault(), "Discount: ₱%.2f", 0.0)
            }
            durationView.text = String.format(java.util.Locale.getDefault(), "Duration: %d min", minutes)

            val bId = booking?.bookingId
            val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.payment_confirmed)) { dialog, _ ->
                    if (!bId.isNullOrBlank()) {
                        functions.getHttpsCallable("updateTripStatus")
                            .call(mapOf("bookingId" to bId, "newStatus" to "COMPLETED", "driverId" to auth.currentUser?.uid))
                            .addOnSuccessListener { dialog.dismiss() }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Popup COMPLETED update failed", e)
                                val bookingRef = db.getReference("bookingRequests").child(bId)
                                bookingRef.child("status").setValue("COMPLETED")
                                    .addOnSuccessListener { dialog.dismiss() }
                                    .addOnFailureListener { err -> Log.e(TAG, "Popup fallback COMPLETED update failed", err) }
                            }
                    } else {
                        dialog.dismiss()
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .create()
            dlg.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show driver fare popup", e)
        }
    }

    /**
     * Starts driver trip timer and updates fee labels every second.
     */
    private fun startDriverTripTimer(startMs: Long, perMin: Double, base: Double, perKm: Double, distanceKm: Double) {
        stopDriverTripTimer()
        val handler = android.os.Handler(mainLooper)
        driverTripTimerHandler = handler
    
        // Show initial and km fees immediately
        binding.textViewDriverInitialFee.visibility = View.VISIBLE
        binding.textViewDriverInitialFee.text = String.format("Initial fee: ₱%.2f", base)
    
        val kmFee = distanceKm * perKm
        binding.textViewDriverKmFee.visibility = View.VISIBLE
        binding.textViewDriverKmFee.text = String.format("Km fee (₱%.1f/km): %.2f km | ₱%.2f", perKm, distanceKm, kmFee)
    
        driverTripTimerRunnable = object : Runnable {
            override fun run() {
                // Use server-corrected current time to avoid negative elapsed
                val serverNowMs = System.currentTimeMillis() + serverTimeOffsetMs
                val rawElapsedMs = serverNowMs - startMs
                val elapsedMs = if (rawElapsedMs < 0) 0L else rawElapsedMs
                val totalSeconds = (elapsedMs / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                val timeFee = minutes * perMin
    
                binding.textViewDriverTimeFee.visibility = View.VISIBLE
                binding.textViewDriverTimeFee.text = String.format("Time fee (₱%.0f/min): %02d:%02d | ₱%.2f", perMin, minutes, seconds, timeFee)
    
                val finalBeforeDiscount = base + kmFee + timeFee
                val discountPercent = currentBooking?.appliedDiscountPercent ?: 0
                val discountAmount = if (discountPercent > 0) finalBeforeDiscount * (discountPercent / 100.0) else 0.0
                val finalFare = finalBeforeDiscount - discountAmount

                if (discountPercent > 0) {
                    binding.textViewDriverDiscountApplied.visibility = View.VISIBLE
                    binding.textViewDriverDiscountApplied.text = String.format("Discount (%d%%): - ₱%.2f", discountPercent, discountAmount)
                } else {
                    binding.textViewDriverDiscountApplied.visibility = View.GONE
                }

                binding.textViewDriverFinalFare.visibility = View.VISIBLE
                binding.textViewDriverFinalFare.text = String.format(java.util.Locale.getDefault(), "Fare: ₱%.2f", finalFare)
    
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(driverTripTimerRunnable!!)
    }

    /**
     * Stops the driver trip timer and hides fee labels.
     */
    private fun stopDriverTripTimer() {
        driverTripTimerHandler?.removeCallbacks(driverTripTimerRunnable ?: return)
        driverTripTimerHandler = null
        driverTripTimerRunnable = null
        binding.textViewDriverTimeFee.visibility = View.GONE
        binding.textViewDriverInitialFee.visibility = View.GONE
        binding.textViewDriverKmFee.visibility = View.GONE
    }
}
