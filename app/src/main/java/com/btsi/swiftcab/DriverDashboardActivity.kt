package com.btsi.swiftcab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.btsi.swiftcab.models.BookingRequest
import com.google.android.gms.location.*
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase

class DriverDashboardActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var textViewDriverWelcome: TextView
    private lateinit var navView: NavigationView
    private lateinit var driverStatusSwitch: SwitchMaterial

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var currentFirebaseDriverId: String? = null
    private var currentDriverName: String = "Driver"

    private lateinit var onlineDriverRef: DatabaseReference
    private lateinit var driverOffersRef: DatabaseReference
    private var driverOffersListener: ChildEventListener? = null

    // --- New, Robust State Management for Offers ---
    private val pendingOffers = mutableMapOf<String, BookingRequest>()
    private var currentlyDisplayedBookingId: String? = null

    // --- New, Dedicated Listener for Active Bookings ---
    private lateinit var activeBookingQuery: Query
    private var activeBookingListener: ValueEventListener? = null
    private var activeBooking: BookingRequest? = null


    // --- Location Services ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest
    private var isRequestingLocationUpdates = false

    private lateinit var bookingRequestLayout: LinearLayout
    private lateinit var textViewPickupLocationInfo: TextView
    private lateinit var textViewDestinationLocationInfo: TextView
    private lateinit var buttonAcceptBooking: Button
    private lateinit var buttonDeclineBooking: Button

    private lateinit var activeBookingLayout: LinearLayout
    private lateinit var textViewActiveBookingTitle: TextView
    private lateinit var textViewActivePickupInfo: TextView
    private lateinit var textViewActiveDestinationInfo: TextView
    private lateinit var textViewActiveRiderInfo: TextView
    private lateinit var buttonNavigateToPickup: Button
    private lateinit var buttonStartTrip: Button
    private lateinit var buttonEndTrip: Button

    private val TAG = "DriverDashboard"

    companion object {
        const val STATUS_ON_TRIP = "ON_TRIP"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 101
        private const val MAX_ACCEPT_RETRIES = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_dashboard)

        setupUI()
        initializeFirebase()

        if (currentFirebaseDriverId == null) {
            handleInvalidDriver()
            return
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
        createLocationCallback()

        setupNavigation()
        setupSwitchListener()
        setupButtonClickListeners()
        fetchDriverDetails(currentFirebaseDriverId!!)
        // --- Start the new active booking listener ---
        attachActiveBookingListener(currentFirebaseDriverId!!)
    }

    private fun setupUI() {
        val toolbar: Toolbar = findViewById(R.id.toolbar_driver_dashboard)
        setSupportActionBar(toolbar)
        drawerLayout = findViewById(R.id.drawer_layout_driver_dashboard)
        navView = findViewById(R.id.nav_view_driver)
        textViewDriverWelcome = findViewById(R.id.textViewDriverWelcome)
        driverStatusSwitch = findViewById(R.id.switch_driver_status)
        bookingRequestLayout = findViewById(R.id.booking_request_layout)
        textViewPickupLocationInfo = findViewById(R.id.textViewPickupLocationInfo)
        textViewDestinationLocationInfo = findViewById(R.id.textViewDestinationLocationInfo)
        buttonAcceptBooking = findViewById(R.id.buttonAcceptBooking)
        buttonDeclineBooking = findViewById(R.id.buttonDeclineBooking)
        activeBookingLayout = findViewById(R.id.activeBookingLayout)
        textViewActiveBookingTitle = findViewById(R.id.textViewActiveBookingTitle)
        textViewActivePickupInfo = findViewById(R.id.textViewActivePickupInfo)
        textViewActiveDestinationInfo = findViewById(R.id.textViewActiveDestinationInfo)
        textViewActiveRiderInfo = findViewById(R.id.textViewActiveRiderInfo)
        buttonNavigateToPickup = findViewById(R.id.buttonNavigateToPickup)
        buttonStartTrip = findViewById(R.id.buttonStartTrip)
        buttonEndTrip = findViewById(R.id.buttonEndTrip)
    }

    private fun initializeFirebase() {
        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentFirebaseDriverId = firebaseAuth.currentUser?.uid

        currentFirebaseDriverId?.let {
            onlineDriverRef = database.getReference("online_drivers").child(it)
            driverOffersRef = database.getReference("driverRideOffers").child(it)
            activeBookingQuery = database.getReference("booking_requests").orderByChild("driverId").equalTo(it)
        }
    }

    private fun setupNavigation() {
        val toggle = ActionBarDrawerToggle(this, drawerLayout, findViewById(R.id.toolbar_driver_dashboard), R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        navView.setNavigationItemSelectedListener(this)
    }

    private fun handleInvalidDriver() {
        Log.e(TAG, "Driver ID is null. User might not be logged in properly.")
        Toast.makeText(this, "Error: Driver not logged in.", Toast.LENGTH_LONG).show()
        navigateToLogin()
    }

    // --- Location Logic Implementation ---

    private fun createLocationRequest() {
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000) // 10 seconds
            .setMinUpdateIntervalMillis(5000) // 5 seconds
            .build()
    }

    private fun createLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    updateDriverLocationOnFirebase(location)
                }
            }
        }
    }

    private fun setupSwitchListener() {
        driverStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (activeBooking != null) {
                driverStatusSwitch.isChecked = !isChecked // Revert the switch
                Toast.makeText(this, "Cannot change status during an active trip.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                startOnlineFlow()
            } else {
                goOffline()
            }
        }
    }

    private fun startOnlineFlow() {
        if (checkLocationPermission()) {
            startLocationUpdates()
        } else {
            requestLocationPermission()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isRequestingLocationUpdates || activeBooking != null) return
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        isRequestingLocationUpdates = true
        driverStatusSwitch.text = getString(R.string.status_online)
        Toast.makeText(this, getString(R.string.status_online_toast), Toast.LENGTH_SHORT).show()
        attachDriverOffersListener()
        onlineDriverRef.onDisconnect().removeValue()
    }

    private fun stopLocationUpdates() {
        if (!isRequestingLocationUpdates) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isRequestingLocationUpdates = false
    }

    private fun updateDriverLocationOnFirebase(location: Location) {
        if (currentFirebaseDriverId == null || !driverStatusSwitch.isChecked || activeBooking != null) return
        val driverStatus = mapOf(
            "lat" to location.latitude,
            "lng" to location.longitude,
            "last_updated" to ServerValue.TIMESTAMP
        )
        onlineDriverRef.setValue(driverStatus)
    }

    private fun goOffline() {
        stopLocationUpdates()
        onlineDriverRef.onDisconnect().cancel()
        onlineDriverRef.removeValue().addOnFailureListener { e -> Log.w(TAG, "Failed to remove online driver node, may already be offline.", e) }
        detachDriverOffersListener()
        driverStatusSwitch.text = getString(R.string.status_offline)
        Toast.makeText(this, getString(R.string.status_offline_toast), Toast.LENGTH_SHORT).show()
    }

    // --- Permission Handling ---

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Location permission is required to go online.", Toast.LENGTH_LONG).show()
                driverStatusSwitch.isChecked = false
            }
        }
    }

    // --- Core Business Logic (Accept, Decline, etc.) ---

    private fun setupButtonClickListeners() {
        buttonAcceptBooking.setOnClickListener {
            currentlyDisplayedBookingId?.let { acceptBooking(it) }
        }
        buttonDeclineBooking.setOnClickListener {
            currentlyDisplayedBookingId?.let { declineBooking(it) }
        }
    }

    private fun acceptBooking(bookingId: String, retryCount: Int = 0) {
        if (retryCount == 0) {
            buttonAcceptBooking.isEnabled = false
            buttonDeclineBooking.isEnabled = false
            buttonAcceptBooking.text = "ACCEPTING..."
        }

        Firebase.functions
            .getHttpsCallable("acceptBooking")
            .call(hashMapOf("bookingId" to bookingId))
            .addOnSuccessListener {
                Log.d(TAG, "Cloud function 'acceptBooking' call succeeded. Waiting for listener to update UI.")
                Toast.makeText(this, getString(R.string.booking_accepted_toast), Toast.LENGTH_SHORT).show()
                // The activeBookingListener will now automatically handle the UI transition.
                // We no longer need to manually change state here.
            }
            .addOnFailureListener { e ->
                if (e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.ABORTED && e.message?.contains("The line is busy") == true) {
                    if (retryCount < MAX_ACCEPT_RETRIES) {
                        Log.w(TAG, "acceptBooking failed due to contention. Retrying... (Attempt ${retryCount + 1})")
                        Handler(Looper.getMainLooper()).postDelayed({ acceptBooking(bookingId, retryCount + 1) }, 500L * (retryCount + 1))
                    } else {
                        Log.e(TAG, "acceptBooking failed after max retries.", e)
                        Toast.makeText(this, "Failed to accept: Server is busy. The offer may no longer be available.", Toast.LENGTH_LONG).show()
                        resetAcceptDeclineButtons()
                    }
                } else {
                    Log.e(TAG, "Cloud function 'acceptBooking' failed with a non-recoverable error.", e)
                    val userMessage = (e as? FirebaseFunctionsException)?.message ?: "An unexpected error occurred."
                    Toast.makeText(this, "Failed to accept ride: $userMessage", Toast.LENGTH_LONG).show()
                    
                    // The offer was likely removed. Refresh the UI state.
                    pendingOffers.remove(bookingId)
                    updateOfferDisplay()
                    resetAcceptDeclineButtons()
                }
            }
    }
    
    private fun resetAcceptDeclineButtons() {
        buttonAcceptBooking.isEnabled = true
        buttonDeclineBooking.isEnabled = true
        buttonAcceptBooking.text = "ACCEPT"
    }

    private fun declineBooking(bookingId: String) {
        // Optimistically remove from UI
        pendingOffers.remove(bookingId)
        updateOfferDisplay()
        // Tell backend to remove the offer
        driverOffersRef.child(bookingId).removeValue()
        Toast.makeText(this, getString(R.string.booking_declined_toast), Toast.LENGTH_SHORT).show()
    }

    // --- NEW, ROBUST LISTENER LOGIC ---

    private fun attachDriverOffersListener() {
        if (driverOffersListener != null || activeBooking != null) return
        
        Log.d(TAG, "Attaching driver offers listener.")
        driverOffersListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val booking = snapshot.getValue(BookingRequest::class.java)
                if (booking != null && booking.bookingId != null) {
                    Log.d(TAG, "Offer added: ${booking.bookingId}")
                    pendingOffers[booking.bookingId!!] = booking
                    updateOfferDisplay()
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val bookingId = snapshot.key
                if (bookingId != null) {
                    Log.d(TAG, "Offer removed: $bookingId")
                    pendingOffers.remove(bookingId)
                    updateOfferDisplay()
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "driverOffersListener cancelled: ${error.message}") }
        }
        driverOffersRef.addChildEventListener(driverOffersListener!!)
    }

    private fun detachDriverOffersListener() {
        if (driverOffersListener != null) {
            Log.d(TAG, "Detaching driver offers listener.")
            driverOffersRef.removeEventListener(driverOffersListener!!)
            driverOffersListener = null
        }
        pendingOffers.clear()
        updateOfferDisplay()
    }
    
    // --- NEW, DEDICATED ACTIVE BOOKING LISTENER ---

    private fun attachActiveBookingListener(driverId: String) {
        if (activeBookingListener != null) return
        Log.d(TAG, "Attaching active booking listener.")
        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var foundActiveBooking: BookingRequest? = null
                if (snapshot.exists()) {
                    for (bookingSnapshot in snapshot.children) {
                        val booking = bookingSnapshot.getValue(BookingRequest::class.java)
                        if (booking != null && (booking.status == "ACCEPTED" || booking.status == STATUS_ON_TRIP)) {
                            foundActiveBooking = booking
                            break
                        }
                    }
                }

                if (foundActiveBooking != null) {
                    if (activeBooking?.bookingId != foundActiveBooking.bookingId) {
                        Log.i(TAG, "Active booking found: ${foundActiveBooking.bookingId}")
                        displayActiveBookingUI(foundActiveBooking)
                    }
                } else {
                    if (activeBooking != null) {
                        Log.i(TAG, "Active booking is no longer active. Returning to online state.")
                        clearActiveBookingState()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "activeBookingListener cancelled: ${error.message}") }
        }
        activeBookingQuery.addValueEventListener(activeBookingListener!!)
    }

    private fun detachActiveBookingListener() {
        if (activeBookingListener != null) {
            Log.d(TAG, "Detaching active booking listener.")
            activeBookingQuery.removeEventListener(activeBookingListener!!)
            activeBookingListener = null
        }
    }


    // --- UI Display and State Management ---

    private fun fetchDriverDetails(uid: String) {
        database.getReference("users").child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                currentDriverName = snapshot.child("name").getValue(String::class.java) ?: "Driver"
                val email = snapshot.child("email").getValue(String::class.java)
                textViewDriverWelcome.text = "Welcome, $currentDriverName"
                updateNavHeader(currentDriverName, email)
            }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "Failed to fetch driver details: ${error.message}") }
        })
    }
    
    private fun updateOfferDisplay() {
        if (pendingOffers.isEmpty()) {
            if (currentlyDisplayedBookingId != null) {
                Log.d(TAG, "No more pending offers. Clearing display.")
                currentlyDisplayedBookingId = null
                bookingRequestLayout.visibility = View.GONE
                resetAcceptDeclineButtons()
            }
        } else {
            val nextBookingId = pendingOffers.keys.first()
            if (currentlyDisplayedBookingId != nextBookingId) {
                Log.d(TAG, "Displaying new offer: $nextBookingId")
                currentlyDisplayedBookingId = nextBookingId
                val booking = pendingOffers[nextBookingId]
                if (booking != null) {
                    bookingRequestLayout.visibility = View.VISIBLE
                    textViewPickupLocationInfo.text = booking.pickupAddress
                    textViewDestinationLocationInfo.text = booking.destinationAddress
                    resetAcceptDeclineButtons()
                } else {
                     // Should not happen, but good to handle
                    pendingOffers.remove(nextBookingId)
                    updateOfferDisplay()
                }
            }
        }
    }

    private fun displayActiveBookingUI(booking: BookingRequest) {
        if (isFinishing || isDestroyed) return
        
        // --- This is the new centralized point for entering the active state ---
        activeBooking = booking
        
        // Stop looking for other jobs
        detachDriverOffersListener()
        stopLocationUpdates() // Temporarily stop location updates while UI is figured out
        
        driverStatusSwitch.isEnabled = false
        driverStatusSwitch.text = "ON TRIP"
        activeBookingLayout.visibility = View.VISIBLE
        bookingRequestLayout.visibility = View.GONE

        textViewActivePickupInfo.text = "Pickup: ${booking.pickupAddress}"
        textViewActiveDestinationInfo.text = "Destination: ${booking.destinationAddress}"
        textViewActiveRiderInfo.text = "Rider: ${booking.riderName}"

        if (booking.status == STATUS_ON_TRIP) {
            buttonNavigateToPickup.visibility = View.GONE
            buttonStartTrip.visibility = View.GONE
            buttonEndTrip.visibility = View.VISIBLE
        } else { // "ACCEPTED"
            buttonNavigateToPickup.visibility = View.VISIBLE
            buttonStartTrip.visibility = View.VISIBLE
            buttonEndTrip.visibility = View.GONE
        }
    }

    private fun clearActiveBookingState() {
        activeBooking = null
        activeBookingLayout.visibility = View.GONE
        driverStatusSwitch.isEnabled = true
        driverStatusSwitch.text = if(driverStatusSwitch.isChecked) "ONLINE" else "OFFLINE"
        
        // If we are still supposed to be online, restart the listeners
        if (driverStatusSwitch.isChecked) {
            startOnlineFlow()
        }
    }

    private fun updateNavHeader(name: String?, email: String?) {
        val headerView = navView.getHeaderView(0)
        val textViewNavName = headerView.findViewById<TextView>(R.id.textViewNavDriverName)
        val textViewNavEmail = headerView.findViewById<TextView>(R.id.textViewNavDriverEmail)
        textViewNavName.text = name
        textViewNavEmail.text = email
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    // --- Lifecycle and Navigation ---

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_driver_logout -> {
                goOffline()
                firebaseAuth.signOut()
                navigateToLogin()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
    
    override fun onResume() {
        super.onResume()
        if (driverStatusSwitch.isChecked && !isRequestingLocationUpdates && activeBooking == null) {
            startOnlineFlow()
        }
        currentFirebaseDriverId?.let { attachActiveBookingListener(it) }
    }
    
    override fun onPause() {
        super.onPause()
        if (isRequestingLocationUpdates) {
            stopLocationUpdates()
        }
        detachActiveBookingListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure we go offline if the activity is destroyed (and not on a trip)
        if (activeBooking == null) {
            goOffline()
        }
    }
}
