package com.btsi.swiftcab

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

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
    private var driverRef: DatabaseReference? = null
    private var offersListenerReference: DatabaseReference? = null
    private var offersValueListener: ValueEventListener? = null
    private var activeBookingListener: ValueEventListener? = null
    private var activeBookingRef: DatabaseReference? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val TAG = "DriverDashboard"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.driver_map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        setupToolbarAndDrawer()
        setupUI()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        // Enable my location button on the map
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupUI() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            // Redirect to login, assuming you have a LoginActivity
            // startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.textViewDriverWelcome.text = "Welcome, ${currentUser.displayName ?: "Driver"}"
        driverRef = db.getReference("drivers").child(currentUser.uid)

        binding.switchDriverStatus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) goOnline() else goOffline()
        }

        binding.navViewDriver.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_logout -> {
                    goOffline()
                    auth.signOut()
                    // startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
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
        driverRef?.onDisconnect()?.removeValue()
        startLocationUpdates()
        attachDriverOffersListener()
        Toast.makeText(this, "You are now online", Toast.LENGTH_SHORT).show()
    }

    private fun goOffline() {
        stopLocationUpdates()
        driverRef?.onDisconnect()?.cancel()
        driverRef?.removeValue()
        detachDriverOffersListener()
        detachActiveBookingListener()
        showDefaultView()
        Toast.makeText(this, "You are now offline", Toast.LENGTH_SHORT).show()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val driverData = mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "isOnline" to true
                    )
                    driverRef?.setValue(driverData)

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
        binding.pickupAddressText.text = "Pickup: ${booking.pickupAddress}"
        binding.dropoffAddressText.text = "Dropoff: ${booking.destinationAddress}"

        val pickupLatLng = LatLng(booking.pickupLatitude ?: 0.0, booking.pickupLongitude ?: 0.0)
        val dropoffLatLng = LatLng(booking.destinationLatitude ?: 0.0, booking.destinationLongitude ?: 0.0)

        mMap?.clear()
        currentPolyline?.remove()
        mMap?.addMarker(MarkerOptions().position(pickupLatLng).title("Pickup"))
        mMap?.addMarker(MarkerOptions().position(dropoffLatLng).title("Dropoff"))

        // Draw route if trip is active
        val shouldDrawRoute = booking.status == "EN_ROUTE_TO_PICKUP" || booking.status == "EN_ROUTE_TO_DROPOFF"
        if (shouldDrawRoute) {
             if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { driverLocation ->
                    if(driverLocation != null) {
                        val origin = LatLng(driverLocation.latitude, driverLocation.longitude)
                        val destination = if (booking.status == "EN_ROUTE_TO_PICKUP") pickupLatLng else dropoffLatLng
                        getDirectionsAndDrawRoute(origin, destination)
                    }
                }
            }
        } else {
            // If no route, just zoom to fit pickup and dropoff markers
             val bounds = LatLngBounds.Builder().include(pickupLatLng).include(dropoffLatLng).build()
             mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
        }


        // Update the action button based on the trip status
        when (booking.status) {
            "ACCEPTED" -> {
                binding.tripActionButton.text = "Start Trip (Navigate to Pickup)"
                binding.tripActionButton.isEnabled = true
            }
            "EN_ROUTE_TO_PICKUP" -> {
                binding.tripActionButton.text = "Confirm Arrival at Pickup"
                binding.tripActionButton.isEnabled = true
            }
            "ARRIVED_AT_PICKUP" -> {
                binding.tripActionButton.text = "Start Trip to Destination"
                binding.tripActionButton.isEnabled = true
            }
            "EN_ROUTE_TO_DROPOFF" -> {
                binding.tripActionButton.text = "Complete Trip"
                binding.tripActionButton.isEnabled = true
            }
            "COMPLETED" -> {
                binding.tripActionButton.text = "Trip Completed"
                binding.tripActionButton.isEnabled = false
                showDefaultView()
            }
            else -> {
                binding.tripActionButton.visibility = View.GONE
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
            .call(mapOf("bookingId" to bookingId, "newStatus" to newStatus))
            .addOnSuccessListener {
                Log.d(TAG, "Trip status updated to $newStatus")
                // The listener will handle UI changes. Now, draw route if needed.
                 if (newStatus == "EN_ROUTE_TO_PICKUP" || newStatus == "EN_ROUTE_TO_DROPOFF") {
                     if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@addOnSuccessListener
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
                 } else if (newStatus == "ARRIVED_AT_PICKUP" || newStatus == "COMPLETED") {
                     // Clear the route when arriving or completing
                     currentPolyline?.remove()
                 }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update trip status", e)
                Toast.makeText(this, "Error: Could not update trip status.", Toast.LENGTH_SHORT).show()
                binding.tripActionButton.isEnabled = true
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
                 mMap?.isMyLocationEnabled = true
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
}
