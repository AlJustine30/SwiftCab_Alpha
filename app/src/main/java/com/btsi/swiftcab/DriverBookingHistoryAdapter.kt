package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RatingBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import com.btsi.swiftcab.models.BookingRequest // Corrected import

class DriverBookingHistoryAdapter(private var bookingHistoryList: List<BookingRequest>) :
    RecyclerView.Adapter<DriverBookingHistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateTextView: TextView = view.findViewById(R.id.textViewDriverHistoryDate)
        val riderNameTextView: TextView = view.findViewById(R.id.textViewDriverHistoryRiderName)
        val pickupTextView: TextView = view.findViewById(R.id.textViewDriverHistoryPickup)
        val destinationTextView: TextView = view.findViewById(R.id.textViewDriverHistoryDestination)
        val statusTextView: TextView = view.findViewById(R.id.textViewDriverHistoryStatus)
        val ratingBar: RatingBar = view.findViewById(R.id.ratingBarDriverHistory)
        val priceTextView: TextView = view.findViewById(R.id.textViewDriverHistoryPrice)
        val discountTextView: TextView = view.findViewById(R.id.textViewDriverHistoryDiscount)
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

        val amount = booking.finalFare ?: booking.estimatedFare
        if (amount != null) {
            holder.priceTextView.visibility = View.VISIBLE
            holder.priceTextView.text = holder.itemView.context.getString(R.string.fare_label, amount)
        } else {
            holder.priceTextView.visibility = View.GONE
        }

        // Show discount if applied
        val discountPercent = booking.appliedDiscountPercent ?: 0
        if (discountPercent > 0) {
            val base = booking.fareBase
            val perKm = booking.perKmRate
            val km = booking.distanceKm
            val perMin = booking.perMinuteRate
            val minutes = booking.durationMinutes

            val subtotal = if (base != null && perKm != null && km != null && perMin != null && minutes != null) {
                base + (perKm * km) + (perMin * minutes)
            } else null

            val discountAmount = when {
                subtotal != null && amount != null -> (subtotal - amount).coerceAtLeast(0.0)
                subtotal != null -> (subtotal * (discountPercent / 100.0))
                else -> null
            }

            holder.discountTextView.visibility = View.VISIBLE
            holder.discountTextView.text = if (discountAmount != null && discountAmount > 0.0) {
                String.format(java.util.Locale.getDefault(), "Discount: - ₱%.2f (%d%%)", discountAmount, discountPercent)
            } else {
                String.format(java.util.Locale.getDefault(), "Discount Applied: %d%%", discountPercent)
            }
        } else {
            holder.discountTextView.visibility = View.GONE
        }

        val rating = booking.riderRating
        if (rating != null && rating > 0f) {
            holder.ratingBar.visibility = View.VISIBLE
            holder.ratingBar.rating = rating
        } else {
            holder.ratingBar.visibility = View.GONE
        }
    }

    override fun getItemCount() = bookingHistoryList.size

    fun updateData(newHistoryList: List<BookingRequest>) {
        bookingHistoryList = newHistoryList
        notifyDataSetChanged()
    }
}
