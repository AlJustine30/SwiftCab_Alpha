package com.btsi.swiftcab

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DriverEarningsActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    private lateinit var toolbar: Toolbar
    private lateinit var textViewSelectedDate: TextView
    private lateinit var textViewEarningsTotal: TextView
    private lateinit var buttonPickDate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var textViewError: TextView

    private val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver_earnings)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        toolbar = findViewById(R.id.toolbarDriverEarnings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Driver Earnings"
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        textViewSelectedDate = findViewById(R.id.textViewSelectedDate)
        textViewEarningsTotal = findViewById(R.id.textViewEarningsTotal)
        buttonPickDate = findViewById(R.id.buttonPickDate)
        progressBar = findViewById(R.id.progressBarEarnings)
        textViewError = findViewById(R.id.textViewError)

        buttonPickDate.setOnClickListener { openDatePicker() }

        // Load today's earnings by default
        val today = Calendar.getInstance()
        updateDateLabel(today.time)
        loadEarningsForDate(today)
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance()
                selectedCal.set(Calendar.YEAR, year)
                selectedCal.set(Calendar.MONTH, month)
                selectedCal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                // Normalize to start of day
                selectedCal.set(Calendar.HOUR_OF_DAY, 0)
                selectedCal.set(Calendar.MINUTE, 0)
                selectedCal.set(Calendar.SECOND, 0)
                selectedCal.set(Calendar.MILLISECOND, 0)

                updateDateLabel(selectedCal.time)
                loadEarningsForDate(selectedCal)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateLabel(date: Date) {
        textViewSelectedDate.text = "Date: ${dateFormat.format(date)}"
    }

    private fun loadEarningsForDate(dayCal: Calendar) {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Not signed in", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val driverId = user.uid

        val start = dayCal.clone() as Calendar
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)

        val end = dayCal.clone() as Calendar
        end.set(Calendar.HOUR_OF_DAY, 23)
        end.set(Calendar.MINUTE, 59)
        end.set(Calendar.SECOND, 59)
        end.set(Calendar.MILLISECOND, 999)

        val startMillis = start.timeInMillis
        val endMillis = end.timeInMillis

        showLoading(true)
        firestore.collection("bookinghistory")
            .whereEqualTo("driverId", driverId)
            .whereEqualTo("status", "COMPLETED")
            .whereGreaterThanOrEqualTo("timestamp", startMillis)
            .whereLessThanOrEqualTo("timestamp", endMillis)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { docs ->
                var total = 0.0
                for (doc in docs) {
                    val fare = doc.getDouble("finalFare") ?: doc.getDouble("estimatedFare") ?: 0.0
                    total += fare
                }
                textViewEarningsTotal.text = "Total: ${formatCurrency(total)}"
                textViewError.visibility = View.GONE
                showLoading(false)
            }
            .addOnFailureListener { e ->
                textViewError.text = "Error: ${e.message}\nIf the error mentions an index, please create the composite Firestore index for (driverId, status, timestamp)."
                textViewError.visibility = View.VISIBLE
                textViewEarningsTotal.text = "Total: ${formatCurrency(0.0)}"
                showLoading(false)
            }
    }

    private fun formatCurrency(amount: Double): String {
        return "\u20B1" + String.format(Locale.getDefault(), "%.2f", amount)
    }

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        buttonPickDate.isEnabled = !loading
    }
}
