package com.btsi.SwiftCab

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btsi.SwiftCab.models.BookingRequest // Corrected import
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.* // Firebase Realtime Database
import java.util.Collections

class BookingHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerViewBookingHistory: RecyclerView
    private lateinit var bookingHistoryAdapter: BookingHistoryAdapter
    private val bookingHistoryList = mutableListOf<BookingRequest>()
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var textViewNoHistory: TextView

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
        bookingHistoryAdapter = BookingHistoryAdapter(bookingHistoryList)
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

        val riderId = currentUser.uid
        val historyRef = database.getReference("Users/Riders/").child(riderId).child("booking_history")

        historyRef.orderByChild("timestamp") // Order by timestamp
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    bookingHistoryList.clear()
                    if (snapshot.exists()) {
                        for (historySnapshot in snapshot.children) {
                            val booking = historySnapshot.getValue(BookingRequest::class.java)
                            if (booking != null) {
                                bookingHistoryList.add(booking)
                            }
                        }
                        // Firebase returns in ascending order, reverse for descending (newest first)
                        Collections.reverse(bookingHistoryList)
                        bookingHistoryAdapter.notifyDataSetChanged()
                    } 
                    
                    if (bookingHistoryList.isEmpty()) {
                        textViewNoHistory.visibility = View.VISIBLE
                        recyclerViewBookingHistory.visibility = View.GONE
                    } else {
                        textViewNoHistory.visibility = View.GONE
                        recyclerViewBookingHistory.visibility = View.VISIBLE
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@BookingHistoryActivity, "Failed to load history: ${error.message}", Toast.LENGTH_SHORT).show()
                    textViewNoHistory.visibility = View.VISIBLE
                    recyclerViewBookingHistory.visibility = View.GONE
                }
            })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed() // Correct way to handle Up button
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
