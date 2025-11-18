package com.btsi.swiftcab

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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

    /**
     * Initializes the booking history screen, toolbar, adapters, and Firebase instances.
     * Also triggers the initial fetch of the rider's booking history.
     *
     * @param savedInstanceState previously saved instance state, or null
     */
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

        bookingHistoryAdapter = BookingHistoryAdapter(
            bookingHistoryList,
            "rider",
            onRateClick = { booking ->
                if (booking.status == "COMPLETED" && !booking.riderRated) {
                    showRatingDialog(booking)
                } else {
                    Toast.makeText(this, "You have already rated this trip.", Toast.LENGTH_SHORT).show()
                }
            },
            onReportClick = { booking ->
                showReportDialog(booking)
            }
        )
        recyclerViewBookingHistory.adapter = bookingHistoryAdapter

        fetchRiderBookingHistory()
    }

    /**
     * Displays a dialog for the rider to report an issue related to a booking.
     * Collects category and message, then submits the report.
     *
     * @param booking the booking to report an issue about
     */
    private fun showReportDialog(booking: BookingRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_issue_report, null)
        val etCategory = dialogView.findViewById<EditText>(R.id.editTextIssueCategory)
        val etMessage = dialogView.findViewById<EditText>(R.id.editTextIssueMessage)
        val btnSubmitIssue = dialogView.findViewById<Button>(R.id.buttonSubmitIssue)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Report an Issue")
            .setView(dialogView)
            .create()

        btnSubmitIssue.setOnClickListener {
            val category = etCategory.text.toString().trim()
            val message = etMessage.text.toString().trim()
            if (message.isBlank()) {
                Toast.makeText(this, "Please describe the issue.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitIssueReport(booking, category, message)
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Submits an issue report to Firestore for the given booking.
     * Requires that the rider is logged in.
     *
     * @param booking the booking being reported
     * @param category optional category describing the issue
     * @param message description of the issue
     */
    private fun submitIssueReport(booking: BookingRequest, category: String, message: String) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }
        val report = com.btsi.swiftcab.models.Report(
            bookingId = booking.bookingId ?: "",
            reporterId = uid,
            riderId = uid,
            driverId = booking.driverId ?: "",
            message = message,
            category = category,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("reports").add(report)
            .addOnSuccessListener {
                Toast.makeText(this, "Report submitted. Our team will review it.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to submit report: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Shows a dialog allowing the rider to rate the driver for a completed trip.
     * Captures rating value, optional comments, and whether the rating is anonymous.
     *
     * @param booking the booking to rate
     */
    private fun showRatingDialog(booking: BookingRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rating, null)
        val ratingBar = dialogView.findViewById<RatingBar>(R.id.ratingBar)
        val etComments = dialogView.findViewById<EditText>(R.id.editTextComments)
        val checkboxAnonymous = dialogView.findViewById<android.widget.CheckBox>(R.id.checkboxAnonymous)
        val btnSubmit = dialogView.findViewById<Button>(R.id.buttonSubmitRating)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rate Your Driver")
            .setView(dialogView)
            .create()

        btnSubmit.setOnClickListener {
            val rating = ratingBar.rating
            val comments = etComments.text.toString()
            val anonymous = checkboxAnonymous.isChecked
            if (rating > 0) {
                saveRating(booking, rating, comments, anonymous)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please provide a rating.", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    /**
     * Persists a rating to Firestore and marks the trip as rated in booking history.
     * Refreshes the booking list upon success.
     *
     * @param booking the booking being rated
     * @param rating the rating value (0.0–5.0)
     * @param comments optional comments from the rider
     * @param anonymous whether the rating should hide the rater's identity
     */
    private fun saveRating(booking: BookingRequest, rating: Float, comments: String, anonymous: Boolean) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            return
        }

        val resolvedRaterName = (booking.riderName?.takeIf { it.isNotBlank() }
            ?: auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: "")

        val ratingData = Rating(
            bookingId = booking.bookingId ?: "",
            raterId = uid,
            ratedId = booking.driverId ?: "",
            rating = rating,
            comments = comments,
            raterName = resolvedRaterName,
            anonymous = anonymous,
            timestamp = System.currentTimeMillis()
        )

        firestore.collection("ratings").add(ratingData)
            .addOnSuccessListener {
                booking.bookingId?.let { id ->
                    firestore.collection("bookinghistory").document(id)
                        .set(mapOf("riderRated" to true, "riderId" to uid), com.google.firebase.firestore.SetOptions.merge())
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Failed to mark trip as rated: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                Toast.makeText(this, "Rating submitted successfully!", Toast.LENGTH_SHORT).show()
                fetchRiderBookingHistory() // Refresh the list
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to submit rating: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Loads the current rider's booking history from Firestore ordered by most recent.
     * Updates the adapter, UI state, and then attempts to load ratings for completed trips.
     */
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

                // Load ratings for completed & rated trips and bind to list
                loadRatingsForHistory(userId)
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

    /**
     * Updates visibility of list and empty-state text based on whether history exists.
     */
    private fun updateUiBasedOnHistory() {
        if (bookingHistoryList.isEmpty()) {
            textViewNoHistory.visibility = android.view.View.VISIBLE
            recyclerViewBookingHistory.visibility = android.view.View.GONE
        } else {
            textViewNoHistory.visibility = android.view.View.GONE
            recyclerViewBookingHistory.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * Fetches and binds rider ratings for completed and marked-as-rated trips in the list.
     *
     * @param riderId the current rider's user ID
     */
    private fun loadRatingsForHistory(riderId: String) {
        val itemsNeedingRatings = bookingHistoryList.filter { it.bookingId != null && it.riderRated && it.status == "COMPLETED" }
        if (itemsNeedingRatings.isEmpty()) return

        itemsNeedingRatings.forEach { booking ->
            val bookingId = booking.bookingId ?: return@forEach
            firestore.collection("ratings")
                .whereEqualTo("bookingId", bookingId)
                .whereEqualTo("raterId", riderId)
                .get()
                .addOnSuccessListener { snap ->
                    val r = snap.documents.firstOrNull()?.toObject(Rating::class.java)
                    if (r != null) {
                        booking.riderRating = r.rating
                        bookingHistoryAdapter.notifyDataSetChanged()
                    }
                }
                .addOnFailureListener {
                    // Silently ignore rating load failures for history UI
                }
        }
    }

    /**
     * Handles toolbar up navigation by finishing this activity.
     *
     * @return true when navigation is handled
     */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * Inflates the booking history options menu.
     *
     * @param menu the options menu to inflate
     * @return true when the menu is created
     */
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_booking_history, menu)
        return true
    }

    /**
     * Handles action bar item clicks from the booking history screen.
     * Navigates to My Reports when selected.
     *
     * @param item the selected menu item
     * @return true if the item was handled, otherwise delegates to super
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_my_reports -> {
                startActivity(android.content.Intent(this, MyReportsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
