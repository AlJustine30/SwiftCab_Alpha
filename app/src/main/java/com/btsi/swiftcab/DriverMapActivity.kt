package com.btsi.swiftcab

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.btsi.swiftcab.databinding.ActivityDriverMapBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class DriverMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityDriverMapBinding
    private var mMap: GoogleMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var pickupLat: Double = 0.0
    private var pickupLng: Double = 0.0
    private var destLat: Double = 0.0
    private var destLng: Double = 0.0
    private var status: String? = null

    companion object {
        const val EXTRA_PICKUP_LAT = "EXTRA_PICKUP_LAT"
        const val EXTRA_PICKUP_LNG = "EXTRA_PICKUP_LNG"
        const val EXTRA_DEST_LAT = "EXTRA_DEST_LAT"
        const val EXTRA_DEST_LNG = "EXTRA_DEST_LNG"
        const val EXTRA_STATUS = "EXTRA_STATUS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriverMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        intent.extras?.let {
            pickupLat = it.getDouble(EXTRA_PICKUP_LAT)
            pickupLng = it.getDouble(EXTRA_PICKUP_LNG)
            destLat = it.getDouble(EXTRA_DEST_LAT)
            destLng = it.getDouble(EXTRA_DEST_LNG)
            status = it.getString(EXTRA_STATUS)
        }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.driver_map_view) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
        }

        val pickupLatLng = LatLng(pickupLat, pickupLng)
        val destLatLng = LatLng(destLat, destLng)

        mMap?.addMarker(
            MarkerOptions()
                .position(pickupLatLng)
                .title("Pickup")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
        mMap?.addMarker(
            MarkerOptions()
                .position(destLatLng)
                .title("Destination")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        val shouldDrawRoute = status == "EN_ROUTE_TO_PICKUP" || status == "EN_ROUTE_TO_DROPOFF"

        if (shouldDrawRoute) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { driverLocation ->
                    if(driverLocation != null) {
                        val origin = LatLng(driverLocation.latitude, driverLocation.longitude)
                        val destination = if (status == "EN_ROUTE_TO_PICKUP") pickupLatLng else destLatLng
                        getDirectionsAndDrawRoute(origin, destination)
                    }
                }
            }
        } else {
             val bounds = LatLngBounds.Builder()
                .include(pickupLatLng)
                .include(destLatLng)
                .build()
            mMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
        }
    }

    private fun getDirectionsAndDrawRoute(origin: LatLng, destination: LatLng) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apiKey = getString(R.string.google_maps_key)
                val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&key=$apiKey"
                val result = URL(url).readText()
                val jsonResult = JSONObject(result)
                val routes = jsonResult.getJSONArray("routes")

                if (routes.length() > 0) {
                    val points = routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points")
                    val decodedPath = decodePolyline(points)

                    withContext(Dispatchers.Main) {
                        mMap?.addPolyline(PolylineOptions().addAll(decodedPath).color(Color.BLUE).width(12f))
                        val bounds = LatLngBounds.Builder()
                            .include(origin)
                            .include(destination)
                            .build()
                        mMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
