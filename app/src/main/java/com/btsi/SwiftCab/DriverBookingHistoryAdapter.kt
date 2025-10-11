package com.btsi.SwiftCab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import com.btsi.SwiftCab.models.BookingRequest // Corrected import

class DriverBookingHistoryAdapter(private var bookingHistoryList: List<BookingRequest>) :
    RecyclerView.Adapter<DriverBookingHistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTextView: TextView = view.findViewById(R.id.textViewDriverHistoryDate)
        val riderNameTextView: TextView = view.findViewById(R.id.textViewDriverHistoryRiderName)
        val pickupTextView: TextView = view.findViewById(R.id.textViewDriverHistoryPickup)
        val destinationTextView: TextView = view.findViewById(R.id.textViewDriverHistoryDestination)
        val statusTextView: TextView = view.findViewById(R.id.textViewDriverHistoryStatus)
        // val priceTextView: TextView = view.findViewById(R.id.textViewDriverHistoryPrice) // Uncomment if price is used
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_driver_booking_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val booking = bookingHistoryList[position]

        holder.dateTextView.text = booking.timestamp?.let { "Date: ${dateFormat.format(Date(it))}" } ?: "Date: N/A"
        holder.riderNameTextView.text = "Rider: ${booking.riderName ?: "N/A"}"
        holder.pickupTextView.text = "From: ${booking.pickupAddress ?: "N/A"}"
        holder.destinationTextView.text = "To: ${booking.destinationAddress ?: "N/A"}"
        holder.statusTextView.text = "Status: ${booking.status ?: "N/A"}"
        // holder.priceTextView.text = String.format("Fare: $%.2f", booking.fare ?: 0.0) // to be added
        // holder.priceTextView.visibility = if (booking.fare != null) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = bookingHistoryList.size

    fun updateData(newHistoryList: List<BookingRequest>) {
        bookingHistoryList = newHistoryList
        notifyDataSetChanged()
    }
}
