package com.btsi.swiftcab

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.util.Collections
import com.btsi.swiftcab.models.BookingRequest // Corrected import

class DriverBookingHistoryActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var currentDriverId: String? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DriverBookingHistoryAdapter
    private val bookingHistoryList = mutableListOf<BookingRequest>()
    private lateinit var textViewNoHistory: TextView
    private lateinit var toolbar: Toolbar

    private val TAG = "DriverBookingHistory"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_booking_history)

        toolbar = findViewById(R.id.toolbar_driver_booking_history)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        currentDriverId = firebaseAuth.currentUser?.uid

        recyclerView = findViewById(R.id.recyclerViewDriverBookingHistory)
        textViewNoHistory = findViewById(R.id.textViewNoDriverHistory)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DriverBookingHistoryAdapter(bookingHistoryList)
        recyclerView.adapter = adapter

        if (currentDriverId == null) {
            Log.e(TAG, "Driver ID is null. Cannot fetch history.")
            textViewNoHistory.text = "Error: Not logged in."
            textViewNoHistory.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            return
        }

        fetchDriverBookingHistory()
    }

    private fun fetchDriverBookingHistory() {
        Log.d(TAG, "Fetching booking history for driver ID: $currentDriverId")
        val historyRef = database.getReference("Users/Drivers/$currentDriverId/booking_history")

        historyRef.orderByChild("timestamp") // Order by timestamp
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    bookingHistoryList.clear()
                    if (snapshot.exists()) {
                        for (historySnapshot in snapshot.children) {
                            val booking = historySnapshot.getValue(BookingRequest::class.java)
                            booking?.let { bookingHistoryList.add(it) }
                        }
                        // Sort by timestamp descending (newest first)
                        Collections.sort(bookingHistoryList, compareByDescending { it.timestamp })
                        adapter.updateData(bookingHistoryList)
                        textViewNoHistory.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        Log.d(TAG, "Fetched ${bookingHistoryList.size} history items.")
                    } else {
                        Log.d(TAG, "No booking history found for driver ID: $currentDriverId")
                        textViewNoHistory.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Failed to fetch driver booking history: ${error.message}")
                    textViewNoHistory.text = "Error fetching history."
                    textViewNoHistory.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                }
            })
    }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
