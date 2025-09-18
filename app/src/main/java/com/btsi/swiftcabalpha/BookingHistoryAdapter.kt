package com.btsi.swiftcabalpha

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.btsi.swiftcabalpha.models.BookingRequest // Corrected import
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookingHistoryAdapter(private val bookingHistoryList: List<BookingRequest>) :
    RecyclerView.Adapter<BookingHistoryAdapter.BookingHistoryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookingHistoryViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_booking_history, parent, false)
        return BookingHistoryViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: BookingHistoryViewHolder, position: Int) {
        val currentItem = bookingHistoryList[position]

        holder.textViewDate.text = holder.itemView.context.getString(R.string.history_date_prefix) + formatDate(currentItem.timestamp)
        holder.textViewDriverName.text = holder.itemView.context.getString(R.string.history_driver_prefix) + (currentItem.driverName ?: "N/A")
        holder.textViewPickup.text = holder.itemView.context.getString(R.string.history_pickup_prefix) + currentItem.pickupAddress
        holder.textViewDestination.text = holder.itemView.context.getString(R.string.history_destination_prefix) + currentItem.destinationAddress
        holder.textViewStatus.text = holder.itemView.context.getString(R.string.history_status_prefix) + currentItem.status
        // Uncomment if you add fare
        // holder.textViewPrice.text = "Fare: ${currentItem.fare ?: \"N/A\"}"
    }

    override fun getItemCount() = bookingHistoryList.size

    private fun formatDate(timestamp: Long?): String {
        if (timestamp == null) return "N/A"
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    class BookingHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textViewDate: TextView = itemView.findViewById(R.id.textViewHistoryDate)
        val textViewDriverName: TextView = itemView.findViewById(R.id.textViewHistoryDriverName) // Added for rider
        val textViewPickup: TextView = itemView.findViewById(R.id.textViewHistoryPickup)
        val textViewDestination: TextView = itemView.findViewById(R.id.textViewHistoryDestination)
        val textViewStatus: TextView = itemView.findViewById(R.id.textViewHistoryStatus)
        // Uncomment if adding fare
        // val textViewPrice: TextView = itemView.findViewById(R.id.textViewHistoryPrice)
    }
}
