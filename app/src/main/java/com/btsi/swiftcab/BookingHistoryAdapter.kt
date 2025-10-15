package com.btsi.swiftcab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcab.models.BookingRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookingHistoryAdapter(
    private val bookingHistoryList: List<BookingRequest>,
    private val userType: String // "driver" or "rider"
) : RecyclerView.Adapter<BookingHistoryAdapter.BookingHistoryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingHistoryViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_booking_history, parent, false)
        return BookingHistoryViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: BookingHistoryViewHolder, position: Int) {
        val currentItem = bookingHistoryList[position]
        val context = holder.itemView.context

        holder.textViewDate.text = context.getString(R.string.history_date, formatDate(currentItem.timestamp))
        if (userType == "driver") {
            holder.textViewUserName.text = context.getString(R.string.history_rider, currentItem.riderName ?: "N/A")
        } else {
            holder.textViewUserName.text = context.getString(R.string.history_driver, currentItem.driverName ?: "N/A")
        }
        holder.textViewPickup.text = context.getString(R.string.history_pickup, currentItem.pickupAddress)
        holder.textViewDestination.text = context.getString(R.string.history_destination, currentItem.destinationAddress)
        holder.textViewStatus.text = context.getString(R.string.history_status, currentItem.status)
    }

    override fun getItemCount() = bookingHistoryList.size

    private fun formatDate(timestamp: Long?): String {
        if (timestamp == null) return "N/A"
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    class BookingHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textViewDate: TextView = itemView.findViewById(R.id.textViewHistoryDate)
        val textViewUserName: TextView = itemView.findViewById(R.id.textViewHistoryUserName)
        val textViewPickup: TextView = itemView.findViewById(R.id.textViewHistoryPickup)
        val textViewDestination: TextView = itemView.findViewById(R.id.textViewHistoryDestination)
        val textViewStatus: TextView = itemView.findViewById(R.id.textViewHistoryStatus)
    }
}
