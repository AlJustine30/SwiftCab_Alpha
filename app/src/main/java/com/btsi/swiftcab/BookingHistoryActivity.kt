package com.btsi.swiftcab

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.BookingRequest
import com.btsi.swiftcab.models.Rating
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class BookingHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerViewBookingHistory: RecyclerView
    private lateinit var bookingHistoryAdapter: BookingHistoryAdapter
    private val bookingHistoryList = mutableListOf<BookingRequest>()
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var textViewNoHistory: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_booking_history)

        val toolbar: Toolbar = findViewById(R.id.toolbar_booking_history)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.booking_history_title)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        textViewNoHistory = findViewById(R.id.textViewNoHistory)
        recyclerViewBookingHistory = findViewById(R.id.recyclerViewBookingHistory)
        recyclerViewBookingHistory.layoutManager = LinearLayoutManager(this)

        bookingHistoryAdapter = BookingHistoryAdapter(bookingHistoryList, "rider") { booking ->
            if (booking.status == "completed" && !booking.riderRated) {
                showRatingDialog(booking)
            } else {
                Toast.makeText(this, "You have already rated this trip.", Toast.LENGTH_SHORT).show()
            }
        }
        recyclerViewBookingHistory.adapter = bookingHistoryAdapter

        fetchRiderBookingHistory()
    }

    private fun showRatingDialog(booking: BookingRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rating, null)
        val ratingBar = dialogView.findViewById<RatingBar>(R.id.ratingBar)
        val etComments = dialogView.findViewById<EditText>(R.id.etComments)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmitRating)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rate Your Driver")
            .setView(dialogView)
            .create()

        btnSubmit.setOnClickListener {
            val rating = ratingBar.rating
            val comments = etComments.text.toString()
            if (rating > 0) {
                saveRating(booking, rating, comments)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please provide a rating.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun saveRating(booking: BookingRequest, rating: Float, comments: String) {
        val ratingData = Rating(
            bookingId = booking.bookingId,
            userId = auth.currentUser!!.uid,
            rating = rating.toDouble(),
            comment = comments,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("ratings").add(ratingData)
            .addOnSuccessListener {
                firestore.collection("bookinghistory").document(booking.bookingId!!)
                    .update("riderRated", true)
                Toast.makeText(this, "Rating submitted successfully!", Toast.LENGTH_SHORT).show()
                fetchRiderBookingHistory() // Refresh the list
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to submit rating: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


    private fun fetchRiderBookingHistory() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val userId = currentUser.uid

        firestore.collection("bookinghistory")
            .whereEqualTo("riderId", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                bookingHistoryList.clear()
                if (!documents.isEmpty) {
                    for (document in documents) {
                        val booking = document.toObject(BookingRequest::class.java)
                        booking.bookingId = document.id
                        bookingHistoryList.add(booking)
                    }
                }
                bookingHistoryAdapter.notifyDataSetChanged()
                updateUiBasedOnHistory()
            }
            .addOnFailureListener { exception ->
                Toast.makeText(
                    this,
                    "Failed to load history: ${exception.message}",
                    Toast.LENGTH_SHORT
                ).show()
                updateUiBasedOnHistory()
            }
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
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
