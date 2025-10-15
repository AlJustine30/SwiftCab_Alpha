package com.btsi.swiftcab

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.BookingRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class BookingHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerViewBookingHistory: RecyclerView
    private lateinit var bookingHistoryAdapter: BookingHistoryAdapter
    private val bookingHistoryList = mutableListOf<BookingRequest>()
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var textViewNoHistory: TextView
    private var userType: String = "rider" // Default to rider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking_history)

        val toolbar: Toolbar = findViewById(R.id.toolbar_booking_history)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.booking_history_title)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        textViewNoHistory = findViewById(R.id.textViewNoHistory)
        recyclerViewBookingHistory = findViewById(R.id.recyclerViewBookingHistory)
        recyclerViewBookingHistory.layoutManager = LinearLayoutManager(this)

        // Initialize adapter early. It will be updated with the correct user type later.
        bookingHistoryAdapter = BookingHistoryAdapter(bookingHistoryList, userType)
        recyclerViewBookingHistory.adapter = bookingHistoryAdapter

        fetchBookingHistory()
    }

    private fun fetchBookingHistory() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val userId = currentUser.uid

        // First, determine if the user is a driver to decide which query to run
        val driversRef = database.getReference("Users/Drivers").child(userId)
        driversRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val query: Query
                if (snapshot.exists()) {
                    userType = "driver"
                    // CORRECTED: Query "bookingRequests" instead of "Bookings"
                    query = database.getReference("bookingRequests").orderByChild("driverId").equalTo(userId)
                } else {
                    userType = "rider"
                    // CORRECTED: Query "bookingRequests" instead of "Bookings"
                    query = database.getReference("bookingRequests").orderByChild("passengerId").equalTo(userId)
                }

                // Re-configure adapter with the correct userType before fetching data
                bookingHistoryAdapter = BookingHistoryAdapter(bookingHistoryList, userType)
                recyclerViewBookingHistory.adapter = bookingHistoryAdapter

                query.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(dataSnapshot: DataSnapshot) {
                        bookingHistoryList.clear()
                        if (dataSnapshot.exists()) {
                            for (historySnapshot in dataSnapshot.children) {
                                val booking = historySnapshot.getValue(BookingRequest::class.java)
                                if (booking != null) {
                                    bookingHistoryList.add(booking)
                                }
                            }
                            // Sort by timestamp descending (newest first)
                            bookingHistoryList.sortByDescending { it.timestamp }
                            bookingHistoryAdapter.notifyDataSetChanged()
                        }
                        updateUiBasedOnHistory()
                    }

                    override fun onCancelled(databaseError: DatabaseError) {
                        Toast.makeText(this@BookingHistoryActivity, "Failed to load history: ${databaseError.message}", Toast.LENGTH_SHORT).show()
                        updateUiBasedOnHistory()
                    }
                })
            }

            override fun onCancelled(databaseError: DatabaseError) {
                Toast.makeText(this@BookingHistoryActivity, "Failed to determine user type: ${databaseError.message}", Toast.LENGTH_SHORT).show()
                updateUiBasedOnHistory()
            }
        })
    }
    
    private fun updateUiBasedOnHistory() {
        if (bookingHistoryList.isEmpty()) {
            textViewNoHistory.visibility = View.VISIBLE
            recyclerViewBookingHistory.visibility = View.GONE
        } else {
            textViewNoHistory.visibility = View.GONE
            recyclerViewBookingHistory.visibility = View.VISIBLE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed() // Correct way to handle Up button
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
