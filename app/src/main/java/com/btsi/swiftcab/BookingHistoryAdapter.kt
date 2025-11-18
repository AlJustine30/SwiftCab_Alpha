package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RatingBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.BookingRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookingHistoryAdapter(
    private val bookingHistoryList: List<BookingRequest>,
    private val userType: String, // "driver" or "rider"
    private val onRateClick: (BookingRequest) -> Unit,
    private val onReportClick: (BookingRequest) -> Unit
) : RecyclerView.Adapter<BookingHistoryAdapter.BookingHistoryViewHolder>() {

    /**
     * Inflates a booking history list item and creates its ViewHolder.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingHistoryViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_booking_history, parent, false)
        return BookingHistoryViewHolder(itemView)
    }

    /**
     * Binds the booking item at the given position to the ViewHolder.
     */
    override fun onBindViewHolder(holder: BookingHistoryViewHolder, position: Int) {
        val currentItem = bookingHistoryList[position]
        holder.bind(currentItem, userType, onRateClick, onReportClick)
    }

    /**
     * Returns the number of items in the booking history list.
     */
    override fun getItemCount() = bookingHistoryList.size

    class BookingHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textViewDate: TextView = itemView.findViewById(R.id.textViewHistoryDate)
        private val textViewUserName: TextView = itemView.findViewById(R.id.textViewHistoryUserName)
        private val textViewPickup: TextView = itemView.findViewById(R.id.textViewHistoryPickup)
        private val textViewDestination: TextView = itemView.findViewById(R.id.textViewHistoryDestination)
        private val textViewStatus: TextView = itemView.findViewById(R.id.textViewHistoryStatus)
        private val ratingBarHistory: RatingBar = itemView.findViewById(R.id.ratingBarHistory)
        private val textViewPrice: TextView = itemView.findViewById(R.id.textViewHistoryPrice)
        private val textViewDiscount: TextView = itemView.findViewById(R.id.textViewHistoryDiscount)
        private val btnRate: Button = itemView.findViewById(R.id.btnRate)
        private val btnReport: Button = itemView.findViewById(R.id.btnReport)

        /**
         * Binds booking data and actions to the list item views.
         * Shows rate/report buttons for riders and applicable discount/fare.
         *
         * @param booking booking history item
         * @param userType "driver" or "rider" to adjust UI
         * @param onRateClick callback when rider taps rate
         * @param onReportClick callback when rider taps report
         */
        fun bind(booking: BookingRequest, userType: String, onRateClick: (BookingRequest) -> Unit, onReportClick: (BookingRequest) -> Unit) {
            val context = itemView.context
            textViewDate.text = context.getString(R.string.history_date, formatDate(booking.timestamp))
            if (userType == "driver") {
                textViewUserName.text = context.getString(R.string.history_rider, booking.riderName ?: "N/A")
                btnRate.visibility = View.GONE // Drivers rate from the dashboard
                btnReport.visibility = View.GONE // Driver history does not report via rider UI
            } else {
                textViewUserName.text = context.getString(R.string.history_driver, booking.driverName ?: "N/A")
                if (booking.status == "COMPLETED" && !booking.riderRated) {
                    btnRate.visibility = View.VISIBLE
                    btnRate.setOnClickListener { onRateClick(booking) }
                } else {
                    btnRate.visibility = View.GONE
                }
                // Allow reporting on any trip; most relevant for completed trips
                btnReport.visibility = View.VISIBLE
                btnReport.setOnClickListener { onReportClick(booking) }
            }
            textViewPickup.text = context.getString(R.string.history_pickup, booking.pickupAddress)
            textViewDestination.text = context.getString(R.string.history_destination, booking.destinationAddress)
            textViewStatus.text = context.getString(R.string.history_status, booking.status)

            // Show final paid fare if available
            val amount = booking.finalFare ?: booking.estimatedFare
            if (amount != null) {
                textViewPrice.visibility = View.VISIBLE
                textViewPrice.text = context.getString(R.string.fare_label, amount)
            } else {
                textViewPrice.visibility = View.GONE
            }

            // Show discount if applied
            val discountPercent = booking.appliedDiscountPercent ?: 0
            if (discountPercent > 0) {
                // Try to compute amount; fall back to percent if insufficient data
                val base = booking.fareBase
                val perKm = booking.perKmRate
                val km = booking.distanceKm
                val perMin = booking.perMinuteRate
                val minutes = booking.durationMinutes

                val subtotal = if (base != null && perKm != null && km != null && perMin != null && minutes != null) {
                    base + (perKm * km) + (perMin * minutes)
                } else null

                val discountAmount = when {
                    subtotal != null && booking.finalFare != null -> (subtotal - booking.finalFare!!).coerceAtLeast(0.0)
                    subtotal != null -> (subtotal * (discountPercent / 100.0))
                    else -> null
                }

                textViewDiscount.visibility = View.VISIBLE
                textViewDiscount.text = if (discountAmount != null && discountAmount > 0.0) {
                    String.format(java.util.Locale.getDefault(), "Discount: - ₱%.2f (%d%%)", discountAmount, discountPercent)
                } else {
                    String.format(java.util.Locale.getDefault(), "Discount Applied: %d%%", discountPercent)
                }
            } else {
                textViewDiscount.visibility = View.GONE
            }

            val rating = booking.riderRating
            if (rating != null && rating > 0f) {
                ratingBarHistory.visibility = View.VISIBLE
                ratingBarHistory.rating = rating
            } else {
                ratingBarHistory.visibility = View.GONE
            }
        }

        /**
         * Formats a timestamp to a human‑readable date and time, or "N/A".
         *
         * @param timestamp epoch millis or null
         * @return formatted date string
         */
        private fun formatDate(timestamp: Long?): String {
            if (timestamp == null) return "N/A"
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
}
