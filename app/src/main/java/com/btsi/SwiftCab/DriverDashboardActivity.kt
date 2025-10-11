package com.btsi.SwiftCab

import android.content.Intent
import android.os.Bundle
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
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.btsi.SwiftCab.DriverBookingHistoryActivity
import com.btsi.SwiftCab.LoginActivity
import com.btsi.SwiftCab.models.BookingRequest
import com.btsi.SwiftCab.R
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class DriverDashboardActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var textViewDriverWelcome: TextView
    private lateinit var navView: NavigationView
    private lateinit var driverStatusSwitch: SwitchMaterial

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var currentFirebaseDriverId: String? = null
    private var currentDriverName: String = "Driver"

    private lateinit var bookingRequestsRef: DatabaseReference
    private var bookingRequestListener: ChildEventListener? = null

    private lateinit var bookingRequestLayout: LinearLayout
    private lateinit var textViewPickupLocationInfo: TextView
    private lateinit var textViewDestinationLocationInfo: TextView
    private lateinit var buttonAcceptBooking: Button
    private lateinit var buttonDeclineBooking: Button
    private var currentlyDisplayedBookingRequest: BookingRequest? = null

    private lateinit var activeBookingLayout: LinearLayout
    private lateinit var textViewActiveBookingTitle: TextView
    private lateinit var textViewActivePickupInfo: TextView
    private lateinit var textViewActiveDestinationInfo: TextView
    private lateinit var textViewActiveRiderInfo: TextView
    private lateinit var buttonNavigateToPickup: Button
    private lateinit var buttonStartTrip: Button
    private lateinit var buttonEndTrip: Button
    private var activeBooking: BookingRequest? = null

    private val TAG = "DriverDashboard"

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_ON_TRIP = "ON_TRIP"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_CANCELLED = "CANCELLED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_dashboard)

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

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentFirebaseDriverId = firebaseAuth.currentUser?.uid

        if (currentFirebaseDriverId == null) {
            Log.e(TAG, "Driver ID is null. User might not be logged in properly.")
            Toast.makeText(this, "Error: Driver not logged in.", Toast.LENGTH_LONG).show()
            navigateToLogin()
            return
        } else {
            fetchDriverDetails(currentFirebaseDriverId!!)
            fetchCurrentDriverActiveBooking(currentFirebaseDriverId!!)
        }

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        navView.setNavigationItemSelectedListener(this)

        setupSwitchListener()
        setupButtonClickListeners()

        if (activeBooking == null && driverStatusSwitch.isChecked) {
            listenForBookingRequests()
        } else if (activeBooking != null) {
            driverStatusSwitch.isEnabled = false
        } else {
            bookingRequestLayout.visibility = View.GONE
            activeBookingLayout.visibility = View.GONE
        }
    }

    private fun navigateToLogin(){
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupSwitchListener() {
        driverStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (activeBooking != null) {
                driverStatusSwitch.isChecked = !isChecked
                Toast.makeText(this, "Cannot change status during an active trip.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                driverStatusSwitch.text = getString(R.string.status_online)
                listenForBookingRequests()
                Toast.makeText(this, getString(R.string.status_online_toast), Toast.LENGTH_SHORT).show()
            } else {
                driverStatusSwitch.text = getString(R.string.status_offline)
                removeBookingRequestsListener()
                bookingRequestLayout.visibility = View.GONE
                Toast.makeText(this, getString(R.string.status_offline_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupButtonClickListeners() {
        buttonAcceptBooking.setOnClickListener {
            currentlyDisplayedBookingRequest?.let { booking ->
                if (currentFirebaseDriverId != null && currentDriverName.isNotBlank()) {
                    acceptBooking(booking)
                } else {
                    Log.e(TAG, "Cannot accept booking: Driver ID or Name is missing.")
                    Toast.makeText(this, getString(R.string.error_accepting_booking_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }

        buttonDeclineBooking.setOnClickListener {
            currentlyDisplayedBookingRequest?.bookingId?.let {
                declineBooking(it)
            }
        }

        buttonNavigateToPickup.setOnClickListener {
            Toast.makeText(this, "Navigation to pickup: TBD", Toast.LENGTH_SHORT).show()
        }

        buttonStartTrip.setOnClickListener {
            activeBooking?.bookingId?.let {
                updateBookingStatus(it, STATUS_ON_TRIP, getString(R.string.trip_started_toast))
            }
        }

        buttonEndTrip.setOnClickListener {
            activeBooking?.bookingId?.let {
                updateBookingStatus(it, STATUS_COMPLETED, getString(R.string.trip_ended_toast))
            }
        }
    }

    private fun fetchDriverDetails(uid: String) {
        val userRef = database.getReference("Users").child("Drivers").child(uid)
        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val name = snapshot.child("name").getValue(String::class.java)
                if (name != null) {
                    currentDriverName = name
                    textViewDriverWelcome.text = "Welcome, $currentDriverName"
                    Log.d(TAG, "Driver name fetched: $currentDriverName")
                    updateNavHeader(name, snapshot.child("email").getValue(String::class.java) ?: firebaseAuth.currentUser?.email)
                } else {
                    Log.w(TAG, "Driver name not found for UID: $uid")
                    textViewDriverWelcome.text = "Welcome, Driver"
                    updateNavHeader("Driver", firebaseAuth.currentUser?.email)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to fetch driver details: ${error.message}")
                textViewDriverWelcome.text = "Welcome, Driver"
                updateNavHeader("Driver", firebaseAuth.currentUser?.email)
            }
        })
    }

    private fun updateNavHeader(name: String?, email: String?) {
        val headerView = navView.getHeaderView(0)
        val textViewNavDriverName: TextView? = headerView.findViewById(R.id.textViewNavDriverName)
        val textViewNavDriverEmail: TextView? = headerView.findViewById(R.id.textViewNavDriverEmail)
        textViewNavDriverName?.text = name ?: "Driver Name"
        textViewNavDriverEmail?.text = email ?: "driver@example.com"
    }

    private fun fetchCurrentDriverActiveBooking(driverIdToCheck: String) {
        Log.d(TAG, getString(R.string.finding_active_booking))
        val bookingsRef = database.getReference("booking_requests")
        bookingsRef.orderByChild("driverId").equalTo(driverIdToCheck)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        Log.d(TAG, getString(R.string.no_active_booking_found))
                        clearActiveBookingState()
                        return
                    }
                    var foundActiveBooking = false
                    for (bookingSnapshot in snapshot.children) {
                        val booking = bookingSnapshot.getValue(BookingRequest::class.java)
                        if (booking != null && (booking.status == STATUS_ACCEPTED || booking.status == STATUS_ON_TRIP)) {
                            Log.d(TAG, "Found active booking: ${booking.bookingId}, Status: ${booking.status}")
                            activeBooking = booking
                            displayActiveBookingUI(booking)
                            driverStatusSwitch.isEnabled = false
                            removeBookingRequestsListener()
                            bookingRequestLayout.visibility = View.GONE
                            foundActiveBooking = true
                            break
                        }
                    }
                    if (!foundActiveBooking) {
                        Log.d(TAG, getString(R.string.no_active_booking_found) + " (checked assigned bookings)")
                        clearActiveBookingState()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error fetching active booking: ${error.message}")
                }
            })
    }

    private fun listenForBookingRequests() {
        if (currentFirebaseDriverId == null) {
            Log.e(TAG, "Cannot listen: Driver ID is null.")
            return
        }
        if (activeBooking != null) {
            Log.d(TAG, "Not listening for new PENDING requests as there is an active booking.")
            return
        }
        if (bookingRequestListener != null) {
            Log.d(TAG, "Listener already active.")
            return
        }

        Log.d(TAG, "Listening for PENDING booking requests...")
        bookingRequestsRef = database.getReference("booking_requests")
        bookingRequestListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prevChildName: String?) = handleBookingData(snapshot, "onChildAdded")
            override fun onChildChanged(snapshot: DataSnapshot, prevChildName: String?) = handleBookingData(snapshot, "onChildChanged")
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val bookingId = snapshot.key
                Log.d(TAG, "onChildRemoved: Booking $bookingId removed")
                if (bookingId == currentlyDisplayedBookingRequest?.bookingId) {
                    clearPendingBookingDisplay()
                    Toast.makeText(this@DriverDashboardActivity, getString(R.string.booking_cancelled_or_expired), Toast.LENGTH_SHORT).show()
                }
            }
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                Toast.makeText(this@DriverDashboardActivity, getString(R.string.error_listening_for_bookings), Toast.LENGTH_LONG).show()
            }
        }
        bookingRequestsRef.orderByChild("status").equalTo(STATUS_PENDING).addChildEventListener(bookingRequestListener!!)
    }

    private fun handleBookingData(snapshot: DataSnapshot, eventType: String) {
        if (activeBooking != null) return

        try {
            val booking = snapshot.getValue(BookingRequest::class.java)
            Log.d(TAG, "$eventType: ID: ${snapshot.key}, Data: $booking")

            if (booking != null && booking.bookingId != null) {
                if (booking.status == STATUS_PENDING && booking.driverId == null) {
                    if (currentlyDisplayedBookingRequest == null || currentlyDisplayedBookingRequest?.bookingId != booking.bookingId) {
                        currentlyDisplayedBookingRequest = booking
                        displayPendingBookingRequestUI(booking)
                    } else if (currentlyDisplayedBookingRequest?.bookingId == booking.bookingId) {
                        currentlyDisplayedBookingRequest = booking
                        displayPendingBookingRequestUI(booking)
                    }
                } else if (booking.bookingId == currentlyDisplayedBookingRequest?.bookingId && booking.status != STATUS_PENDING) {
                    clearPendingBookingDisplay()
                }
            } else {
                Log.w(TAG, "$eventType: Null booking data or ID for: ${snapshot.key}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$eventType: Error processing data: ${e.message}", e)
        }
    }

    private fun displayPendingBookingRequestUI(booking: BookingRequest) {
        if (isFinishing || isDestroyed || activeBooking != null) return

        textViewPickupLocationInfo.text = getString(R.string.pickup_prefix, booking.pickupAddress ?: "N/A")
        textViewDestinationLocationInfo.text = getString(R.string.destination_prefix, booking.destinationAddress ?: "N/A")
        bookingRequestLayout.visibility = View.VISIBLE
        Log.d(TAG, "Pending request layout visible for ID: ${booking.bookingId}")
    }

    private fun clearPendingBookingDisplay() {
        bookingRequestLayout.visibility = View.GONE
        currentlyDisplayedBookingRequest = null
        Log.d(TAG, "Pending request layout hidden.")
    }

    private fun acceptBooking(bookingToAccept: BookingRequest) {
        val bookingId = bookingToAccept.bookingId ?: return
        Log.d(TAG, "Attempting to accept booking: $bookingId by driver: $currentFirebaseDriverId ($currentDriverName)")
        val bookingUpdate = mapOf(
            "driverId" to currentFirebaseDriverId,
            "driverName" to currentDriverName,
            "status" to STATUS_ACCEPTED,
            "lastUpdateTime" to System.currentTimeMillis()
        )
        database.getReference("booking_requests").child(bookingId).updateChildren(bookingUpdate)
            .addOnSuccessListener {
                Log.i(TAG, "Booking $bookingId ACCEPTED by $currentFirebaseDriverId")
                Toast.makeText(this, getString(R.string.booking_accepted_toast), Toast.LENGTH_SHORT).show()
                clearPendingBookingDisplay()
                activeBooking = bookingToAccept.apply {
                    status = STATUS_ACCEPTED
                    driverId = currentFirebaseDriverId
                    driverName = currentDriverName
                }
                displayActiveBookingUI(activeBooking!!)
                driverStatusSwitch.isEnabled = false
                removeBookingRequestsListener()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to accept booking $bookingId: ${e.message}", e)
                Toast.makeText(this, getString(R.string.error_accepting_booking), Toast.LENGTH_SHORT).show()
            }
    }

    private fun declineBooking(bookingId: String) {
        Log.d(TAG, "Declining booking: $bookingId")
        clearPendingBookingDisplay()
        Toast.makeText(this, getString(R.string.booking_declined_toast), Toast.LENGTH_SHORT).show()
    }

    private fun displayActiveBookingUI(booking: BookingRequest) {
        if (isFinishing || isDestroyed) return

        activeBooking = booking
        textViewActivePickupInfo.text = getString(R.string.pickup_prefix, booking.pickupAddress ?: "N/A")
        textViewActiveDestinationInfo.text = getString(R.string.destination_prefix, booking.destinationAddress ?: "N/A")
        textViewActiveRiderInfo.text = getString(R.string.rider_name_prefix, booking.riderName ?: "Client")
        textViewActiveRiderInfo.visibility = if (booking.riderName != null) View.VISIBLE else View.GONE

        activeBookingLayout.visibility = View.VISIBLE
        bookingRequestLayout.visibility = View.GONE

        if (booking.status == STATUS_ON_TRIP) {
            buttonNavigateToPickup.visibility = View.GONE
            buttonStartTrip.visibility = View.GONE
            buttonEndTrip.visibility = View.VISIBLE
        } else { // ACCEPTED status
            buttonNavigateToPickup.visibility = View.VISIBLE
            buttonStartTrip.visibility = View.VISIBLE
            buttonEndTrip.visibility = View.GONE
        }
        Log.d(TAG, "Active booking layout visible for ID: ${booking.bookingId}, Status: ${booking.status}")
    }

    private fun updateBookingStatus(bookingId: String, newStatus: String, toastMessage: String) {
        val bookingUpdate = mapOf(
            "status" to newStatus,
            "lastUpdateTime" to System.currentTimeMillis()
        )
        database.getReference("booking_requests").child(bookingId).updateChildren(bookingUpdate)
            .addOnSuccessListener {
                Log.i(TAG, "Booking $bookingId status updated to $newStatus")
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
                activeBooking?.status = newStatus
                if (newStatus == STATUS_COMPLETED) {
                    activeBooking?.let { completedBooking ->
                        archiveBookingToDriverHistory(completedBooking)
                    }
                    clearActiveBookingState()
                } else {
                    activeBooking?.let { displayActiveBookingUI(it) }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update booking $bookingId to $newStatus: ${e.message}", e)
                Toast.makeText(this, getString(R.string.error_updating_booking_status), Toast.LENGTH_SHORT).show()
            }
    }

    private fun archiveBookingToDriverHistory(booking: BookingRequest) {
        currentFirebaseDriverId?.let { driverId ->
            booking.bookingId?.let { bookingId ->
                Log.d(TAG, "Archiving booking $bookingId to driver $driverId history")
                val historyRef = database.getReference("Users/Drivers/$driverId/booking_history/$bookingId")
                historyRef.setValue(booking)
                    .addOnSuccessListener {
                        Log.i(TAG, "Booking $bookingId archived successfully to driver history.")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to archive booking $bookingId to driver history: ${e.message}")
                    }
            }
        }
    }

    private fun clearActiveBookingState() {
        Log.d(TAG, "Clearing active booking state.")
        activeBooking = null
        activeBookingLayout.visibility = View.GONE
        driverStatusSwitch.isEnabled = true
        if (driverStatusSwitch.isChecked) {
            listenForBookingRequests()
        }
    }

    private fun removeBookingRequestsListener() {
        bookingRequestListener?.let {
            if (::bookingRequestsRef.isInitialized) {
                 bookingRequestsRef.removeEventListener(it)
            }
            Log.d(TAG, "Booking request listener removed.")
        }
        bookingRequestListener = null
        clearPendingBookingDisplay()
        Log.d(TAG, "Stopped listening for PENDING requests.")
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_driver_dashboard_home -> { /* Already here or Toast */ }
            R.id.nav_driver_profile -> Toast.makeText(this, "HELLO WORLD", Toast.LENGTH_SHORT).show()
            R.id.nav_driver_booking_history -> {
                startActivity(Intent(this, DriverBookingHistoryActivity::class.java))
            }
            R.id.nav_driver_logout -> {
                removeBookingRequestsListener()
                firebaseAuth.signOut()
                navigateToLogin()
                Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBookingRequestsListener()
    }
}
