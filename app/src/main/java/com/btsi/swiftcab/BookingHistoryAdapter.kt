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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingHistoryViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_booking_history, parent, false)
        return BookingHistoryViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: BookingHistoryViewHolder, position: Int) {
        val currentItem = bookingHistoryList[position]
        holder.bind(currentItem, userType, onRateClick, onReportClick)
    }

    override fun getItemCount() = bookingHistoryList.size

    class BookingHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textViewDate: TextView = itemView.findViewById(R.id.textViewHistoryDate)
        private val textViewUserName: TextView = itemView.findViewById(R.id.textViewHistoryUserName)
        private val textViewPickup: TextView = itemView.findViewById(R.id.textViewHistoryPickup)
        private val textViewDestination: TextView = itemView.findViewById(R.id.textViewHistoryDestination)
        private val textViewStatus: TextView = itemView.findViewById(R.id.textViewHistoryStatus)
        private val ratingBarHistory: RatingBar = itemView.findViewById(R.id.ratingBarHistory)
        private val textViewPrice: TextView = itemView.findViewById(R.id.textViewHistoryPrice)
        private val btnRate: Button = itemView.findViewById(R.id.btnRate)
        private val btnReport: Button = itemView.findViewById(R.id.btnReport)

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

            val rating = booking.riderRating
            if (rating != null && rating > 0f) {
                ratingBarHistory.visibility = View.VISIBLE
                ratingBarHistory.rating = rating
            } else {
                ratingBarHistory.visibility = View.GONE
            }
        }

        private fun formatDate(timestamp: Long?): String {
            if (timestamp == null) return "N/A"
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
}
