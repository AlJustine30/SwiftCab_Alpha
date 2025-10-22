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


    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance()
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private var driverRef: DatabaseReference? = null
    private var offersListenerReference: DatabaseReference? = null
    private var offersValueListener: ValueEventListener? = null
    private var activeBookingListener: ValueEventListener? = null
    private var activeBookingRef: DatabaseReference? = null
    private var playServicesAvailable: Boolean = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val TAG = "DriverDashboard"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        // Enable my location button on the map only if Play Services available
        if (playServicesAvailable && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
        } else if (!playServicesAvailable) {
            Toast.makeText(this, "Google Play services not available; map location disabled.", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun setupUI() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            finish()
            return
        }

        binding.textViewDriverWelcome.text = "Welcome, ${currentUser.displayName ?: "Driver"}"
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
                    Toast.makeText(this, "Earnings clicked", Toast.LENGTH_SHORT).show()
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
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun goOnline() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            binding.switchDriverStatus.isChecked = false
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
            return
        }
        if (!playServicesAvailable) {
            Toast.makeText(this, "Google Play services not available; cannot go online.", Toast.LENGTH_SHORT).show()
            binding.switchDriverStatus.isChecked = false
            return
        }
        binding.switchDriverStatus.text = getString(R.string.status_online)
        driverRef?.onDisconnect()?.removeValue()
        // Ensure driver metadata is present for acceptBooking
        backfillDriverProfileToRealtime()
        startLocationUpdates()
        attachDriverOffersListener()
        Toast.makeText(this, "You are now online", Toast.LENGTH_SHORT).show()
    }

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

                    // NEW: If there is an active booking, update its driverLocation
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

    private fun goOffline() {
        binding.switchDriverStatus.text = getString(R.string.status_offline)
        stopLocationUpdates()
        driverRef?.onDisconnect()?.cancel()
        driverRef?.removeValue()
        detachDriverOffersListener()
        detachActiveBookingListener()
        showDefaultView()
        Toast.makeText(this, "You are now offline", Toast.LENGTH_SHORT).show()
    }

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

    private fun stopLocationUpdates() {
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun attachDriverOffersListener() {
        val driverId = auth.currentUser?.uid ?: return
        offersListenerReference = db.getReference("driverOffers").child(driverId)

        offersValueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.childrenCount > 0) {
                    val firstOfferSnapshot = snapshot.children.first()
                    val bookingRequest = firstOfferSnapshot.getValue(BookingRequest::class.java)
                    if (bookingRequest != null && !isFinishing) {
                        showBookingOfferDialog(bookingRequest)
                        detachDriverOffersListener()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Driver offers listener cancelled.", error.toException())
            }
        }
        offersListenerReference?.addValueEventListener(offersValueListener!!)
    }

    private fun detachDriverOffersListener() {
        offersValueListener?.let { listener ->
            offersListenerReference?.removeEventListener(listener)
        }
    }

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

    private fun acceptBooking(bookingId: String?) {
        if (bookingId == null) return

        functions.getHttpsCallable("acceptBooking")
            .call(hashMapOf("bookingId" to bookingId))
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Booking Accepted!", Toast.LENGTH_SHORT).show()
                    binding.bookingRequestLayout.visibility = View.GONE
                    listenForActiveBooking(bookingId)
                } else {
                    Log.w(TAG, "acceptBooking:onComplete:failure", task.exception)
                    Toast.makeText(this, "Failed to accept booking.", Toast.LENGTH_SHORT).show()
                    attachDriverOffersListener()
                }
            }
    }

    private fun listenForActiveBooking(bookingId: String) {
        detachActiveBookingListener()
        activeBookingRef = db.getReference("bookingRequests").child(bookingId)
        activeBookingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val booking = snapshot.getValue(BookingRequest::class.java)
                currentBooking = booking
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

    private fun detachActiveBookingListener() {
        activeBookingListener?.let { listener ->
            activeBookingRef?.removeEventListener(listener)
        }
    }

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
        mMap?.addMarker(MarkerOptions().position(pickupLatLng).title("Pickup"))
        mMap?.addMarker(MarkerOptions().position(dropoffLatLng).title("Dropoff"))

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
                binding.tripActionButton.text = "Start Trip"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
            }
            "EN_ROUTE_TO_PICKUP" -> {
                binding.tripActionButton.text = "Arrived at Pickup"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
            }
            "ARRIVED_AT_PICKUP" -> {
                binding.tripActionButton.text = "Start Dropoff"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
            }
            "EN_ROUTE_TO_DROPOFF" -> {
                binding.tripActionButton.text = "Complete Trip"
                binding.tripActionButton.visibility = View.VISIBLE
                binding.tripActionButton.setOnClickListener { onTripActionButtonClicked() }
                binding.tripActionButton.isEnabled = true
            }
            "COMPLETED" -> {
                binding.tripActionButton.visibility = View.GONE
                detachActiveBookingListener()
                currentBooking = null
                showDefaultView()
            }
            "CANCELED" -> {
                binding.tripActionButton.visibility = View.GONE
                detachActiveBookingListener()
                currentBooking = null
                showDefaultView()
            }
            else -> {
                binding.tripActionButton.visibility = View.GONE
                binding.tripActionButton.isEnabled = false
            }
        }
    }

    private fun onTripActionButtonClicked() {
        val booking = currentBooking ?: return
        val currentStatus = booking.status

        val nextStatus = when (currentStatus) {
            "ACCEPTED" -> "EN_ROUTE_TO_PICKUP"
            "EN_ROUTE_TO_PICKUP" -> "ARRIVED_AT_PICKUP"
            "ARRIVED_AT_PICKUP" -> "EN_ROUTE_TO_DROPOFF"
            "EN_ROUTE_TO_DROPOFF" -> "COMPLETED"
            else -> null
        }

        if (nextStatus != null) {
            updateTripStatus(booking.bookingId!!, nextStatus)
        }
    }

    private fun updateTripStatus(bookingId: String, newStatus: String) {
        binding.tripActionButton.isEnabled = false
        functions.getHttpsCallable("updateTripStatus")
            .call(mapOf("bookingId" to bookingId, "newStatus" to newStatus, "driverId" to auth.currentUser?.uid))
            .addOnSuccessListener {
                Log.d(TAG, "Trip status updated to $newStatus")
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
            .addOnFailureListener {
                Log.e(TAG, "Failed to update trip status", it)
                binding.tripActionButton.isEnabled = true
            }
    }

    private fun showDefaultView() {
        binding.driverStatusLayout.visibility = View.VISIBLE
        binding.tripDetailsMapContainer.visibility = View.GONE
        binding.tripDetailsCard.visibility = View.GONE
        binding.bookingRequestLayout.visibility = View.GONE // Hide booking request as well
        mMap?.clear()
        currentPolyline?.remove()
    }

    private fun showActiveTripView() {
        binding.driverStatusLayout.visibility = View.GONE
        binding.bookingRequestLayout.visibility = View.GONE
        binding.tripDetailsMapContainer.visibility = View.VISIBLE
        binding.tripDetailsCard.visibility = View.VISIBLE
    }

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

    override fun onResume() {
        super.onResume()
        auth.currentUser?.uid?.let {
            db.getReference("bookingRequests").orderByChild("driverId").equalTo(it).get().addOnSuccessListener {
                if(it.exists()){
                    for (child in it.children) {
                        val booking = child.getValue(BookingRequest::class.java)
                        if (booking != null && booking.status != "COMPLETED" && booking.status != "CANCELED") {
                            listenForActiveBooking(booking.bookingId!!)
                            break
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // To prevent memory leaks, we should ensure we go offline and detach listeners
        if (auth.currentUser != null) {
            goOffline()
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
                    Toast.makeText(this@DriverDashboardActivity, "Could not get directions.", Toast.LENGTH_SHORT).show()
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


    // Helper: Load passenger profile image by userId from Firestore
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
}