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

class DriverEarningsAdapter(
    private val items: MutableList<BookingRequest>
) : RecyclerView.Adapter<DriverEarningsAdapter.EarningViewHolder>() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun setData(newItems: List<BookingRequest>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EarningViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_driver_earning, parent, false)
        return EarningViewHolder(view)
    }

    override fun onBindViewHolder(holder: EarningViewHolder, position: Int) {
        val item = items[position]
        val ts = item.timestamp ?: 0L
        holder.textViewEarningTime.text = timeFormat.format(Date(ts))

        val route = "${item.pickupAddress ?: "Unknown"} → ${item.destinationAddress ?: "Unknown"}"
        holder.textViewRoute.text = route

        val fare = item.finalFare ?: item.estimatedFare ?: 0.0
        holder.textViewFare.text = String.format(Locale.getDefault(), "\u20B1%.2f", fare)
    }

    override fun getItemCount(): Int = items.size

    class EarningViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textViewEarningTime: TextView = itemView.findViewById(R.id.textViewEarningTime)
        val textViewRoute: TextView = itemView.findViewById(R.id.textViewRoute)
        val textViewFare: TextView = itemView.findViewById(R.id.textViewFare)
    }
}
